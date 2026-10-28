package com.myagent.agui.memory;

import com.myagent.common.BizException;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 长期记忆的**只读+可编辑**视图（「记忆可视化」页面用）。
 *
 * <p>记忆实际有两处落地（框架自己决定的布局，平台**不改变**它）：
 * <ul>
 *   <li>`MEMORY.md`（精华，consolidation 合并后的长期记忆）→ 工作区磁盘
 *       `<workspaceRoot>/<userId>/MEMORY.md`；</li>
 *   <li>`memory/YYYY-MM-DD.md`（每日台账）→ 经官方 `RemoteFilesystem` 路由进库，
 *       {@code memory_store} 表里 `namespace_path=user/<userId>`、`item_key=/YYYY-MM-DD.md`。</li>
 * </ul>
 *
 * <h3>MEMORY.md 现在也存数据库（不再依赖磁盘）</h3>
 * 它原本只写在工作区磁盘上，换机器/换卷就整份消失（用户实测："长期记忆没了"）。
 * 现在 {@code AgentFactory} 用官方 {@code filesystemRoute("MEMORY.md", 远程库文件系统)}
 * 把它**精确路由进库**（{@code memory_store} 里 {@code item_key=/MEMORY.md}，与台账同命名空间），
 * 框架的读写都直接落库，容器磁盘上不再有这份文件。
 *
 * <p>历史兼容：早期版本把 MEMORY.md 写在磁盘上，本类/启动迁移会把它**导入库并删除磁盘副本**
 * （只做一次，之后以库为准）。
 *
 * <p>本类只做“列出 / 读出 / 覆盖写 / 删台账”，不改框架的记忆生成与合并策略；
 * 写台账时沿用框架自己的 payload 形状（`content`/`encoding`/`created_at`/`modified_at`），
 * 这样框架后续读取、consolidation 都不会因为格式不同而失效。
 *
 * <p>为什么放在 `agui/memory` 包：这里要用官方 SPI 的 `BaseStore`/`StoreItem`（门禁 G2 规定
 * `io.agentscope` 只允许出现在 agui/ 等包内）。
 */
public class MemoryAdminService {

    /** 精华记忆在库里的 key（与台账同命名空间，便于一起备份/迁移）。 */
    static final String CURATED_KEY = "/MEMORY.md";

    /** 只允许这两类名字，挡住路径穿越（`../x`、绝对路径等）。 */
    private static final Pattern DAILY_NAME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}\\.md");

    private final io.agentscope.harness.agent.filesystem.remote.store.BaseStore store;
    private final Path workspaceRoot;

    public MemoryAdminService(
            io.agentscope.harness.agent.filesystem.remote.store.BaseStore store, Path workspaceRoot) {
        this.store = store;
        this.workspaceRoot = workspaceRoot;
    }

    /** 列出该用户的全部记忆文档（精华在前，台账按日期倒序）。 */
    public List<MemoryDocument> list(Long userId) {
        List<MemoryDocument> out = new ArrayList<>();
        curatedDocOrNull(userId).ifPresent(out::add);
        for (MemoryDocument doc : dailyDocs(userId)) {
            out.add(doc.withoutContent());
        }
        return out;
    }

    /** 读一份文档的正文。 */
    public MemoryDocument read(Long userId, String name) {
        if (MemoryDocument.CURATED_NAME.equals(name)) {
            return curatedDocOrNull(userId).orElseThrow(() -> BizException.notFound("还没有 MEMORY.md"));
        }
        String key = dailyKey(name);
        StoreItem item = store.get(namespace(userId), key);
        if (item == null) {
            throw BizException.notFound("没有这份记忆：" + name);
        }
        return dailyDoc(item);
    }

    /**
     * 覆盖写一份文档的正文。
     *
     * <p>`MEMORY.md` 用「临时文件 + 原子移动」写，避免框架同时读时读到半个文件；
     * 台账写回库（沿用框架的 payload 形状）。
     */
    public MemoryDocument save(Long userId, String name, String content) {
        if (content == null) {
            throw BizException.badRequest("content 不能为空");
        }
        if (MemoryDocument.CURATED_NAME.equals(name)) {
            // 只写库：AgentFactory 已把 MEMORY.md 路由到远程库文件系统，框架读写都走这里
            store.put(namespace(userId), CURATED_KEY, payload(content, Instant.now().toString()));
            return new MemoryDocument(
                    MemoryDocument.CURATED_NAME,
                    MemoryDocument.KIND_CURATED,
                    sizeOf(content),
                    OffsetDateTime.now(),
                    content);
        }
        String key = dailyKey(name);
        OffsetDateTime now = OffsetDateTime.now();
        StoreItem existing = store.get(namespace(userId), key);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("content", content);
        payload.put("encoding", "utf-8");
        payload.put("created_at", existing == null ? now.toString() : payloadValue(existing, "created_at", now.toString()));
        payload.put("modified_at", now.toString());
        store.put(namespace(userId), key, payload);
        return new MemoryDocument(name, MemoryDocument.KIND_DAILY, sizeOf(content), now, content);
    }

    /** 从库里取长期记忆；库里没有但磁盘上有旧文件 → 导入库并删掉磁盘副本（历史兼容，只做一次）。 */
    private Optional<MemoryDocument> curatedDocOrNull(Long userId) {
        StoreItem item = store.get(namespace(userId), CURATED_KEY);
        if (item != null) {
            return Optional.of(curatedDoc(item));
        }
        return Optional.ofNullable(importLegacyCurated(userId));
    }

    /**
     * 历史迁移：早期版本把 MEMORY.md 写在磁盘上。
     *
     * <p>导入成功后**删除磁盘副本**，避免"两份数据、谁为准"的问题（也满足"长期记忆不要放磁盘"）。
     */
    MemoryDocument importLegacyCurated(Long userId) {
        Optional<Path> path = curatedPath(userId);
        if (path.isEmpty() || !Files.isRegularFile(path.get())) {
            return null;
        }
        try {
            Path file = path.get();
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String modifiedAt = Files.getLastModifiedTime(file).toInstant().toString();
            store.put(namespace(userId), CURATED_KEY, payload(content, modifiedAt));
            Files.deleteIfExists(file);
            return curatedDoc(store.get(namespace(userId), CURATED_KEY));
        } catch (Exception ex) {
            return null;
        }
    }

    /** 库里的长期记忆文档。 */
    private MemoryDocument curatedDoc(StoreItem item) {
        String content = payloadValue(item, "content", "");
        return new MemoryDocument(
                MemoryDocument.CURATED_NAME,
                MemoryDocument.KIND_CURATED,
                sizeOf(content),
                modifiedAt(item),
                content);
    }

    /** 与框架一致的 payload 形状（台账也是这样存的）。 */
    private Map<String, Object> payload(String content, String modifiedAt) {
        String now = Instant.now().toString();
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("content", content);
        value.put("encoding", "utf-8");
        value.put("created_at", now);
        value.put("modified_at", modifiedAt == null ? now : modifiedAt);
        return value;
    }

    /** 删除一份**台账**（`MEMORY.md` 是精华记忆，不允许删空）。 */
    public void delete(Long userId, String name) {
        if (MemoryDocument.CURATED_NAME.equals(name)) {
            throw BizException.badRequest("MEMORY.md 不能删除（可以清空正文后保存）");
        }
        store.delete(namespace(userId), dailyKey(name));
    }

    // ===================== 内部 =====================

    /** 该用户的命名空间（与 {@link PgNamespaceFactory} 保持一致）。 */
    static List<String> namespace(Long userId) {
        return List.of("user", String.valueOf(userId));
    }

    /**
     * 校验并转换成存储 key（`/YYYY-MM-DD.md`）。
     *
     * <p>光用正则不够：`2026-13-99.md` 也符合 `\d{4}-\d{2}-\d{2}`，但那种 key 框架永远不会去读
     * （像 2026-13-99 这种不存在日期），存进去只会变成垃圾。所以再用 `LocalDate.parse` 校一遍。
     */
    static String dailyKey(String name) {
        String raw = name == null ? "" : name.trim();
        if (DAILY_NAME.matcher(raw).matches()) {
            try {
                java.time.LocalDate.parse(raw.substring(0, raw.length() - 3));
                return "/" + raw;
            } catch (java.time.format.DateTimeParseException ignored) {
                // 日期不合法：落到下面统一报错
            }
        }
        throw BizException.badRequest("文件名只能形如 2026-10-20.md（且必须是真实日期）：" + name);
    }

    private Optional<Path> curatedPath(Long userId) {
        if (userId == null || workspaceRoot == null) {
            return Optional.empty();
        }
        return Optional.of(workspaceRoot.resolve(String.valueOf(userId)).resolve(MemoryDocument.CURATED_NAME));
    }

    /** 库里的每日台账（跳过非 `/<date>.md` 形状的 key）。 */
    private List<MemoryDocument> dailyDocs(Long userId) {
        List<MemoryDocument> docs = new ArrayList<>();
        for (StoreItem item : store.search(namespace(userId), 0, 0)) {
            String key = item.key() == null ? "" : item.key();
            String name = key.startsWith("/") ? key.substring(1) : key;
            if (!DAILY_NAME.matcher(name).matches()) {
                continue;
            }
            docs.add(dailyDoc(item));
        }
        // 日期倒序：最新的台账排前面
        docs.sort(Comparator.comparing(MemoryDocument::name).reversed());
        return docs;
    }

    private MemoryDocument dailyDoc(StoreItem item) {
        String key = item.key() == null ? "" : item.key();
        String name = key.startsWith("/") ? key.substring(1) : key;
        String content = String.valueOf(payloadMap(item).getOrDefault("content", ""));
        return new MemoryDocument(
                name, MemoryDocument.KIND_DAILY, sizeOf(content), modifiedAt(item), content);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadMap(StoreItem item) {
        return item.value() == null ? Map.of() : item.value();
    }

    private String payloadValue(StoreItem item, String field, String fallback) {
        Object value = payloadMap(item).get(field);
        return value == null ? fallback : String.valueOf(value);
    }

    private OffsetDateTime modifiedAt(StoreItem item) {
        String raw = payloadValue(item, "modified_at", null);
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (RuntimeException ex) {
            try {
                return OffsetDateTime.ofInstant(Instant.parse(raw), ZoneId.systemDefault());
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private static long sizeOf(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

}
