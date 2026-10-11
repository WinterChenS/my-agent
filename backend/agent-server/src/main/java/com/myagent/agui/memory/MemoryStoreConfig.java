package com.myagent.agui.memory;

import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 记忆与会话状态入库的装配（D23 / Task 11.1）。
 *
 * <p>只把两个官方 SPI 的实现注册成 Bean，由 {@code AgentFactory} 注入到
 * {@code HarnessAgent.Builder}（`stateStore(...)` 与 `filesystemRoute(...)`）。
 *
 * <p>本类位于 {@code agui/} 下，符合门禁 G2（AgentScope API 仅出现在 agui/、audit/、hitl/）。
 */
@Configuration
public class MemoryStoreConfig {

    @Bean
    public PgAgentStateStore pgAgentStateStore(DataSource dataSource) {
        return new PgAgentStateStore(new JdbcTemplate(dataSource));
    }

    @Bean
    public PgBaseStore pgBaseStore(DataSource dataSource) {
        return new PgBaseStore(new JdbcTemplate(dataSource));
    }

    @Bean
    public PgNamespaceFactory pgNamespaceFactory() {
        return new PgNamespaceFactory();
    }

    /** 官方 RemoteFilesystem：工作区 {@code memory/**} 的读写改走数据库。 */
    @Bean
    public RemoteFilesystem memoryRemoteFilesystem(PgBaseStore store, PgNamespaceFactory namespaceFactory) {
        return new RemoteFilesystem(store, namespaceFactory);
    }
}
