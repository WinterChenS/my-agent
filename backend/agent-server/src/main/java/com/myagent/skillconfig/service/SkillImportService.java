package com.myagent.skillconfig.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.myagent.agentconfig.cache.AgentCacheInvalidator;
import com.myagent.common.BizException;
import com.myagent.skillconfig.cache.SkillSyncTrigger;
import com.myagent.skillconfig.dto.SkillImportCandidate;
import com.myagent.skillconfig.dto.SkillImportRequest;
import com.myagent.skillconfig.dto.SkillImportResult;
import com.myagent.skillconfig.entity.Skill;
import com.myagent.skillconfig.importer.SkillArchive;
import com.myagent.skillconfig.importer.SkillMarkdownParser;
import com.myagent.skillconfig.importer.SkillRepoFetcher;
import com.myagent.skillconfig.importer.SkillScan;
import com.myagent.skillconfig.importer.SkillSecurityCheck;
import com.myagent.skillconfig.mapper.SkillMapper;
import com.myagent.skillconfig.mapper.SkillResourceMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 从仓库链接导入标准 Skill：下载 → 拆包 → 官方解析 → 安全扫描 → 落库 → 同步运行时仓库。
 *
 * <p>为什么单独一个服务：管理页（{@code POST /api/skills/import}）与聊天里的
 * {@code install_skill} 工具走**同一条**路径，避免两套实现出现「页面能装、聊天装进来是残的」。
 *
 * <p>关键设计：
 * <ul>
 *   <li><b>预览 / 安装两段式</b>：一个仓库常有多个技能（实测 {@code anthropics/skills} 有 20 个），
 *       先返回候选让人挑，选中的内容用 token 缓存复用，避免二次下载；
 *   <li><b>预装默认 DISABLED</b>：外部仓库 = 外部提示词 + 外部脚本，先停用，人工看一眼再启用；
 *   <li><b>再导一次 = 更新</b>：靠 {@code source_url + name} 定位，内容指纹没变就不刷版本号。
 * </ul>
 */
@Service
public class SkillImportService {

    private static final Logger log = LoggerFactory.getLogger(SkillImportService.class);

    /** 预览时正文展示上限（完整内容存在 token 缓存里，安装时才用）。 */
    private static final int PREVIEW_CONTENT_CHARS = 4000;

    /** 平台侧 name 列宽 128；官方仓库 255。取小的那个，避免落库成功、同步失败。 */
    private static final int MAX_NAME_LENGTH = 128;

    /** 预览结果缓存有效期。 */
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(15);

    private final SkillMapper skillMapper;
    private final SkillResourceMapper skillResourceMapper;
    private final SkillSyncTrigger skillSyncTrigger;
    /**
     * Agent 缓存失效器。
     *
     * <p>用 {@link ObjectProvider} 延迟取：{@code AgentCacheInvalidator} 的实现是 {@code AgentFactory}，
     * 而 AgentFactory → McpToolkitFactory → install_skill 工具 → 本服务，构造期会形成环。
     * 安装完成后才需要它，运行时再取不影响语义。
     */
    private final ObjectProvider<AgentCacheInvalidator> agentCacheInvalidator;
    private final SkillMarkdownParser markdownParser;
    private final SkillSecurityCheck securityCheck;
    private final SkillRepoFetcher repoFetcher;

    // 解包限制（可配，见 application.yml 的 myagent.skill-import.*）
    private final long maxArchiveBytes;
    private final int maxEntries;
    private final long maxFileBytes;
    private final long maxTotalBytes;
    private final int maxResourcesPerSkill;

    /** 预览缓存：token → 已解析好的待装技能（含完整正文与资源）。 */
    private final Map<String, CachedPreview> previewCache = new ConcurrentHashMap<>();

    public SkillImportService(
            SkillMapper skillMapper,
            SkillResourceMapper skillResourceMapper,
            SkillSyncTrigger skillSyncTrigger,
            ObjectProvider<AgentCacheInvalidator> agentCacheInvalidator,
            SkillMarkdownParser markdownParser,
            SkillSecurityCheck securityCheck,
            SkillRepoFetcher repoFetcher,
            @Value("${myagent.skill-import.max-archive-mb:20}") int maxArchiveMb,
            @Value("${myagent.skill-import.max-file-kb:2048}") int maxFileKb,
            @Value("${myagent.skill-import.max-total-mb:64}") int maxTotalMb,
            @Value("${myagent.skill-import.max-entries:4000}") int maxEntries,
            @Value("${myagent.skill-import.max-resources-per-skill:300}") int maxResourcesPerSkill) {
        this.skillMapper = skillMapper;
        this.skillResourceMapper = skillResourceMapper;
        this.skillSyncTrigger = skillSyncTrigger;
        this.agentCacheInvalidator = agentCacheInvalidator;
        this.markdownParser = markdownParser;
        this.securityCheck = securityCheck;
        this.repoFetcher = repoFetcher;
        this.maxArchiveBytes = Math.max(1, maxArchiveMb) * 1024L * 1024L;
        this.maxFileBytes = Math.max(1, maxFileKb) * 1024L;
        this.maxTotalBytes = Math.max(1, maxTotalMb) * 1024L * 1024L;
        this.maxEntries = Math.max(1, maxEntries);
        this.maxResourcesPerSkill = Math.max(1, maxResourcesPerSkill);
    }

    /** 解包限制（配置驱动）。 */
    private SkillArchive.Limits limits() {
        return new SkillArchive.Limits(
                maxArchiveBytes, maxEntries, maxFileBytes, maxTotalBytes, maxResourcesPerSkill);
    }

    // ------------------------------------------------------------------ 预览 / 安装

    /**
     * 预览：仓库里有哪些技能、各自安全结论、是否已存在同名 Skill。**不落库**。
     */
    public SkillImportResult preview(SkillImportRequest request) {
        Loaded loaded = load(request);
        List<SkillImportCandidate> candidates = new ArrayList<>();
        for (Prepared prepared : loaded.skills()) {
            Skill existing = findBySourceOrName(loaded.url(), prepared.name());
            candidates.add(new SkillImportCandidate(
                    prepared.root(),
                    prepared.pack(),
                    prepared.name(),
                    prepared.description(),
                    truncate(prepared.content()),
                    prepared.resources().size(),
                    prepared.resourceBytes(),
                    List.copyOf(prepared.resources().keySet()),
                    prepared.scan().verdict(),
                    prepared.scan().findings(),
                    existing == null ? null : existing.getId(),
                    existing == null ? null : existing.getVersion()));
        }
        cache(loaded);
        return new SkillImportResult(
                loaded.downloadUrl(), loaded.ref(), true, candidates, List.of(), List.of(), loaded.notes());
    }

    /**
     * 安装：按 {@code names} 装选中的技能（空 = 全部）。整个批次在一个事务里，不会出现装了一半。
     */
    @Transactional
    public SkillImportResult install(SkillImportRequest request) {
        Loaded cached = takeFromCache(request);
        Loaded loaded = cached != null ? cached : load(request);
        List<Prepared> selected = select(loaded, request.names());
        if (selected.isEmpty()) {
            throw BizException.badRequest("没有选中任何技能；可选的技能：" + loaded.skills().stream()
                    .map(Prepared::root).toList());
        }
        List<SkillImportResult.Installed> installed = new ArrayList<>();
        List<SkillImportResult.Skipped> skipped = new ArrayList<>();
        for (Prepared prepared : selected) {
            Skill existing = findBySourceOrName(loaded.url(), prepared.name());
            if (existing != null && !request.overwriteExisting()) {
                skipped.add(new SkillImportResult.Skipped(prepared.name(), prepared.root(), "同名 Skill 已存在，未覆盖"));
                continue;
            }
            if (!securityCheck.allows(prepared.scan()) && !request.forceInstall()) {
                skipped.add(new SkillImportResult.Skipped(
                        prepared.name(),
                        prepared.root(),
                        "安全扫描为 DANGEROUS，拒绝安装（确认无风险可用 force 强制安装）："
                                + String.join("；", prepared.scan().findings())));
                continue;
            }
            installed.add(save(prepared, existing, loaded.url(), loaded.ref(), request.enableInstalled()));
        }
        // 一次同步 + 一次缓存失效（而不是每个技能各来一次）
        if (!installed.isEmpty()) {
            skillSyncTrigger.requestSync();
            AgentCacheInvalidator invalidator = agentCacheInvalidator.getIfAvailable();
            if (invalidator != null) {
                invalidator.invalidateAll();
            }
        }
        log.info(
                "Skill 导入完成：来源={} ref={} 安装={} 跳过={}",
                loaded.url(),
                loaded.ref(),
                installed.size(),
                skipped.size());
        return new SkillImportResult(
                loaded.downloadUrl(),
                loaded.ref(),
                false,
                loaded.skills().stream()
                        .map(p -> new SkillImportCandidate(
                                p.root(),
                                p.pack(),
                                p.name(),
                                p.description(),
                                truncate(p.content()),
                                p.resources().size(),
                                p.resourceBytes(),
                                List.copyOf(p.resources().keySet()),
                                p.scan().verdict(),
                                p.scan().findings(),
                                null,
                                null))
                        .toList(),
                installed,
                skipped,
                loaded.notes());
    }

    // ------------------------------------------------------------------ 落库

    private SkillImportResult.Installed save(
            Prepared prepared, Skill existing, String url, String ref, boolean enable) {
        String hash = hashOf(prepared);
        String status = enable ? "ENABLED" : "DISABLED";
        Skill target;
        String action;
        if (existing == null) {
            target = new Skill();
            target.setName(prepared.name());
            target.setDescription(prepared.description());
            target.setContent(prepared.content());
            target.setVersion(1);
            target.setStatus(status);
            target.setSourceUrl(url);
            target.setSourceRef(ref);
            target.setContentHash(hash);
            skillMapper.insert(target);
            action = "created";
        } else {
            target = existing;
            boolean contentChanged = !Objects.equals(hash, existing.getContentHash())
                    || !Objects.equals(existing.getContent(), prepared.content());
            // 只有内容真的变了才自增版本；纯改来源/状态不刷版本（与 SkillConfigService 的口径一致）
            if (contentChanged) {
                target.setVersion((existing.getVersion() == null ? 1 : existing.getVersion()) + 1);
            }
            target.setDescription(prepared.description());
            target.setContent(prepared.content());
            target.setStatus(enable ? "ENABLED" : (existing.getStatus() == null ? "DISABLED" : existing.getStatus()));
            target.setSourceUrl(url);
            target.setSourceRef(ref);
            target.setContentHash(hash);
            skillMapper.updateById(target);
            action = contentChanged ? "updated" : "unchanged";
        }

        // 资源整体替换：官方仓库按 name 覆盖，这里也必须「以仓库为准」，不能留下上一版已删除的文件
        skillResourceMapper.deleteBySkillId(target.getId());
        for (Map.Entry<String, String> entry : prepared.resources().entrySet()) {
            skillResourceMapper.insertOne(target.getId(), entry.getKey(), entry.getValue());
        }
        return new SkillImportResult.Installed(
                target.getId(),
                target.getName(),
                prepared.root(),
                target.getVersion(),
                action,
                prepared.resources().size());
    }

    // ------------------------------------------------------------------ 下载 + 解析

    /** 可复用的「已装好」中间结果。 */
    private record Loaded(
            String url, String ref, String subPath, String downloadUrl, List<Prepared> skills, List<String> notes) {}

    /** 一个解析完成的技能。 */
    private record Prepared(
            String root,
            String pack,
            String name,
            String description,
            String content,
            Map<String, String> resources,
            SkillScan scan) {

        long resourceBytes() {
            long total = 0;
            for (String value : resources.values()) {
                total += value.startsWith("base64:")
                        ? (value.length() - 7) * 3L / 4
                        : value.getBytes(StandardCharsets.UTF_8).length;
            }
            return total;
        }
    }

    /** 缓存项：过期时间 + 内容。 */
    private record CachedPreview(Instant expiresAt, Loaded loaded) {}

    private Loaded load(SkillImportRequest request) {
        SkillRepoFetcher.Resolved resolved = SkillRepoFetcher.resolve(request.url(), request.ref());
        String host = SkillRepoFetcher.hostOf(resolved.downloadUrl()).orElse("");
        if (SkillRepoFetcher.isBlockedHost(host)) {
            throw BizException.badRequest("该地址不允许访问：" + host);
        }
        SkillRepoFetcher.Fetched fetched = repoFetcher.fetch(request.url(), request.ref());
        List<SkillArchive.Bundle> bundles =
                SkillArchive.read(fetched.bytes(), resolved.subPath(), limits());
        List<Prepared> prepared = new ArrayList<>();
        for (SkillArchive.Bundle bundle : bundles) {
            prepared.add(prepare(bundle));
        }
        List<String> notes = new ArrayList<>();
        List<Prepared> deduped = dedupeByName(prepared, notes);
        return new Loaded(
                request.url().trim(), resolved.ref(), resolved.subPath(), fetched.downloadUrl(), deduped, notes);
    }

    private Prepared prepare(SkillArchive.Bundle bundle) {
        SkillMarkdownParser.Parsed parsed =
                markdownParser.parse(bundle.markdown(), bundle.resources(), "myagent");
        validateName(parsed.name());
        if (!StringUtils.hasText(parsed.content())) {
            throw BizException.badRequest("技能 " + parsed.name() + " 的 SKILL.md 只有 frontmatter、没有正文");
        }
        SkillScan scan = securityCheck.scan(parsed.name(), parsed.content(), bundle.resources());
        return new Prepared(
                bundle.root(),
                bundle.pack(),
                parsed.name(),
                parsed.description(),
                parsed.content(),
                bundle.resources(),
                scan);
    }

    /**
     * 同一个仓库里常有**多份同名技能**（实测 nextlevelbuilder/ui-ux-pro-max-skill：
     * {@code .claude/skills/x} 与 {@code cli/assets/skills/x} 各一份）。
     * 平台按 name 唯一，全装会互相覆盖，所以只保留一份：
     * 优先「在技能包清单里声明过的」→ 其次路径层级最浅的 → 再按字典序。
     */
    private List<Prepared> dedupeByName(List<Prepared> prepared, List<String> notes) {
        Map<String, Prepared> byName = new LinkedHashMap<>();
        for (Prepared candidate : prepared) {
            Prepared existing = byName.get(candidate.name());
            if (existing == null) {
                byName.put(candidate.name(), candidate);
                continue;
            }
            Prepared better = prefer(candidate, existing);
            Prepared dropped = better == candidate ? existing : candidate;
            byName.put(candidate.name(), better);
            notes.add("仓库里有多份同名技能「" + candidate.name() + "」：" + dropped.root()
                    + " 已忽略，使用 " + better.root());
        }
        return new ArrayList<>(byName.values());
    }

    private static Prepared prefer(Prepared a, Prepared b) {
        boolean aPacked = a.pack() != null;
        boolean bPacked = b.pack() != null;
        if (aPacked != bPacked) {
            return aPacked ? a : b;
        }
        int aDepth = a.root().split("/").length;
        int bDepth = b.root().split("/").length;
        if (aDepth != bDepth) {
            return aDepth < bDepth ? a : b;
        }
        return a.root().compareTo(b.root()) <= 0 ? a : b;
    }

    private void validateName(String name) {
        if (!StringUtils.hasText(name)) {
            throw BizException.badRequest("SKILL.md 的 frontmatter 缺少 name");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw BizException.badRequest("技能名超过 " + MAX_NAME_LENGTH + " 字符：" + name);
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw BizException.badRequest("技能名不能包含路径分隔符或 `..`：" + name);
        }
    }

    // ------------------------------------------------------------------ 选择 / 定位 / 缓存

    private List<Prepared> select(Loaded loaded, List<String> names) {
        if (names == null || names.isEmpty()) {
            return loaded.skills();
        }
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        for (String raw : names) {
            if (StringUtils.hasText(raw)) {
                wanted.add(raw.trim());
            }
        }
        // 两段匹配：先按「技能目录 / 技能名 / 目录最后一段」精确挑，
        // 一个都没匹配上才把 names 当**技能包名**（一次装一整个包）。
        // 为什么要分两段：有的仓库里技能名恰好等于包名（实测 ui-ux-pro-max 就是），
        // 混在一起会导致「只想更新一个技能，结果整包都重装了」。
        List<Prepared> exact = new ArrayList<>();
        for (Prepared prepared : loaded.skills()) {
            if (wanted.contains(prepared.root())
                    || wanted.contains(prepared.name())
                    || wanted.contains(lastSegment(prepared.root()))) {
                exact.add(prepared);
            }
        }
        if (!exact.isEmpty()) {
            return exact;
        }
        List<Prepared> byPack = new ArrayList<>();
        for (Prepared prepared : loaded.skills()) {
            if (prepared.pack() != null && wanted.contains(prepared.pack())) {
                byPack.add(prepared);
            }
        }
        return byPack;
    }

    private static String lastSegment(String root) {
        int slash = root.lastIndexOf('/');
        return slash < 0 ? root : root.substring(slash + 1);
    }

    /** 同一个来源的**同名**技能才算「再导一次」；手工创建的同名技能也会被按名字更新（用户明确给了覆盖）。 */
    private Skill findBySourceOrName(String url, String name) {
        List<Skill> byName = skillMapper.selectList(Wrappers.<Skill>lambdaQuery()
                .eq(Skill::getName, name)
                .orderByAsc(Skill::getId));
        if (byName.isEmpty()) {
            return null;
        }
        for (Skill skill : byName) {
            if (url != null && url.equals(skill.getSourceUrl())) {
                return skill;
            }
        }
        return byName.get(0);
    }

    private void cache(Loaded loaded) {
        previewCache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
        previewCache.put(UUID.randomUUID().toString(), new CachedPreview(Instant.now().plus(PREVIEW_TTL), loaded));
        // 只保留最近 20 份预览，防止内存随使用无限增长
        if (previewCache.size() > 20) {
            previewCache.keySet().stream().skip(previewCache.size() - 20).forEach(previewCache::remove);
        }
    }

    /**
     * 安装时优先复用预览过的内容：链接 + ref 与预览时一致才复用，否则重新下载
     * （用户可能在预览后改了分支，重下才安全）。
     */
    private Loaded takeFromCache(SkillImportRequest request) {
        if (previewCache.isEmpty() || !StringUtils.hasText(request.url())) {
            return null;
        }
        String url = request.url().trim();
        SkillRepoFetcher.Resolved resolved;
        try {
            resolved = SkillRepoFetcher.resolve(request.url(), request.ref());
        } catch (RuntimeException ex) {
            return null;
        }
        for (CachedPreview cached : previewCache.values()) {
            if (cached.expiresAt().isBefore(Instant.now())) {
                continue;
            }
            // 链接、ref、子目录三者都一致才复用：用户在预览后改了其中任何一个都必须重新下载
            if (cached.loaded().url().equals(url)
                    && Objects.equals(cached.loaded().ref(), resolved.ref())
                    && Objects.equals(cached.loaded().subPath(), resolved.subPath())) {
                return cached.loaded();
            }
        }
        return null;
    }

    private static String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= PREVIEW_CONTENT_CHARS
                ? content
                : content.substring(0, PREVIEW_CONTENT_CHARS) + "\n\n…（预览截断，安装后保存完整内容）";
    }

    private static String hashOf(Prepared prepared) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(prepared.name().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(prepared.description().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(prepared.content().getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, String> entry : new LinkedHashMap<>(prepared.resources()).entrySet()) {
                digest.update((byte) '\n');
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是所有 JVM 必须实现的算法，走不到这里
            return null;
        }
    }
}
