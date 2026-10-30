package com.myagent.agui.reach;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.agent.RuntimeContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 联网工具：注册 / 只读 / 白名单 / SSRF 拦截 / 参数拼装。
 *
 * <p>用一个"记录型"客户端替代真实进程，所以测试不联网也不依赖 agent-reach 是否安装。
 */
class AgentReachToolkitTest {

    /** 记录调用参数、返回固定结果的假客户端。 */
    private static class RecordingClient extends AgentReachClient {
        final List<List<String>> calls = new ArrayList<>();
        Result next = new Result(true, "web", "search", "exa",
                List.of(new Item("T", "https://e.com/1", null, null, "正文")), null, null, null, 12);

        RecordingClient(ReachProperties properties) {
            super(properties, new ObjectMapper());
        }

        @Override
        public Result run(String userId, List<String> args) {
            calls.add(args);
            return next;
        }
    }

    private static ReachProperties properties(boolean enabled) {
        return new ReachProperties(enabled, "agent-reach", 45, 5, 4000,
                "web,github,v2ex,bilibili,youtube", false, false, "", 2, 10);
    }

    private static ToolCallParam param(Map<String, Object> input) {
        return ToolCallParam.builder()
                .input(input)
                .runtimeContext(RuntimeContext.builder().userId("42").sessionId("s1").build())
                .build();
    }

    private static String call(AgentTool tool, Map<String, Object> input) {
        ToolResultBlock block = tool.callAsync(param(input)).block();
        assertThat(block).isNotNull();
        String text = block.getOutput().isEmpty() ? "" : block.getOutput().get(0).toString();
        return text.contains("text=") ? text.substring(text.indexOf("text=")) : text;
    }

    @Test
    @DisplayName("注册三个联网工具，且全部只读（不弹审批卡）")
    void registersReadOnlyTools() {
        ReachProperties props = properties(true);
        AgentReachToolkit toolkit = new AgentReachToolkit(new RecordingClient(props), props);
        Toolkit official = new Toolkit();

        List<String> names = toolkit.register(official);

        assertThat(names).containsExactlyInAnyOrder("internet_search", "internet_read", "internet_channel");
        assertThat(official.getToolNames()).contains("internet_search", "internet_read", "internet_channel");
        assertThat(toolkit.tools()).allMatch(AgentTool::isReadOnly);
    }

    @Test
    @DisplayName("关掉开关就不注册任何联网工具（模型只能回答上不了网）")
    void disabledRegistersNothing() {
        ReachProperties props = properties(false);
        AgentReachToolkit toolkit = new AgentReachToolkit(new RecordingClient(props), props);

        assertThat(toolkit.register(new Toolkit())).isEmpty();
    }

    @Test
    @DisplayName("搜索：拼出 web search 参数，渲染出来源与正文")
    void searchBuildsArgs() {
        ReachProperties props = properties(true);
        RecordingClient client = new RecordingClient(props);
        AgentReachToolkit toolkit = new AgentReachToolkit(client, props);
        AgentTool search = toolkit.tools().stream()
                .filter(t -> t.getName().equals("internet_search")).findFirst().orElseThrow();

        String output = call(search, Map.of("query", "AgentScope", "limit", 3));

        assertThat(client.calls).hasSize(1);
        assertThat(client.calls.get(0)).containsExactly("web", "search", "AgentScope", "--limit", "3");
        assertThat(output).contains("链接：https://e.com/1");
    }

    @Test
    @DisplayName("limit 会被夹到配置上限（不让模型自己把结果拉爆）")
    void searchClampsLimit() {
        ReachProperties props = properties(true);
        RecordingClient client = new RecordingClient(props);
        AgentReachToolkit toolkit = new AgentReachToolkit(client, props);
        AgentTool search = toolkit.tools().stream()
                .filter(t -> t.getName().equals("internet_search")).findFirst().orElseThrow();

        call(search, Map.of("query", "x", "limit", 999));

        assertThat(client.calls.get(0)).containsExactly("web", "search", "x", "--limit", "10");
    }

    @Test
    @DisplayName("读链接：内网地址在调用前就被拦掉（不产生任何请求）")
    void readBlocksPrivateUrls() {
        ReachProperties props = properties(true);
        RecordingClient client = new RecordingClient(props);
        AgentReachToolkit toolkit = new AgentReachToolkit(client, props);
        AgentTool read = toolkit.tools().stream()
                .filter(t -> t.getName().equals("internet_read")).findFirst().orElseThrow();

        String output = call(read, Map.of("url", "http://192.168.3.10:18080/api/health"));

        assertThat(output).contains("出于安全考虑");
        assertThat(client.calls).isEmpty();
    }

    @Test
    @DisplayName("渠道读：白名单外的渠道直接拒（登录类渠道不该被尝试）")
    void channelRejectsNotAllowed() {
        ReachProperties props = properties(true);
        RecordingClient client = new RecordingClient(props);
        AgentReachToolkit toolkit = new AgentReachToolkit(client, props);

        String output = call(toolkit.tools().get(2), Map.of("channel", "xiaohongshu", "action", "search",
                "query", "咖啡"));

        assertThat(output).contains("未启用");
        assertThat(client.calls).isEmpty();
    }

    @Test
    @DisplayName("渠道读：search 需要 query，read 需要 target")
    void channelRequiresTargetOrQuery() {
        ReachProperties props = properties(true);
        RecordingClient client = new RecordingClient(props);
        AgentReachToolkit toolkit = new AgentReachToolkit(client, props);

        assertThat(call(toolkit.tools().get(2), Map.of("channel", "github", "action", "search")))
                .contains("需要 query 或 target");
        assertThat(client.calls).isEmpty();
    }

    @Test
    @DisplayName("渠道读：github search 正常拼参数；read 带公网链接时放行")
    void channelBuildsArgs() {
        ReachProperties props = properties(true);
        RecordingClient client = new RecordingClient(props);
        AgentReachToolkit toolkit = new AgentReachToolkit(client, props);
        AgentTool channel = toolkit.tools().get(2);

        call(channel, Map.of("channel", "GitHub", "action", "search", "query", "agentscope", "limit", 2));
        assertThat(client.calls.get(0)).containsExactly("github", "search", "agentscope", "--limit", "2");

        call(channel, Map.of("channel", "github", "action", "read",
                "target", "https://github.com/agentscope-ai/agentscope"));
        assertThat(client.calls.get(1)).containsExactly("github", "read",
                "https://github.com/agentscope-ai/agentscope");
    }
}
