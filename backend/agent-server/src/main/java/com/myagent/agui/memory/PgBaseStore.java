package com.myagent.agui.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 官方 {@link BaseStore} 的 PostgreSQL 实现（D23 / Task 11.1）。
 *
 * <p>配合官方 {@code RemoteFilesystem} 承载 Agent 的长期记忆（工作区 {@code memory/**}），
 * 使记忆文件读写实际落到数据库。只实现官方 SPI 的 5 个方法，不重写框架记忆逻辑（门禁 G8）。
 *
 * <p>`version` 列用于官方 {@code putIfVersion} 的乐观锁语义。
 */
public class PgBaseStore implements BaseStore {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final JsonCodec codec = JsonUtils.getJsonCodec();

    public PgBaseStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StoreItem get(List<String> namespace, String key) {
        List<StoreItem> items = jdbc.query(
                "select item_key, payload::text as payload, version from memory_store "
                        + "where namespace_path = ? and item_key = ?",
                (rs, rowNum) -> new StoreItem(
                        rs.getString("item_key"), decode(rs.getString("payload")), rs.getLong("version")),
                namespacePath(namespace), key);
        return items.isEmpty() ? null : items.get(0);
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        jdbc.update(
                """
                insert into memory_store (namespace_path, item_key, payload, version, created_at, updated_at)
                values (?, ?, ?::jsonb, 1, now(), now())
                on conflict (namespace_path, item_key)
                do update set payload = excluded.payload,
                              version = memory_store.version + 1,
                              updated_at = now()
                """,
                namespacePath(namespace), key, codec.toJson(value));
    }

    @Override
    public boolean putIfVersion(List<String> namespace, String key, Map<String, Object> value, long version) {
        int updated = jdbc.update(
                "update memory_store set payload = ?::jsonb, version = version + 1, updated_at = now() "
                        + "where namespace_path = ? and item_key = ? and version = ?",
                codec.toJson(value), namespacePath(namespace), key, version);
        return updated > 0;
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int offset, int limit) {
        int safeOffset = Math.max(offset, 0);
        // limit <= 0 视为「不限量」（官方语义未约定，这里给一个安全上限）
        int safeLimit = limit > 0 ? limit : Integer.MAX_VALUE;
        return jdbc.query(
                "select item_key, payload::text as payload, version from memory_store "
                        + "where namespace_path = ? order by item_key asc offset ? limit ?",
                (rs, rowNum) -> new StoreItem(
                        rs.getString("item_key"), decode(rs.getString("payload")), rs.getLong("version")),
                namespacePath(namespace), safeOffset, safeLimit);
    }

    @Override
    public void delete(List<String> namespace, String key) {
        jdbc.update(
                "delete from memory_store where namespace_path = ? and item_key = ?",
                namespacePath(namespace), key);
    }

    /** 命名空间用 {@code /} 连接后存一列，便于唯一索引与范围查询。 */
    static String namespacePath(List<String> namespace) {
        return namespace == null ? "" : String.join("/", namespace);
    }

    private Map<String, Object> decode(String json) {
        return codec.fromJson(json, MAP_TYPE);
    }
}
