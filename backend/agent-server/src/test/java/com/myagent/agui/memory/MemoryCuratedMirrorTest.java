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
 * 长期记忆（MEMORY.md）必须能跟着部署走。
 *
 * <p>真实故障：换成 Docker 部署（新卷）后「记忆」页只剩每日台账，"长期记忆"整份消失——
 * 因为它原本只写在工作区磁盘上，而每日台账在数据库里。
 * 现在磁盘与库双向镜像：换机器后从库恢复回磁盘，框架（只读磁盘）下一轮就能读到。
 */
class MemoryCuratedMirrorTest {

    /** 内存版 BaseStore（只实现测试用得到的 get/put/search）。 */
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

    private Path curatedFile() {
        return workspace.resolve("1").resolve("MEMORY.md");
    }

    private void writeDisk(String text) throws IOException {
        Files.createDirectories(curatedFile().getParent());
        Files.writeString(curatedFile(), text, StandardCharsets.UTF_8);
    }

    @Test
    void 磁盘上有_列表能看到并镜像进库() throws IOException {
        writeDisk("- 主人喜欢简洁\n");

        assertThat(service.list(1L))
                .anySatisfy(doc -> {
                    assertThat(doc.name()).isEqualTo("MEMORY.md");
                    assertThat(doc.kind()).isEqualTo(MemoryDocument.KIND_CURATED);
                });

        StoreItem mirror = store.get(MemoryAdminService.namespace(1L), MemoryAdminService.CURATED_KEY);
        assertThat(mirror).isNotNull();
        assertThat(mirror.value().get("content")).isEqualTo("- 主人喜欢简洁\n");
    }

    @Test
    void 磁盘上没有_从库恢复到磁盘_框架才读得到() {
        store.put(
                MemoryAdminService.namespace(1L),
                MemoryAdminService.CURATED_KEY,
                Map.of("content", "- 从库恢复的记忆\n", "encoding", "utf-8", "modified_at", "2026-10-01T00:00:00Z"));

        assertThat(Files.isRegularFile(curatedFile())).as("读之前磁盘上还没有").isFalse();

        List<MemoryDocument> docs = service.list(1L);

        assertThat(docs).anySatisfy(doc -> assertThat(doc.name()).isEqualTo("MEMORY.md"));
        // 关键：必须真的写回磁盘（框架只读磁盘，光在库里有没用）
        assertThat(Files.isRegularFile(curatedFile())).isTrue();
        assertThat(readDisk()).isEqualTo("- 从库恢复的记忆\n");
    }

    @Test
    void 读取MEMORY_md时也会自愈() {
        store.put(
                MemoryAdminService.namespace(1L),
                MemoryAdminService.CURATED_KEY,
                Map.of("content", "- 恢复\n", "encoding", "utf-8"));

        assertThat(service.read(1L, "MEMORY.md").content()).isEqualTo("- 恢复\n");
        assertThat(Files.isRegularFile(curatedFile())).isTrue();
    }

    @Test
    void 人工编辑MEMORY_md时磁盘与库同时更新() {
        service.save(1L, "MEMORY.md", "- 手改后的长期记忆\n");

        assertThat(readDisk()).isEqualTo("- 手改后的长期记忆\n");
        assertThat(store.get(MemoryAdminService.namespace(1L), MemoryAdminService.CURATED_KEY)
                        .value().get("content"))
                .isEqualTo("- 手改后的长期记忆\n");
    }

    @Test
    void 内容没变时不会反复写库() throws IOException {
        writeDisk("- 稳定内容\n");
        service.list(1L); // 第一次：镜像
        StoreItem first = store.get(MemoryAdminService.namespace(1L), MemoryAdminService.CURATED_KEY);
        service.list(1L); // 第二次：内容相同应跳过
        StoreItem second = store.get(MemoryAdminService.namespace(1L), MemoryAdminService.CURATED_KEY);
        assertThat(second.version()).isEqualTo(first.version());
    }

    private String readDisk() {
        try {
            return Files.readString(curatedFile(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
