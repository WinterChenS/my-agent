package com.myagent.agui.reach;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CLI 返回值的解析 / 渲染 / 错误话术（不启动进程，纯逻辑）。 */
class AgentReachClientTest {

    private static final String OK_JSON = """
            {"schema_version":1,"ok":true,"channel":"web","action":"search","backend":"exa",
             "items":[
               {"title":"AgentScope","url":"https://github.com/agentscope-ai/agentscope","author":null,
                "published_at":null,"text":"Build and run agents you can see."},
               {"title":"Docs","url":"https://docs.agentscope.io","author":"team",
                "published_at":"2026-10-01","text":"Quickstart ..."}
             ],
             "meta":{"elapsed_ms":1234,"attempts":[]},"error":null}
            """;

    private static final String ERROR_JSON = """
            {"schema_version":1,"ok":false,"channel":"xiaohongshu","action":"search","backend":null,
             "items":[],"meta":{"elapsed_ms":10,"attempts":[]},
             "error":{"code":"NEED_LOGIN","message":"login required","hint":"agent-reach login xiaohongshu"}}
            """;

    private static AgentReachClient client() {
        ReachProperties properties = new ReachProperties(true, "agent-reach", 45, 2, 400, "web", false, false,
                "", 2, 10);
        return new AgentReachClient(properties, new ObjectMapper());
    }

    @Test
    @DisplayName("解析成功响应：字段、条数都对")
    void parsesOkResponse() {
        AgentReachClient.Result result = AgentReachClient.parse(OK_JSON);

        assertThat(result.ok()).isTrue();
        assertThat(result.channel()).isEqualTo("web");
        assertThat(result.action()).isEqualTo("search");
        assertThat(result.backend()).isEqualTo("exa");
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).title()).isEqualTo("AgentScope");
        assertThat(result.items().get(0).url()).contains("github.com");
        assertThat(result.errorCode()).isNull();
    }

    @Test
    @DisplayName("解析失败响应：错误码/原因/建议都保留")
    void parsesErrorResponse() {
        AgentReachClient.Result result = AgentReachClient.parse(ERROR_JSON);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("NEED_LOGIN");
        assertThat(result.errorHint()).contains("login");
        assertThat(result.items()).isEmpty();
    }

    @Test
    @DisplayName("返回不是 JSON：不抛异常，给可读错误")
    void handlesGarbage() {
        AgentReachClient.Result result = AgentReachClient.parse("not a json at all");
        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("BAD_OUTPUT");
    }

    @Test
    @DisplayName("渲染：给出标题、链接、正文，并受条数/字符上限约束")
    void rendersItems() {
        AgentReachClient.Result result = AgentReachClient.parse(OK_JSON);
        String text = client().render(result);

        assertThat(text).contains("来源：web / search");
        assertThat(text).contains("[1] AgentScope");
        assertThat(text).contains("链接：https://github.com/agentscope-ai/agentscope");
        assertThat(text).contains("正文：Build and run agents");
    }

    @Test
    @DisplayName("渲染：超过 max-items 的部分不返回（别把上下文烧光）")
    void renderRespectsMaxItems() {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            items.append("{\"title\":\"t").append(i).append("\",\"url\":\"https://e.com/").append(i)
                    .append("\",\"text\":\"body\"},");
        }
        String json = "{\"ok\":true,\"channel\":\"web\",\"action\":\"search\",\"items\":["
                + items.substring(0, items.length() - 1) + "]}";

        String text = client().render(AgentReachClient.parse(json)); // maxItems=2

        assertThat(text).contains("[1] t0");
        assertThat(text).contains("[2] t1");
        assertThat(text).doesNotContain("[3]");
    }

    @Test
    @DisplayName("失败渲染成中文可操作提示，而不是把英文原文甩给模型")
    void rendersHumanErrors() {
        assertThat(client().render(AgentReachClient.parse(ERROR_JSON)))
                .contains("需要登录");
        assertThat(AgentReachClient.humanError(AgentReachClient.Result.failure("RATE_LIMITED", "x", "")))
                .contains("限流");
        assertThat(AgentReachClient.humanError(AgentReachClient.Result.failure("TIMEOUT", "x", "")))
                .contains("超时");
        assertThat(AgentReachClient.humanError(AgentReachClient.Result.failure("NOT_INSTALLED", "x", "")))
                .contains("没装好");
        assertThat(AgentReachClient.humanError(AgentReachClient.Result.failure("UPSTREAM_BROKEN", "boom", "")))
                .contains("boom");
    }

    @Test
    @DisplayName("单条正文上限可调：读榜单/列表页时能放大（默认 1500 会只截到导航栏）")
    void renderItemCharsIsConfigurable() {
        String body = "x".repeat(5000);
        String json = "{\"ok\":true,\"channel\":\"web\",\"action\":\"read\",\"items\":"
                + "[{\"title\":\"t\",\"url\":\"https://e.com\",\"text\":\"" + body + "\"}]}";
        AgentReachClient.Result result = AgentReachClient.parse(json);

        String narrow = client().render(result, 500);
        String wide = client().render(result, 8000);

        assertThat(narrow.length()).isLessThan(900);
        assertThat(wide.length()).isGreaterThan(4000);
    }

    @Test
    @DisplayName("没结果时给可执行的下一步，而不是空字符串")
    void rendersEmptyResult() {
        String text = client().render(AgentReachClient.parse(
                "{\"ok\":true,\"channel\":\"web\",\"action\":\"search\",\"items\":[]}"));
        assertThat(text).contains("没有找到相关内容");
    }
}
