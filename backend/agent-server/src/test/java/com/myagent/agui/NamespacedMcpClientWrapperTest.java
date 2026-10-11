package com.myagent.agui;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * {@link NamespacedMcpClientWrapper}：MCP 工具名加/去 Server 命名空间前缀（A16 的修复）。
 *
 * <p>官方注册的是裸工具名（实测 {@code echo}），而平台冻结契约要求 {@code server__tool}。
 */
class NamespacedMcpClientWrapperTest {

    /** 假客户端：记录被调用的工具名，返回一个固定的 echo 工具。 */
    private static class FakeWrapper extends McpClientWrapper {
        final List<String> called = new ArrayList<>();
        final AtomicReference<Map<String, Object>> lastArgs = new AtomicReference<>();

        FakeWrapper(String name) {
            super(name);
        }

        @Override
        public Mono<Void> initialize() {
            return Mono.empty();
        }

        @Override
        public Mono<List<McpSchema.Tool>> listTools() {
            McpSchema.Tool tool = McpSchema.Tool.builder()
                    .name("echo")
                    .description("回显")
                    .inputSchema(new McpSchema.JsonSchema("object", Map.of("text", Map.of("type", "string")),
                            List.of("text"), false, null, null))
                    .build();
            return Mono.just(List.of(tool));
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(String name, Map<String, Object> arguments) {
            called.add(name);
            lastArgs.set(arguments);
            return Mono.just(McpSchema.CallToolResult.builder()
                    .content(List.of(new McpSchema.TextContent("Echo: " + arguments.get("text"))))
                    .build());
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(
                String name, Map<String, Object> arguments, Map<String, Object> meta) {
            return callTool(name, arguments);
        }

        @Override
        public McpSchema.Tool getCachedTool(String name) {
            return null;
        }

        @Override
        public void close() {
            /* no-op */
        }
    }

    @Test
    void 列出的工具名带Server命名空间() {
        NamespacedMcpClientWrapper wrapper =
                new NamespacedMcpClientWrapper("everything", new FakeWrapper("everything"));

        List<McpSchema.Tool> tools = wrapper.listTools().block();

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("everything__echo");
        // 其余字段必须原样保留（描述/入参 schema 不能在这一层被丢掉）
        assertThat(tools.get(0).description()).isEqualTo("回显");
        assertThat(tools.get(0).inputSchema().required()).containsExactly("text");
    }

    @Test
    void 调用时剥掉前缀再交给官方客户端() {
        FakeWrapper delegate = new FakeWrapper("everything");
        NamespacedMcpClientWrapper wrapper = new NamespacedMcpClientWrapper("everything", delegate);

        // McpTool.callAsync 传的是注册名（带前缀），必须剥掉后才是服务端认识的工具名
        McpSchema.CallToolResult result = wrapper.callTool("everything__echo", Map.of("text", "hi")).block();

        assertThat(delegate.called).containsExactly("echo");
        assertThat(result.content()).isNotEmpty();
    }

    @Test
    void 不带前缀的名字原样透传() {
        FakeWrapper delegate = new FakeWrapper("everything");
        NamespacedMcpClientWrapper wrapper = new NamespacedMcpClientWrapper("everything", delegate);

        wrapper.callTool("other_tool", Map.of()).block();

        assertThat(delegate.called).containsExactly("other_tool");
    }

    @Test
    void 命名空间前缀便于区分不同Server的同名工具() {
        NamespacedMcpClientWrapper a = new NamespacedMcpClientWrapper("alpha", new FakeWrapper("alpha"));
        NamespacedMcpClientWrapper b = new NamespacedMcpClientWrapper("beta", new FakeWrapper("beta"));

        assertThat(a.namespacedName("echo")).isEqualTo("alpha__echo");
        assertThat(b.namespacedName("echo")).isEqualTo("beta__echo");
    }
}
