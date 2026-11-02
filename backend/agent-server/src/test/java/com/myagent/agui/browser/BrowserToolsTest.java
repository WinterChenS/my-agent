package com.myagent.agui.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 浏览器工具与 URL 闸门（不启动真浏览器：会话池用 mock）。
 *
 * <p>真实浏览器行为已在生产环境实测（见 docs/浏览器Agent-实时协同方案.md），
 * 这里守的是"注册 / 只读 / 没打开时的提示 / SSRF 拦截"这些容易回归的逻辑。
 */
class BrowserToolsTest {

    private BrowserSessionRegistry registry;
    private BrowserProperties props;
    private BrowserTools tools;

    @BeforeEach
    void setUp() {
        props = new BrowserProperties(true, "chrome-headless-shell", 1280, 800, 60, 15, 600, 1, 30000, true);
        registry = mock(BrowserSessionRegistry.class);
        when(registry.properties()).thenReturn(props);
        com.myagent.agui.browser.ext.BrowserToolService service =
                mock(com.myagent.agui.browser.ext.BrowserToolService.class);
        when(service.localMode()).thenReturn(false);
        when(service.enabled()).thenReturn(true);
        tools = new BrowserTools(registry, service);
    }

    private static ToolCallParam param(Map<String, Object> input) {
        return ToolCallParam.builder()
                .input(input)
                .runtimeContext(RuntimeContext.builder().userId("7").sessionId("s1").build())
                .build();
    }

    private static String call(AgentTool tool, Map<String, Object> input) {
        return tool.callAsync(param(input)).block().getOutput().get(0).toString();
    }

    @Test
    @DisplayName("注册 7 个浏览器工具，且都标为只读（不逐条弹审批卡）")
    void registersTools() {
        Toolkit toolkit = new Toolkit();
        List<String> names = tools.register(toolkit);

        assertThat(names).containsExactlyInAnyOrder("browser_open", "browser_read", "browser_click",
                "browser_type", "browser_scroll", "browser_screenshot", "browser_close", "browser_status");
        assertThat(toolkit.getToolNames()).contains("browser_open", "browser_click");
        assertThat(tools.tools()).allMatch(AgentTool::isReadOnly);
    }

    @Test
    @DisplayName("服务端关掉浏览器功能时不注册任何工具")
    void disabledRegistersNothing() {
        BrowserProperties off = new BrowserProperties(false, "chrome-headless-shell", 1280, 800, 60, 15, 600, 1, 30000, true);
        BrowserSessionRegistry offRegistry = mock(BrowserSessionRegistry.class);
        when(offRegistry.properties()).thenReturn(off);
        com.myagent.agui.browser.ext.BrowserToolService offService =
                mock(com.myagent.agui.browser.ext.BrowserToolService.class);
        when(offService.enabled()).thenReturn(false);
        when(offService.localMode()).thenReturn(false);
        assertThat(new BrowserTools(offRegistry, offService).register(new Toolkit())).isEmpty();
    }

    @Test
    @DisplayName("还没打开浏览器时，读/点/输入都给出「先 browser_open」的提示")
    void hintsWhenNotOpened() {
        when(registry.find(anyString())).thenReturn(null);
        AgentTool read = tools.tools().get(1);
        AgentTool click = tools.tools().get(2);
        AgentTool type = tools.tools().get(3);

        assertThat(call(read, Map.of())).contains("先调用 browser_open");
        assertThat(call(click, Map.of("selector", "a"))).contains("先调用 browser_open");
        assertThat(call(type, Map.of("text", "hi"))).contains("先调用 browser_open");
    }

    @Test
    @DisplayName("打开内网地址在调用前就被拦掉，不会真的起浏览器")
    void openRejectsUnsafeUrl() {
        when(registry.rejectUrl(any())).thenReturn("出于安全考虑，不能读取本机/内网地址：192.168.1.10");

        String output = call(tools.tools().get(0), Map.of("url", "http://192.168.1.10:8080/"));

        assertThat(output).contains("内网");
        // 被拦下就不应该去 acquire（会启动进程）
        org.mockito.Mockito.verify(registry, org.mockito.Mockito.never()).acquire(anyString());
    }

    @Test
    @DisplayName("会话池：功能关闭时 acquire 直接拒绝（不静默失败）")
    void acquireRejectsWhenDisabled() {
        BrowserProperties off = new BrowserProperties(false, "chrome-headless-shell", 1280, 800, 60, 15, 600, 1, 30000, true);
        BrowserSessionRegistry realRegistry = new BrowserSessionRegistry(off);

        assertThatThrownBy(() -> realRegistry.acquire("7"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未启用");
    }

    @Test
    @DisplayName("会话池的 URL 闸门：内网/非 http 一律拒，公网 IP 放行")
    void registryUrlGate() {
        BrowserSessionRegistry realRegistry = new BrowserSessionRegistry(props);

        assertThat(realRegistry.rejectUrl("http://192.168.1.1/")).isNotNull();
        assertThat(realRegistry.rejectUrl("http://127.0.0.1:8080/")).isNotNull();
        assertThat(realRegistry.rejectUrl("file:///etc/passwd")).isNotNull();
        assertThat(realRegistry.rejectUrl("http://8.8.8.8/")).isNull();
    }
}
