package com.myagent.channelconfig.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myagent.agentconfig.entity.Agent;
import com.myagent.agentconfig.mapper.AgentMapper;
import com.myagent.auth.entity.SysUser;
import com.myagent.auth.mapper.SysUserMapper;
import com.myagent.channelconfig.dto.ChannelAppRequest;
import com.myagent.channelconfig.dto.ChannelAppResponse;
import com.myagent.channelconfig.dto.ChannelPeerResponse;
import com.myagent.channelconfig.entity.ChannelApp;
import com.myagent.channelconfig.entity.ChannelPeer;
import com.myagent.channelconfig.event.ChannelConfigChangedEvent;
import com.myagent.channelconfig.mapper.ChannelAppMapper;
import com.myagent.channelconfig.mapper.ChannelPeerMapper;
import com.myagent.common.BizException;
import com.myagent.common.CryptoService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 渠道应用配置（CRUD + 凭据加密 + 变更广播）。
 *
 * <p>本类**不 import 任何 AgentScope 类型**（门禁 G2）：真正的渠道生命周期由
 * {@code com.myagent.agui.channel.ChannelRuntime} 监听 {@link ChannelConfigChangedEvent} 完成。
 */
@Service
public class ChannelAppService {

    private static final Logger log = LoggerFactory.getLogger(ChannelAppService.class);

    /** 支持的渠道类型（飞书后续接入，表结构已预留）。 */
    public static final Set<String> SUPPORTED_TYPES = Set.of("DINGTALK", "FEISHU");

    /** 会被掩码的敏感键（回显时替换成 ***）。 */
    private static final Set<String> SECRET_KEYS = Set.of("appSecret", "encryptKey", "aesKey", "verificationToken");

    /** 钉钉必填键。 */
    private static final List<String> DINGTALK_REQUIRED = List.of("appKey", "appSecret", "robotCode");

    private final ChannelAppMapper appMapper;
    private final ChannelPeerMapper peerMapper;
    private final AgentMapper agentMapper;
    private final SysUserMapper userMapper;
    private final CryptoService crypto;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json = new ObjectMapper();

    public ChannelAppService(
            ChannelAppMapper appMapper,
            ChannelPeerMapper peerMapper,
            AgentMapper agentMapper,
            SysUserMapper userMapper,
            CryptoService crypto,
            ApplicationEventPublisher events) {
        this.appMapper = appMapper;
        this.peerMapper = peerMapper;
        this.agentMapper = agentMapper;
        this.userMapper = userMapper;
        this.crypto = crypto;
        this.events = events;
    }

    // ===================== 查询 =====================

    public List<ChannelAppResponse> list() {
        List<ChannelApp> apps = appMapper.selectList(
                Wrappers.<ChannelApp>lambdaQuery().orderByDesc(ChannelApp::getId));
        List<ChannelAppResponse> result = new ArrayList<>(apps.size());
        for (ChannelApp app : apps) {
            result.add(toResponse(app));
        }
        return result;
    }

    public ChannelAppResponse get(Long id) {
        return toResponse(require(id));
    }

    /** 渠道运行时要用的明文配置。 */
    public Map<String, String> config(Long id) {
        return decryptConfig(require(id));
    }

    public ChannelApp require(Long id) {
        ChannelApp app = id == null ? null : appMapper.selectById(id);
        if (app == null) {
            throw BizException.notFound("渠道不存在: " + id);
        }
        return app;
    }

    public List<ChannelPeerResponse> peers(Long appId) {
        require(appId);
        List<ChannelPeer> peers = peerMapper.selectList(Wrappers.<ChannelPeer>lambdaQuery()
                .eq(ChannelPeer::getChannelAppId, appId)
                .orderByDesc(ChannelPeer::getLastActiveAt));
        List<ChannelPeerResponse> result = new ArrayList<>(peers.size());
        for (ChannelPeer p : peers) {
            result.add(new ChannelPeerResponse(
                    p.getId(), p.getPeerKey(), p.getPeerKind(), p.getPeerName(),
                    p.getChatSessionId(), p.getLastActiveAt()));
        }
        return result;
    }

    /** 启用中的渠道（运行时启动时用）。 */
    public List<ChannelApp> enabledApps() {
        return appMapper.selectList(Wrappers.<ChannelApp>lambdaQuery().eq(ChannelApp::getEnabled, true));
    }

    // ===================== 写入 =====================

    @Transactional
    public ChannelAppResponse create(ChannelAppRequest request, Long currentUserId) {
        validate(request, null);
        ChannelApp app = new ChannelApp();
        app.setChannelType(request.channelType().trim().toUpperCase());
        app.setName(request.name().trim());
        app.setAgentId(request.agentId());
        app.setOwnerUserId(request.ownerUserId() == null ? currentUserId : request.ownerUserId());
        app.setEnabled(Boolean.TRUE.equals(request.enabled()));
        app.setStatus("STOPPED");
        app.setConfigEnc(crypto.encrypt(writeJson(mergeConfig(null, request.config()))));
        app.setCreatedAt(OffsetDateTime.now());
        app.setUpdatedAt(OffsetDateTime.now());
        appMapper.insert(app);
        publish(app.getId(), false);
        return toResponse(app);
    }

    @Transactional
    public ChannelAppResponse update(Long id, ChannelAppRequest request, Long currentUserId) {
        ChannelApp existing = require(id);
        validate(request, existing);
        String type = request.channelType() == null ? existing.getChannelType() : request.channelType().trim().toUpperCase();
        if (!type.equals(existing.getChannelType())) {
            throw BizException.badRequest("渠道类型不可修改（请新建一个渠道应用）");
        }
        existing.setName(request.name().trim());
        existing.setAgentId(request.agentId());
        if (request.ownerUserId() != null) {
            existing.setOwnerUserId(request.ownerUserId());
        }
        if (request.enabled() != null) {
            existing.setEnabled(request.enabled());
        }
        existing.setConfigEnc(crypto.encrypt(writeJson(mergeConfig(existing, request.config()))));
        existing.setUpdatedAt(OffsetDateTime.now());
        appMapper.updateById(existing);
        publish(id, false);
        return toResponse(existing);
    }

    @Transactional
    public ChannelAppResponse setEnabled(Long id, boolean enabled) {
        ChannelApp app = require(id);
        app.setEnabled(enabled);
        app.setUpdatedAt(OffsetDateTime.now());
        appMapper.updateById(app);
        publish(id, false);
        return toResponse(app);
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        appMapper.deleteById(id);
        publish(id, true);
    }

    /** 手动重载渠道（改完凭据/网络后不必重启整个应用）。 */
    public void restart(Long id) {
        require(id);
        publish(id, false);
    }

    /** 运行时回报状态（RUNNING / ERROR + 最近错误）。 */
    public void markStatus(Long id, String status, String lastError) {
        ChannelApp app = appMapper.selectById(id);
        if (app == null) {
            return;
        }
        app.setStatus(status);
        app.setLastError(lastError);
        app.setUpdatedAt(OffsetDateTime.now());
        appMapper.updateById(app);
    }

    // ===================== 内部 =====================

    private void publish(Long appId, boolean deleted) {
        try {
            events.publishEvent(new ChannelConfigChangedEvent(appId, deleted));
        } catch (Exception ex) {
            // 广播失败不影响配置保存；渠道会在下次启动/重启时对齐
            log.warn("渠道变更事件广播失败 appId={}: {}", appId, ex.toString());
        }
    }

    private void validate(ChannelAppRequest request, ChannelApp existing) {
        String type = request.channelType() == null ? null : request.channelType().trim().toUpperCase();
        if (type != null && !SUPPORTED_TYPES.contains(type)) {
            throw BizException.badRequest("不支持的渠道类型: " + request.channelType());
        }
        Agent agent = agentMapper.selectById(request.agentId());
        if (agent == null) {
            throw BizException.badRequest("绑定的 Agent 不存在: " + request.agentId());
        }
        // IM 里没有审批卡片：绑定需要人工审批的 Agent 会让每轮对话都卡住，必须在保存时就拦下
        if ("REQUIRE_APPROVAL".equalsIgnoreCase(agent.getPermissionMode())) {
            throw BizException.badRequest(
                    "该 Agent 处于「需要人工审批」模式，IM 渠道没有审批界面。请先在 Agent 配置里改为自动允许。");
        }
        Long owner = request.ownerUserId() != null
                ? request.ownerUserId()
                : (existing == null ? null : existing.getOwnerUserId());
        if (owner != null && userMapper.selectById(owner) == null) {
            throw BizException.badRequest("渠道会话属主不存在: " + owner);
        }
        Map<String, String> merged = mergeConfig(existing, request.config());
        String effectiveType = type != null
                ? type
                : (existing == null ? null : existing.getChannelType());
        if ("DINGTALK".equals(effectiveType)) {
            for (String key : DINGTALK_REQUIRED) {
                if (isBlank(merged.get(key))) {
                    throw BizException.badRequest("钉钉渠道缺少必填配置: " + key);
                }
            }
        }
    }

    /** 更新时允许只回传部分配置（例如不改密钥），因此与旧值合并。 */
    private Map<String, String> mergeConfig(ChannelApp existing, Map<String, String> incoming) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (existing != null) {
            merged.putAll(decryptConfig(existing));
        }
        if (incoming != null) {
            incoming.forEach((key, value) -> {
                if (key == null) {
                    return;
                }
                // 前端把密钥回显成 *** 时不覆盖真实值
                if ("***".equals(value)) {
                    return;
                }
                if (value == null || value.isBlank()) {
                    merged.remove(key);
                } else {
                    merged.put(key, value.trim());
                }
            });
        }
        return merged;
    }

    private Map<String, String> decryptConfig(ChannelApp app) {
        String plain = crypto.decrypt(app.getConfigEnc());
        if (isBlank(plain)) {
            return new LinkedHashMap<>();
        }
        try {
            return json.readValue(plain, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception ex) {
            log.warn("渠道配置解析失败 appId={}: {}", app.getId(), ex.toString());
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Map<String, String> config) {
        try {
            return json.writeValueAsString(config);
        } catch (Exception ex) {
            throw BizException.badRequest("渠道配置序列化失败: " + ex.getMessage());
        }
    }

    private ChannelAppResponse toResponse(ChannelApp app) {
        Map<String, String> config = decryptConfig(app);
        Map<String, String> masked = new LinkedHashMap<>();
        config.forEach((key, value) -> masked.put(key, SECRET_KEYS.contains(key) && !isBlank(value) ? "***" : value));
        Agent agent = app.getAgentId() == null ? null : agentMapper.selectById(app.getAgentId());
        SysUser owner = app.getOwnerUserId() == null ? null : userMapper.selectById(app.getOwnerUserId());
        long peerCount = peerMapper.selectCount(
                Wrappers.<ChannelPeer>lambdaQuery().eq(ChannelPeer::getChannelAppId, app.getId()));
        return new ChannelAppResponse(
                app.getId(),
                app.getChannelType(),
                app.getName(),
                app.getEnabled(),
                app.getAgentId(),
                agent == null ? null : agent.getName(),
                app.getOwnerUserId(),
                owner == null ? null : (owner.getDisplayName() == null ? owner.getUsername() : owner.getDisplayName()),
                app.getStatus(),
                app.getLastError(),
                masked,
                peerCount,
                webhookPath(app, config),
                app.getCreatedAt(),
                app.getUpdatedAt());
    }

    /** 飞书/钉钉 HTTP 回调模式的完整回调地址；Stream 模式返回 null。 */
    private String webhookPath(ChannelApp app, Map<String, String> config) {
        String mode = config.getOrDefault("mode", "stream");
        if ("http".equalsIgnoreCase(mode)) {
            return "DINGTALK".equals(app.getChannelType())
                    ? "/api/channels/dingtalk/" + app.getId() + "/callback"
                    : "/api/channels/feishu/" + app.getId() + "/callback";
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
