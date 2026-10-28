package com.myagent.agui.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.CompositeFilesystem;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证官方 {@code filesystemRoute} 能**精确匹配一个文件**（`MEMORY.md`）。
 *
 * <p>为什么要验：框架把长期记忆写在 {@code <workspace>/<userId>/MEMORY.md}，属于工作区根目录，
 * 不在 {@code memory/} 这个路由前缀下，所以它一直落在磁盘上（换机器就丢）。
 * 如果官方的复合文件系统支持「按文件路径」路由，我们就能把 MEMORY.md 直接存进库，
 * 而不用自己实现文件系统（门禁 G8：不重写框架能力）。
 */
class MemoryFilesystemRouteTest {

    private static final class RecordingLocal implements io.agentscope.harness.agent.filesystem.AbstractFilesystem {
        final List<String> calls = new ArrayList<>();

        @Override
        public LsResult ls(RuntimeContext context, String path) {
            calls.add("ls:" + path);
            return null;
        }

        @Override
        public io.agentscope.harness.agent.filesystem.model.ReadResult read(
                RuntimeContext context, String path, int offset, int limit) {
            calls.add("read:" + path);
            return null;
        }

        @Override
        public io.agentscope.harness.agent.filesystem.model.WriteResult write(
                RuntimeContext context, String path, String content) {
            calls.add("write:" + path);
            return io.agentscope.harness.agent.filesystem.model.WriteResult.ok(path);
        }

        @Override
        public io.agentscope.harness.agent.filesystem.model.EditResult edit(
                RuntimeContext context, String path, String oldText, String newText, boolean replaceAll) {
            calls.add("edit:" + path);
            return null;
        }

        @Override
        public io.agentscope.harness.agent.filesystem.model.GrepResult grep(
                RuntimeContext context, String path, String pattern, String glob) {
            calls.add("grep:" + path);
            return null;
        }

        @Override
        public io.agentscope.harness.agent.filesystem.model.GlobResult glob(
                RuntimeContext context, String path, String pattern) {
            calls.add("glob:" + path);
            return null;
        }

        @Override
        public List<io.agentscope.harness.agent.filesystem.model.FileUploadResponse> uploadFiles(
                RuntimeContext context, List<Map.Entry<String, byte[]>> files) {
            return List.of();
        }

        @Override
        public List<io.agentscope.harness.agent.filesystem.model.FileDownloadResponse> downloadFiles(
                RuntimeContext context, List<String> paths) {
            return List.of();
        }

        @Override
        public io.agentscope.harness.agent.filesystem.model.WriteResult delete(
                RuntimeContext context, String path) {
            calls.add("delete:" + path);
            return io.agentscope.harness.agent.filesystem.model.WriteResult.ok(path);
        }

        @Override
        public io.agentscope.harness.agent.filesystem.model.WriteResult move(
                RuntimeContext context, String path, String dest) {
            calls.add("move:" + path);
            return null;
        }

        @Override
        public boolean exists(RuntimeContext context, String path) {
            calls.add("exists:" + path);
            return false;
        }
    }

    private static final class InMemoryStore implements BaseStore {
        final Map<String, Map<String, Object>> data = new LinkedHashMap<>();

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

    @Test
    void 精确文件路由_MEMORY_md_会落到库里而不是磁盘() {
        InMemoryStore store = new InMemoryStore();
        RecordingLocal local = new RecordingLocal();
        RemoteFilesystem remote = new RemoteFilesystem(store, new PgNamespaceFactory());
        CompositeFilesystem fs = new CompositeFilesystem(local, Map.of("MEMORY.md", remote));

        RuntimeContext ctx = RuntimeContext.builder().userId("1").build();

        fs.write(ctx, "MEMORY.md", "- 长期记忆进库\n");
        fs.write(ctx, "report.md", "- 普通产物留在磁盘\n");

        // MEMORY.md 进了库，且 key 与我们管理页/镜像用的是同一个（/MEMORY.md）
        assertThat(store.data)
                .containsKey("user/1|/MEMORY.md")
                .doesNotContainKey("user/1|MEMORY.md");
        assertThat(store.data.get("user/1|/MEMORY.md").get("content")).isEqualTo("- 长期记忆进库\n");

        // 其它文件仍然走磁盘
        assertThat(local.calls).contains("write:report.md");
        assertThat(local.calls).noneMatch(c -> c.contains("MEMORY.md"));
    }
}
