package com.myagent.agui.reach;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 出网 URL 护栏：这个工具是 Agent 的唯一出网入口，必须挡住内网/本机/元数据/非 http 协议。
 *
 * <p>测试里关掉 DNS 解析（{@code resolveDns=false}）保持离线、确定性；生产默认开启。
 */
class ReachUrlPolicyTest {

    private final ReachUrlPolicy policy = new ReachUrlPolicy(false, false);
    private final ReachUrlPolicy lenient = new ReachUrlPolicy(true, false);

    @Test
    @DisplayName("公网 http/https 放行")
    void allowsPublicUrls() {
        assertThat(policy.reject("https://example.com/a/b?c=1")).isNull();
        assertThat(policy.reject("http://api.github.com/repos/agentscope-ai/agentscope")).isNull();
        assertThat(policy.reject("https://www.bilibili.com/video/BV1xx411c7mD")).isNull();
        assertThat(policy.reject("  https://docs.agentscope.io/  ")).isNull();
    }

    @Test
    @DisplayName("非 http(s) 协议一律拒绝")
    void blocksNonHttpSchemes() {
        assertThat(policy.reject("file:///etc/passwd")).contains("只支持 http/https");
        assertThat(policy.reject("ftp://example.com/x")).contains("只支持 http/https");
        assertThat(policy.reject("gopher://example.com")).contains("只支持 http/https");
        assertThat(policy.reject("javascript:alert(1)")).contains("只支持 http/https");
        assertThat(policy.reject("")).isNotNull();
        assertThat(policy.reject("不是链接")).isNotNull();
    }

    @Test
    @DisplayName("本机/内网字面量地址拒绝（含云元数据 169.254.169.254）")
    void blocksPrivateAddresses() {
        assertThat(policy.reject("http://localhost:8080/api/x")).contains("出于安全考虑");
        assertThat(policy.reject("http://127.0.0.1/")).contains("出于安全考虑");
        assertThat(policy.reject("http://0.0.0.0/")).contains("出于安全考虑");
        assertThat(policy.reject("http://192.168.1.10:5432/")).contains("出于安全考虑");
        assertThat(policy.reject("http://10.1.2.3/")).contains("出于安全考虑");
        assertThat(policy.reject("http://172.20.1.1/")).contains("出于安全考虑");
        assertThat(policy.reject("http://169.254.169.254/latest/meta-data/")).contains("出于安全考虑");
        assertThat(policy.reject("http://100.64.0.1/")).contains("出于安全考虑");
        assertThat(policy.reject("http://[::1]/")).contains("出于安全考虑");
    }

    @Test
    @DisplayName("内网域名 / 无点短名拒绝")
    void blocksInternalHostnames() {
        assertThat(policy.reject("http://intranet/")).contains("内网短名");
        assertThat(policy.reject("http://printer.local/")).contains("内网域名");
        assertThat(policy.reject("http://db.internal/")).contains("内网域名");
        assertThat(policy.reject("http://api.localhost/")).contains("出于安全考虑");
    }

    @Test
    @DisplayName("带用户名密码的 URL 拒绝（容易混淆真实主机）")
    void blocksUserInfo() {
        assertThat(policy.reject("https://user:pass@example.com/")).contains("用户名/密码");
    }

    @Test
    @DisplayName("管理/数据库端口拒绝")
    void blocksSensitivePorts() {
        assertThat(policy.reject("http://example.com:5432/")).contains("端口 5432");
        assertThat(policy.reject("http://example.com:6379/")).contains("端口 6379");
        assertThat(policy.reject("http://example.com:9200/")).contains("端口 9200");
        // 常规 web 端口不受影响
        assertThat(policy.reject("http://example.com:8443/")).isNull();
    }

    @Test
    @DisplayName("显式允许内网时（仅限可信场景）放行内网地址")
    void allowPrivateNetworkOptOut() {
        assertThat(lenient.reject("http://192.168.1.10:8080/")).isNull();
        assertThat(lenient.reject("http://localhost:8080/")).isNull();
    }
}
