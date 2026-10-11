package com.myagent.agui.memory;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import java.util.List;

/**
 * 官方 {@link NamespaceFactory} 的平台实现：按调用方身份隔离记忆（D23 / Task 11.1）。
 *
 * <p>命名空间取 {@code ["user", <userId>]}，因此每个用户的长期记忆互不可见；
 * 拿不到 userId 时退化为 {@code ["user", "anonymous"]}，避免把不同人的记忆混在一起。
 */
public class PgNamespaceFactory implements NamespaceFactory {

    static final String ANONYMOUS = "anonymous";

    @Override
    public List<String> getNamespace(RuntimeContext context) {
        String userId = context == null ? null : context.getUserId();
        if (userId == null || userId.isBlank()) {
            return List.of("user", ANONYMOUS);
        }
        return List.of("user", userId);
    }
}
