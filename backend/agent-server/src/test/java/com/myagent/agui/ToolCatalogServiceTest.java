package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;

import com.myagent.mcpconfig.entity.McpServer;
import com.myagent.mcpconfig.mapper.McpServerMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工具清单（供技能编辑器 `/` 与 MCP 页展示）。
 *
 * <p>重点覆盖**不需要真连 MCP** 的那部分：命名空间拼接、只看启用中的服务器、没同步过的跳过。
 * 真实工具发现由 MCP 页的「同步工具」与 e2e 覆盖。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ToolCatalogServiceTest {

    @Autowired
    private ToolCatalogService service;

    @Autowired
    private McpServerMapper mcpServerMapper;

    @Test
    void MCP工具带服务器命名空间_与模型实际看到的一致() {
        // 平台给 MCP 工具加了 `<server>__<tool>` 前缀（NamespacedMcpClientWrapper），
        // 技能正文里必须写这个名字才调得到 —— 所以清单里也必须是这个名字
        String serverName = "cat-server-" + System.nanoTime();
        seedServer(serverName, "ENABLED", List.of(
                Map.of("name", "echo", "description", "回声"),
                Map.of("name", "sum", "description", "求和")));

        List<ToolCatalogService.ToolEntry> entries = service.mcpTools();
        assertThat(entries)
                .anySatisfy(e -> {
                    assertThat(e.name()).isEqualTo(serverName + "__echo");
                    assertThat(e.group()).isEqualTo("MCP · " + serverName);
                    assertThat(e.hint()).isEqualTo("回声");
                })
                .anySatisfy(e -> assertThat(e.name()).isEqualTo(serverName + "__sum"));
    }

    @Test
    void 停用的服务器不出现在清单里() {
        String serverName = "cat-disabled-" + System.nanoTime();
        seedServer(serverName, "DISABLED", List.of(Map.of("name", "echo", "description", "x")));

        assertThat(service.mcpTools()).noneSatisfy(e -> assertThat(e.name()).startsWith(serverName));
    }

    @Test
    void 没同步过工具的服务器不报错也不产生条目() {
        String serverName = "cat-empty-" + System.nanoTime();
        seedServer(serverName, "ENABLED", null); // toolCache 为空（还没点「同步工具」）

        assertThat(service.mcpTools()).noneSatisfy(e -> assertThat(e.name()).startsWith(serverName));
    }

    @Test
    void list_把MCP与本地工具合在一起且不抛异常() {
        String serverName = "cat-mix-" + System.nanoTime();
        seedServer(serverName, "ENABLED", List.of(Map.of("name", "echo", "description", "x")));

        // 本地工具来自「构建一次 Agent 读框架 Toolkit」，测试库里可能没有可用 Agent/模型 →
        // 那就返回空；关键是**不能抛异常**（技能编辑器的 `/` 依赖它）
        List<ToolCatalogService.ToolEntry> all = service.list();
        assertThat(all).anySatisfy(e -> assertThat(e.name()).isEqualTo(serverName + "__echo"));
        assertThat(all).allSatisfy(e -> assertThat(e.name()).isNotBlank());
        // 分组只有两类：MCP · xxx 与「本地工具」
        assertThat(all).allSatisfy(e -> assertThat(e.group()).satisfiesAnyOf(
                g -> assertThat(g).startsWith("MCP · "),
                g -> assertThat(g).isEqualTo("本地工具")));
    }

    private void seedServer(String name, String status, List<Map<String, Object>> toolCache) {
        McpServer server = new McpServer();
        server.setName(name);
        server.setTransport("STDIO");
        server.setCommand("cmd");
        server.setStatus(status);
        server.setToolCache(toolCache);
        mcpServerMapper.insert(server);
    }
}
