package com.myagent.connectorconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.common.BizException;
import com.myagent.common.CryptoService;
import com.myagent.connectorconfig.dto.ConnectorRequest;
import com.myagent.connectorconfig.dto.ConnectorResponse;
import com.myagent.connectorconfig.dto.ConnectorTestResult;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 连接器配置（CRUD + 凭据加密 + 属主校验 + 连通性探测 + 发送限流）。
 *
 * <p>本类**不 import 任何 AgentScope 类型**（门禁 G2）：工具注册与邮件协议实现都在
 * {@code com.myagent.agui.connector}，这里只处理"配置与安全"。
 *
 * <p>归属模型：连接器属于**用户**。写入/测试/绑定都要求是本人（管理员可代操作查看）。
 */
@Service
public class ConnectorService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorService.class);

    /** 允许的入站模式。 */
    static final List<String> INBOUND_MODES = List.of("OFF", "NOTIFY", "AUTOREPLY");

    private final ConnectorStore store;
    private final ConnectorTypeRegistry typeRegistry;
    private final CryptoService crypto;
    private final ObjectProvider<ConnectorProbePort> probePorts;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final ObjectMapper json = new ObjectMapper();
    private final int maxSendsPerHour;

    public ConnectorService(
            ConnectorStore store,
            ConnectorTypeRegistry typeRegistry,
            CryptoService crypto,
            ObjectProvider<ConnectorProbePort> probePorts,
            org.springframework.context.ApplicationEventPublisher events,
            @Value("${myagent.connector.mail.max-sends-per-hour:60}") int maxSendsPerHour) {
        this.store = store;
        this.typeRegistry = typeRegistry;
        this.crypto = crypto;
        this.probePorts = probePorts;
        this.events = events;
        this.maxSendsPerHour = maxSendsPerHour <= 0 ? 60 : maxSendsPerHour;
    }

    // ===================== 查询 =====================

    /** 当前用户能看到的连接器：普通用户只看自己的，管理员可看全部。 */
    public List<ConnectorResponse> list(Long userId, boolean admin, boolean all) {
        List<ConnectorRecord> records = admin && all ? store.listAll() : store.listByOwner(userId);
        List<ConnectorResponse> result = new ArrayList<>(records.size());
        for (ConnectorRecord record : records) {
            result.add(toResponse(record));
        }
        return result;
    }

    public ConnectorResponse get(Long id, Long userId, boolean admin) {
        ConnectorRecord record = require(id);
        requireAccess(record, userId, admin);
        return toResponse(record);
    }

    /** 某 Agent 绑定了哪些连接器类型（决定注册哪些工具）。 */
    public List<String> typesBoundToAgent(long agentId) {
        return store.typesByAgent(agentId);
    }

    /** 取原始记录（含密文），调用方自行校验权限。 */
    public ConnectorRecord require(Long id) {
        if (id == null) {
            throw BizException.badRequest("连接器 id 不能为空");
        }
        return store.find(id)
                .orElseThrow(() -> BizException.notFound("连接器不存在: " + id));
    }

    /** 解密后的配置（只在服务端内部用，绝不回显）。 */
    public Map<String, String> config(ConnectorRecord record) {
        return decrypt(record.configEnc());
    }

    /**
     * 某 Agent 上、**当前用户自己的**连接器（含解密配置）。
     *
     * <p>工具在调用时用它解析"该用哪个邮箱"：所以多人共用一个 Agent 时，
     * 每个人只会碰自己的邮箱，不会串号。
     */
    public List<ResolvedConnector> resolveForUser(long agentId, long userId) {
        return store.listByAgentAndOwner(agentId, userId).stream()
                .map(record -> new ResolvedConnector(record, config(record)))
                .toList();
    }

    /** 解析结果：记录 + 明文配置。 */
    public record ResolvedConnector(ConnectorRecord record, Map<String, String> config) {
        public String label() {
            return record.displayName();
        }
    }

    // ===================== 写 =====================

    @Transactional
    public ConnectorResponse create(ConnectorRequest request, Long ownerUserId) {
        ConnectorTypeDescriptor descriptor = typeRegistry.require(request.type());
        String name = requireName(request.name());
        Map<String, String> config = normalizeConfig(request.config());
        List<String> missing = descriptor.missingKeys(config);
        if (!missing.isEmpty()) {
            throw BizException.badRequest("缺少必填配置：" + String.join("、", labels(descriptor, missing)));
        }
        validateFieldValues(descriptor, config);
        String alias = normalizeAlias(request.alias());
        String inboundMode = normalizeInboundMode(request.inboundMode(), descriptor);
        long id;
        try {
            id = store.insert(
                    descriptor.typeUpper(),
                    name,
                    alias,
                    ownerUserId,
                    crypto.encrypt(toJson(config)),
                    request.enabled() == null || request.enabled(),
                    inboundMode);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // 同一用户下「类型 + 名称」唯一：以前这里直接把数据库异常抛成 500，
            // 用户只看到"服务器内部错误"；现在告诉他换个名字或者去编辑已有的那个。
            throw BizException.conflict("你已经有一个叫「" + name + "」的" + descriptor.displayName()
                    + "连接器了：换个名字，或者直接编辑已有的那个");
        }
        if (request.agentIds() != null) {
            store.replaceAgents(id, distinct(request.agentIds()));
        }
        log.info("新建连接器 id={} type={} name={} owner={} agents={}",
                id, descriptor.typeUpper(), name, ownerUserId, request.agentIds());
        publishChanged(id, store.agentIdsOf(id));
        return get(id, ownerUserId, true);
    }

    @Transactional
    public ConnectorResponse update(Long id, ConnectorRequest request, Long userId, boolean admin) {
        ConnectorRecord existing = require(id);
        requireAccess(existing, userId, admin);
        ConnectorTypeDescriptor descriptor = typeRegistry.require(existing.type());
        String name = request.name() == null || request.name().isBlank()
                ? existing.name()
                : requireName(request.name());
        List<Long> agentIdsBefore = store.agentIdsOf(id);
        Map<String, String> before = config(existing);
        Map<String, String> merged = new LinkedHashMap<>(before);
        if (request.config() != null) {
            request.config().forEach((key, value) -> {
                if (key == null) {
                    return;
                }
                // 前端回显的掩码不覆盖真实值；空串表示"清空这个非敏感项"
                if (ConnectorRequest.MASK.equals(value)) {
                    return;
                }
                if (value == null || value.isBlank()) {
                    merged.remove(key);
                } else {
                    merged.put(key, value.trim());
                }
            });
        }
        List<String> missing = descriptor.missingKeys(merged);
        if (!missing.isEmpty()) {
            throw BizException.badRequest("缺少必填配置：" + String.join("、", labels(descriptor, missing)));
        }
        validateFieldValues(descriptor, merged);
        String alias = request.alias() == null ? existing.alias() : normalizeAlias(request.alias());
        String inboundMode = request.inboundMode() == null
                ? existing.inboundMode()
                : normalizeInboundMode(request.inboundMode(), descriptor);
        boolean enabled = request.enabled() == null ? existing.enabled() : request.enabled();
        // 只有配置真的变了才把探测状态打回"未测试"（改名字/改开关不该让状态失效）
        boolean configChanged = !merged.equals(before);
        store.update(id, name, alias, crypto.encrypt(toJson(merged)), enabled, inboundMode, configChanged);
        // 入站模式一变就清掉旧游标：下一次轮询重新打基线，从**这一刻之后**到达的邮件算起。
        // 否则（例如从"只记录"改成"推 IM 通知"）会拿很久以前的游标，
        // 一开启就把这段时间的历史未读邮件全推一遍 —— 用户实测被推了 7 月的邮件。
        if (inboundModeChanged(existing.inboundMode(), inboundMode)) {
            store.resetInboundCursor(id);
        }
        log.info("更新连接器 id={} name={} enabled={} configChanged={} inboundMode={}",
                id, name, enabled, configChanged, inboundMode);
        if (request.agentIds() != null) {
            store.replaceAgents(id, distinct(request.agentIds()));
        }
        java.util.LinkedHashSet<Long> affected = new java.util.LinkedHashSet<>();
        affected.addAll(agentIdsBefore);
        affected.addAll(store.agentIdsOf(id));
        publishChanged(id, new ArrayList<>(affected));
        return get(id, userId, true);
    }

    @Transactional
    public void delete(Long id, Long userId, boolean admin) {
        ConnectorRecord record = require(id);
        requireAccess(record, userId, admin);
        List<Long> agentIdsBefore = store.agentIdsOf(id);
        store.delete(id);
        log.info("删除连接器 id={} type={} owner={}", id, record.type(), record.ownerUserId());
        publishChanged(id, agentIdsBefore);
    }

    /** 绑定/解绑（整体替换）。 */
    @Transactional
    public List<Long> bindAgents(Long id, List<Long> agentIds, Long userId, boolean admin) {
        ConnectorRecord record = require(id);
        requireAccess(record, userId, admin);
        List<Long> agentIdsBefore = store.agentIdsOf(id);
        List<Long> distinct = distinct(agentIds);
        store.replaceAgents(id, distinct);
        java.util.LinkedHashSet<Long> affected = new java.util.LinkedHashSet<>(agentIdsBefore);
        affected.addAll(distinct);
        publishChanged(id, new ArrayList<>(affected));
        return distinct;
    }

    public List<Long> boundAgents(Long id, Long userId, boolean admin) {
        ConnectorRecord record = require(id);
        requireAccess(record, userId, admin);
        return store.agentIdsOf(id);
    }

    // ===================== 测试连接 =====================

    /**
     * 探测一个**已保存**的连接器：真实登录 IMAP + SMTP。
     *
     * <p>结果写入 {@code status/last_error}，界面上能直接看到"上次检查"与失败原因。
     */
    public ConnectorTestResult test(Long id, Long userId, boolean admin) {
        ConnectorRecord record = require(id);
        requireAccess(record, userId, admin);
        ConnectorTestResult result = probe(record.type(), config(record), userId);
        store.markProbe(id, result.ok(), result.ok() ? null : result.message());
        return result;
    }

    /** 用尚未保存的配置探测（新建表单里"测试连接"，不落库）。 */
    public ConnectorTestResult testDraft(String type, Map<String, String> config) {
        ConnectorTypeDescriptor descriptor = typeRegistry.require(type);
        Map<String, String> normalized = normalizeConfig(config);
        List<String> missing = descriptor.missingKeys(normalized);
        if (!missing.isEmpty()) {
            throw BizException.badRequest("缺少必填配置：" + String.join("、", labels(descriptor, missing)));
        }
        validateFieldValues(descriptor, normalized);
        return probe(descriptor.typeUpper(), normalized, null);
    }

    private ConnectorTestResult probe(String type, Map<String, String> config, Long userId) {
        ConnectorProbePort port = probePorts.stream()
                .filter(candidate -> candidate.type().equalsIgnoreCase(type))
                .findFirst()
                .orElseThrow(() -> BizException.badRequest("该类型暂不支持连通性测试: " + type));
        try {
            return port.probe(config, userId);
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return new ConnectorTestResult(false, "测试失败：" + message, List.of());
        }
    }

    // ===================== 审计 / 限流 =====================

    /** 记一条写操作审计（发信/回复/标记/删除）。 */
    public void logAction(long connectorId, Long userId, String action, String target, String subject,
            boolean success, String error) {
        try {
            store.logAction(connectorId, userId, action, target, subject, success, error);
        } catch (RuntimeException ex) {
            log.warn("写连接器审计失败 connectorId={} action={}: {}", connectorId, action, ex.toString());
        }
    }

    /** 每连接器每小时的发信上限（默认 60）：超了直接拒绝，避免模型在循环里把邮箱发爆。 */
    public void assertSendQuota(long connectorId) {
        Instant hourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
        int sent = store.countActionsSince(connectorId, "SEND", hourAgo)
                + store.countActionsSince(connectorId, "REPLY", hourAgo);
        if (sent >= maxSendsPerHour) {
            throw BizException.badRequest("该邮箱本小时已发送 " + sent + " 封，达到上限（"
                    + maxSendsPerHour + " 封/小时）。稍后再试，或让用户确认是否要调整上限。");
        }
    }

    /** 本连接器的写操作记录（工具内部兜底用，不做属主校验）。 */
    public List<Map<String, Object>> actionsOf(long connectorId, int limit) {
        return store.recentActions(connectorId, limit);
    }

    public List<Map<String, Object>> recentActions(Long id, Long userId, boolean admin, int limit) {
        ConnectorRecord record = require(id);
        requireAccess(record, userId, admin);
        return store.recentActions(id, limit);
    }

    // ===================== 内部工具 =====================

    /**
     * 广播"连接器变了"。
     *
     * <p>必须**事务提交后**再发：监听者会去重建 Agent、读库里的新配置；
     * 提交前发它读到的还是旧数据（渠道配置那边踩过同样的坑）。
     */
    private void publishChanged(long connectorId, List<Long> agentIds) {
        List<Long> affected = agentIds == null ? List.of() : agentIds;
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            events.publishEvent(new com.myagent.connectorconfig.event.ConnectorConfigChangedEvent(
                    connectorId, affected));
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        events.publishEvent(
                                new com.myagent.connectorconfig.event.ConnectorConfigChangedEvent(
                                        connectorId, affected));
                    }
                });
    }

    /** 只有属主（或管理员）能动这个连接器。 */
    public void requireAccess(ConnectorRecord record, Long userId, boolean admin) {
        if (admin) {
            return;
        }
        if (userId == null || record.ownerUserId() != userId) {
            throw BizException.badRequest("只能操作自己的连接器");
        }
    }

    /** 取用户自己的某个连接器（工具里按 id/名称/别名解析）。 */
    public ConnectorRecord requireOwned(long connectorId, long userId) {
        ConnectorRecord record = require(connectorId);
        if (record.ownerUserId() != userId) {
            throw BizException.badRequest("只能使用自己的连接器");
        }
        return record;
    }

    public Optional<ConnectorRecord> findByAlias(long ownerUserId, String type, String aliasOrName) {
        if (aliasOrName == null || aliasOrName.isBlank()) {
            return Optional.empty();
        }
        String key = aliasOrName.trim().toLowerCase(Locale.ROOT);
        return store.listByOwner(ownerUserId).stream()
                .filter(record -> record.type().equalsIgnoreCase(type))
                .filter(record -> key.equals(lower(record.alias())) || key.equals(lower(record.name())))
                .findFirst();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private ConnectorResponse toResponse(ConnectorRecord record) {
        ConnectorTypeDescriptor descriptor = typeRegistry.require(record.type());
        Map<String, String> masked = new LinkedHashMap<>();
        config(record).forEach((key, value) ->
                masked.put(key, descriptor.isSecret(key) && value != null && !value.isBlank()
                        ? ConnectorRequest.MASK
                        : value));
        List<Long> agentIds = store.agentIdsOf(record.id());
        return new ConnectorResponse(
                record.id(),
                record.type(),
                descriptor.displayName(),
                record.name(),
                record.alias(),
                masked.getOrDefault("email", masked.getOrDefault("account", "")),
                record.enabled(),
                record.status(),
                record.lastError(),
                record.lastCheckedAt(),
                record.inboundMode(),
                descriptor.capabilities().stream().map(Enum::name).toList(),
                masked,
                agentIds,
                record.createdAt(),
                record.updatedAt());
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw BizException.badRequest("连接器名称不能为空（用于区分多个邮箱）");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 64) {
            throw BizException.badRequest("连接器名称最多 64 个字符");
        }
        return trimmed;
    }

    /** 别名：只允许小写字母开头 + 小写字母数字下划线，工具名要用它。 */
    static String normalizeAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return null;
        }
        String value = alias.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("^[a-z][a-z0-9_]{1,23}$")) {
            throw BizException.badRequest("别名只能用小写字母开头，含小写字母/数字/下划线，长度 2-24");
        }
        return value;
    }

    private static boolean inboundModeChanged(String before, String after) {
        return !java.util.Objects.equals(
                before == null ? "OFF" : before.toUpperCase(Locale.ROOT),
                after == null ? "OFF" : after.toUpperCase(Locale.ROOT));
    }

    static String normalizeInboundMode(String mode, ConnectorTypeDescriptor descriptor) {
        if (mode == null || mode.isBlank()) {
            return "OFF";
        }
        String value = mode.trim().toUpperCase(Locale.ROOT);
        if (!INBOUND_MODES.contains(value)) {
            throw BizException.badRequest("不支持的入站模式: " + mode + "（可选 OFF/NOTIFY/AUTOREPLY）");
        }
        if (!"OFF".equals(value) && !descriptor.inboundSupported()) {
            throw BizException.badRequest("该连接器类型不支持新邮件处理");
        }
        return value;
    }

    /**
     * 按字段声明的控件类型校验取值。
     *
     * <p>为什么要在服务端再校验一遍：前端下拉只是"好填"，接口仍可能被脚本/旧表单直接调用；
     * 端口写成 {@code abc}、SSL 写成 {@code yes} 这类值进去后，报错会出现在收发信时（很难查）。
     */
    static void validateFieldValues(ConnectorTypeDescriptor descriptor, Map<String, String> config) {
        for (ConnectorTypeDescriptor.Field field : descriptor.fields()) {
            String value = config.get(field.key());
            if (value == null || value.isBlank()) {
                continue;
            }
            if (field.type() == ConnectorTypeDescriptor.FieldType.NUMBER
                    && !value.trim().matches("[0-9]{1,5}")) {
                throw BizException.badRequest(field.label() + " 必须是数字（例如 993），当前是「" + value + "」");
            }
            if (field.type() == ConnectorTypeDescriptor.FieldType.TAGS) {
                for (String tag : value.split("[,;，；\s]+")) {
                    String item = tag.trim();
                    if (item.isEmpty()) {
                        continue;
                    }
                    // 白名单里只会出现"邮箱地址"或"@域名"；写成别的（例如中文名）根本匹配不上，
                    // 与其保存后用户以为"我加过了"，不如保存时就指出哪一项不对
                    if (!item.contains("@")) {
                        throw BizException.badRequest(field.label() + " 里的「" + item
                                + "」不是邮箱地址；整域请写成 @域名.com");
                    }
                }
            }
            if (field.isSelect() && !field.accepts(value)) {
                String allowed = field.options().stream()
                        .map(option -> option.label() + "（" + option.value() + "）")
                        .reduce((a, b) -> a + "、" + b)
                        .orElse("");
                throw BizException.badRequest(field.label() + " 只能选：" + allowed
                        + "，当前是「" + value + "」");
            }
        }
    }

    private static List<String> labels(ConnectorTypeDescriptor descriptor, List<String> keys) {
        Map<String, String> labelByKey = new LinkedHashMap<>();
        descriptor.fields().forEach(field -> labelByKey.put(field.key(), field.label()));
        return keys.stream().map(key -> labelByKey.getOrDefault(key, key)).toList();
    }

    private static List<Long> distinct(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    private static Map<String, String> normalizeConfig(Map<String, String> config) {
        Map<String, String> result = new LinkedHashMap<>();
        if (config == null) {
            return result;
        }
        config.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                return;
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty() || ConnectorRequest.MASK.equals(trimmed)) {
                return;
            }
            result.put(key.trim(), trimmed);
        });
        return result;
    }

    private String toJson(Map<String, String> config) {
        try {
            return json.writeValueAsString(config);
        } catch (Exception ex) {
            throw BizException.badRequest("配置序列化失败：" + ex.getMessage());
        }
    }

    private Map<String, String> decrypt(String cipherText) {
        String plain = crypto.decrypt(cipherText);
        if (plain == null || plain.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return json.readValue(plain, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception ex) {
            log.warn("连接器配置解析失败: {}", ex.toString());
            return new LinkedHashMap<>();
        }
    }

    /** 供入站轮询使用的时间戳（测试可注入）。 */
    OffsetDateTime now() {
        return OffsetDateTime.now();
    }
}
