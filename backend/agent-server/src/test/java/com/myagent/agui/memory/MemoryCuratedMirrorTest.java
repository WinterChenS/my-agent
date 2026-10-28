package com.myagent.agui.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 长期记忆（MEMORY.md）存**数据库**，不落磁盘。
 *
 * <p>背景：它原本只写在工作区磁盘上，换成 Docker 部署（新卷）后整份消失（用户实测）。
 * 现在框架通过官方 {@code filesystemRoute("MEMORY.md", 远程库)} 直接读写数据库（见
 * {@link MemoryFilesystemRouteTest}），管理页也一律读写数据库；磁盘只在**升级迁移**时被读取一次。
 */
class MemoryCuratedMirrorTest {

    private static final class InMemoryStore implements BaseStore {
        private final Map<String, Map<String, Object>> data = new LinkedHashMap<>();

        @Override
        public StoreItem get(List<String> namespace, String key) {
            Map<String, Object> value = data.get(String.join("/", namespace) + "|" + key);
            return value == null ? null : new StoreItem(key, value, 1L);
        }

        @Override
        public void put(List<String> namespace, String key, Map<String, Object> value) {
            data.put(String.join("/", namespace) + "|" + key, new LinkedHashMap<>(value));
        }

        @Override
        public boolean putIfVersion(List<String> namespace, String key, Map<String, Object> value, long version) {
            put(namespace, key, value);
            return true;
        }

        @Override
        public List<StoreItem> search(List<String> namespace, int offset, int limit) {
            String prefix = String.join("/", namespace) + "|";
            List<StoreItem> items = new ArrayList<>();
            data.forEach((k, v) -> {
                if (k.startsWith(prefix)) {
                    items.add(new StoreItem(k.substring(prefix.length()), v, 1L));
                }
            });
            return items;
        }

        @Override
        public void delete(List<String> namespace, String key) {
            data.remove(String.join("/", namespace) + "|" + key);
        }
    }

    @TempDir
    Path workspace;

    private InMemoryStore store;
    private MemoryAdminService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        service = new MemoryAdminService(store, workspace);
    }

    private Path legacyDiskFile() {
        return workspace.resolve("1").resolve("MEMORY.md");
    }

    private void writeLegacyDisk(String text) throws IOException {
        Files.createDirectories(legacyDiskFile().getParent());
        Files.writeString(legacyDiskFile(), text, StandardCharsets.UTF_8);
    }

    private void putStore(String content) {
        store.put(
                MemoryAdminService.namespace(1L),
                MemoryAdminService.CURATED_KEY,
                Map.of("content", content, "encoding", "utf-8", "modified_at", "2026-10-01T00:00:00Z"));
    }

    @Test
    void 长期记忆从库里读_磁盘上没有文件也能看到() {
        putStore("- 库里存的长期记忆\n");

        List<MemoryDocument> docs = service.list(1L);

        assertThat(docs).anySatisfy(doc -> {
            assertThat(doc.name()).isEqualTo("MEMORY.md");
            assertThat(doc.kind()).isEqualTo(MemoryDocument.KIND_CURATED);
            assertThat(doc.content()).isEqualTo("- 库里存的长期记忆\n");
        });
        assertThat(Files.exists(legacyDiskFile())).as("不应该再往磁盘写").isFalse();
    }

    @Test
    void 人工编辑写进库_不写磁盘() {
        service.save(1L, "MEMORY.md", "- 手改后的长期记忆\n");

        assertThat(store.get(MemoryAdminService.namespace(1L), MemoryAdminService.CURATED_KEY)
                        .value().get("content"))
                .isEqualTo("- 手改后的长期记忆\n");
        assertThat(Files.exists(legacyDiskFile())).isFalse();
    }

    @Test
    void 历史遗留的磁盘副本_第一次打开就迁进库并删掉磁盘文件() throws IOException {
        writeLegacyDisk("- 老数据在磁盘上\n");

        assertThat(service.read(1L, "MEMORY.md").content()).isEqualTo("- 老数据在磁盘上\n");

        // 迁进库
        assertThat(store.get(MemoryAdminService.namespace(1L), MemoryAdminService.CURATED_KEY)
                        .value().get("content"))
                .isEqualTo("- 老数据在磁盘上\n");
        // 磁盘副本删除，避免两份数据谁为准
        assertThat(Files.exists(legacyDiskFile())).isFalse();
    }

    @Test
    void 库和磁盘都有时_以库为准() throws IOException {
        putStore("- 库里的版本\n");
        writeLegacyDisk("- 磁盘上的旧版本\n");

        assertThat(service.read(1L, "MEMORY.md").content()).isEqualTo("- 库里的版本\n");
    }
}
