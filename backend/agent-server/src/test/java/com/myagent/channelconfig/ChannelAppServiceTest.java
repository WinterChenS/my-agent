package com.myagent.channelconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myagent.agentconfig.dto.AgentRequest;
import com.myagent.agentconfig.service.AgentConfigService;
import com.myagent.channelconfig.dto.ChannelAppRequest;
import com.myagent.channelconfig.dto.ChannelAppResponse;
import com.myagent.channelconfig.dto.ChannelPeerResponse;
import com.myagent.channelconfig.service.ChannelAppService;
import com.myagent.common.BizException;
import com.myagent.modelconfig.dto.ModelRequest;
import com.myagent.modelconfig.dto.ProviderRequest;
import com.myagent.modelconfig.service.ModelConfigService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 渠道配置 CRUD：凭据加密/掩码、必填校验、审批模式拦截（连接专用测试库，事务回滚）。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ChannelAppServiceTest {

    @Autowired
    private ChannelAppService channelAppService;

    @Autowired
    private AgentConfigService agentConfigService;

    @Autowired
    private ModelConfigService modelConfigService;

    private Map<String, String> dingtalkConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("appKey", "ding-app-key");
        config.put("appSecret", "ding-app-secret");
        config.put("robotCode", "ding-robot");
        return config;
    }

    private Long newAgent(String permissionMode) {
        String suffix = String.valueOf(System.nanoTime());
        Long providerId = modelConfigService
                .createProvider(new ProviderRequest("p-" + suffix, "openai", "http://localhost:9999", "k", "ENABLED"))
                .id();
        Long modelId = modelConfigService
                .createModel(new ModelRequest(
                        providerId, "m-" + suffix, "gpt-4o-mini", java.util.Map.<String, Object>of(),
                        null, null, null, 1000, null, null, null, false, "ENABLED", null))
                .id();
        return agentConfigService
                .create(new AgentRequest(
                        "a-" + suffix, "agent-" + suffix, "系统提示", modelId,
                        null, null, permissionMode, 5,
                        null, null, null, "ENABLED", java.util.List.of(), java.util.List.of()))
                .id();
    }

    @Test
    void 新建渠道_凭据加密且回显掩码() {
        Long agentId = newAgent("ALWAYS_ALLOW");
        ChannelAppResponse created = channelAppService.create(
                new ChannelAppRequest("DINGTALK", "客服机器人", false, agentId, null, dingtalkConfig()), 1L);

        assertThat(created.id()).isNotNull();
        assertThat(created.status()).isEqualTo("STOPPED");
        assertThat(created.config().get("appKey")).isEqualTo("ding-app-key");
        // 密钥不允许明文回显
        assertThat(created.config().get("appSecret")).isEqualTo("***");
        // 但运行时能拿到明文
        assertThat(channelAppService.config(created.id()).get("appSecret")).isEqualTo("ding-app-secret");
    }

    @Test
    void 缺少必填凭据时拒绝() {
        Long agentId = newAgent("ALWAYS_ALLOW");
        Map<String, String> config = dingtalkConfig();
        config.remove("robotCode");

        assertThatThrownBy(() -> channelAppService.create(
                new ChannelAppRequest("DINGTALK", "缺配置", false, agentId, null, config), 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("robotCode");
    }

    @Test
    void 绑定需要人工审批的Agent时拒绝() {
        Long agentId = newAgent("REQUIRE_APPROVAL");

        assertThatThrownBy(() -> channelAppService.create(
                new ChannelAppRequest("DINGTALK", "审批Agent", false, agentId, null, dingtalkConfig()), 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("审批");
    }

    @Test
    void 更新时不传密钥保持原值_传掩码也不覆盖() {
        Long agentId = newAgent("ALWAYS_ALLOW");
        ChannelAppResponse created = channelAppService.create(
                new ChannelAppRequest("DINGTALK", "改配置", false, agentId, null, dingtalkConfig()), 1L);

        Map<String, String> patch = new HashMap<>();
        patch.put("robotCode", "ding-robot-2");
        patch.put("appSecret", "***"); // 前端回显值
        ChannelAppResponse updated = channelAppService.update(
                created.id(), new ChannelAppRequest("DINGTALK", "改配置", false, agentId, null, patch), 1L);

        Map<String, String> config = channelAppService.config(updated.id());
        assertThat(config.get("robotCode")).isEqualTo("ding-robot-2");
        assertThat(config.get("appSecret")).isEqualTo("ding-app-secret");
        assertThat(config.get("appKey")).isEqualTo("ding-app-key");
    }

    @Test
    void 删除后查询报不存在() {
        Long agentId = newAgent("ALWAYS_ALLOW");
        ChannelAppResponse created = channelAppService.create(
                new ChannelAppRequest("DINGTALK", "待删除", false, agentId, null, dingtalkConfig()), 1L);
        channelAppService.delete(created.id());

        assertThatThrownBy(() -> channelAppService.get(created.id())).isInstanceOf(BizException.class);
    }

    @Test
    void 启用状态可切换() {
        Long agentId = newAgent("ALWAYS_ALLOW");
        ChannelAppResponse created = channelAppService.create(
                new ChannelAppRequest("DINGTALK", "开关", false, agentId, null, dingtalkConfig()), 1L);

        assertThat(channelAppService.setEnabled(created.id(), true).enabled()).isTrue();
        assertThat(channelAppService.enabledApps())
                .extracting(com.myagent.channelconfig.entity.ChannelApp::getId)
                .contains(created.id());
        assertThat(channelAppService.setEnabled(created.id(), false).enabled()).isFalse();
        assertThat(channelAppService.enabledApps())
                .extracting(com.myagent.channelconfig.entity.ChannelApp::getId)
                .doesNotContain(created.id());
    }

    @Test
    void 可以修改IM会话的显示名_且不影响推送地址() {
        Long agentId = newAgent("ALWAYS_ALLOW");
        ChannelAppResponse app = channelAppService.create(
                new ChannelAppRequest("DINGTALK", "改名测试", false, agentId, null, dingtalkConfig()), 1L);
        ChannelPeerResponse peer = channelAppService.bindPeer(app.id(), "staff-123", "你好");

        ChannelPeerResponse renamed = channelAppService.renamePeer(app.id(), peer.id(), "张三");

        assertThat(renamed.peerName()).isEqualTo("张三");
        // peerKey 是推送地址，改名不能动它
        assertThat(renamed.peerKey()).isEqualTo("staff-123");
        assertThat(channelAppService.peers(app.id()))
                .extracting(ChannelPeerResponse::peerName)
                .contains("张三");
    }

    @Test
    void 名称不能为空_也不能超长() {
        Long agentId = newAgent("ALWAYS_ALLOW");
        ChannelAppResponse app = channelAppService.create(
                new ChannelAppRequest("DINGTALK", "改名校验", false, agentId, null, dingtalkConfig()), 1L);
        ChannelPeerResponse peer = channelAppService.bindPeer(app.id(), "staff-1", "原名");

        assertThatThrownBy(() -> channelAppService.renamePeer(app.id(), peer.id(), "  "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> channelAppService.renamePeer(app.id(), peer.id(), "x".repeat(200)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("128");
    }

    @Test
    void 改别的渠道下的会话会报不存在() {
        Long agentId = newAgent("ALWAYS_ALLOW");
        ChannelAppResponse app1 = channelAppService.create(
                new ChannelAppRequest("DINGTALK", "渠道A", false, agentId, null, dingtalkConfig()), 1L);
        ChannelAppResponse app2 = channelAppService.create(
                new ChannelAppRequest("DINGTALK", "渠道B", false, agentId, null, dingtalkConfig()), 1L);
        ChannelPeerResponse peer = channelAppService.bindPeer(app1.id(), "staff-9", "原名");

        assertThatThrownBy(() -> channelAppService.renamePeer(app2.id(), peer.id(), "张三"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("会话不存在");
    }
}
