package com.myagent.channelconfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.myagent.channelconfig.entity.ChannelApp;
import com.myagent.channelconfig.entity.ChannelPeer;
import com.myagent.channelconfig.service.ChannelTargetResolver;
import com.myagent.channelconfig.service.ChannelTargetResolver.Resolution;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 推送目标解析：把「钉钉 / 张三」解析成确定的 (渠道, 接收人)。
 *
 * <p>这是用户反馈的核心 bug：之前 schedule_task 没这两个参数，{@code target=IM} 时只能回退到
 * "当前会话的绑定"，多机器人/多接收人时**会发给别人**。解析器的契约是「宁可拒绝，也不猜」。
 */
class ChannelTargetResolverTest {

    /** 用内存数据替换数据源（不碰 MyBatis-Plus wrapper）。 */
    private static final class FakeResolver extends ChannelTargetResolver {

        private final List<ChannelApp> apps = new ArrayList<>();
        private final Map<Long, List<ChannelPeer>> peers = new java.util.HashMap<>();

        FakeResolver() {
            super(null, null);
        }

        FakeResolver app(long id, String name, String type, boolean enabled) {
            ChannelApp app = new ChannelApp();
            app.setId(id);
            app.setName(name);
            app.setChannelType(type);
            app.setEnabled(enabled);
            apps.add(app);
            return this;
        }

        FakeResolver peer(long appId, String key, String name, String kind) {
            ChannelPeer peer = new ChannelPeer();
            peer.setChannelAppId(appId);
            peer.setPeerKey(key);
            peer.setPeerName(name);
            peer.setPeerKind(kind);
            peers.computeIfAbsent(appId, k -> new ArrayList<>()).add(peer);
            return this;
        }

        @Override
        protected List<ChannelApp> loadApps() {
            return apps;
        }

        @Override
        protected ChannelApp loadApp(Long channelAppId) {
            return apps.stream().filter(a -> a.getId().equals(channelAppId)).findFirst().orElse(null);
        }

        @Override
        protected List<ChannelPeer> loadPeers(Long channelAppId) {
            return peers.getOrDefault(channelAppId, List.of());
        }
    }

    private FakeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new FakeResolver()
                .app(2L, "钉钉机器人", "DINGTALK", true)
                .app(7L, "测试机器人", "DINGTALK", true)
                .peer(2L, "0935422236", "张三", "DIRECT")
                .peer(2L, "cid-group", "项目群", "GROUP")
                .peer(7L, "peer-x", "小李", "DIRECT");
    }

    @Test
    void 指定渠道名和接收人名能精确解析() {
        Resolution r = resolver.resolve("钉钉机器人", "张三", null, null);

        assertThat(r.ok()).isTrue();
        assertThat(r.channelAppId()).isEqualTo(2L);
        assertThat(r.peerKey()).isEqualTo("0935422236");
        assertThat(r.describe()).contains("钉钉机器人").contains("张三");
    }

    @Test
    void 接收人支持群聊与peerKey() {
        assertThat(resolver.resolve("钉钉机器人", "项目群", null, null).peerKey()).isEqualTo("cid-group");
        assertThat(resolver.resolve("钉钉机器人", "0935422236", null, null).peerKey()).isEqualTo("0935422236");
    }

    @Test
    void 渠道支持id() {
        assertThat(resolver.resolve("2", "张三", null, null).channelAppId()).isEqualTo(2L);
        assertThat(resolver.resolve("7", "小李", null, null).channelAppId()).isEqualTo(7L);
    }

    @Test
    void 渠道有多个候选时拒绝而不是猜() {
        Resolution r = resolver.resolve(null, "张三", null, null);

        assertThat(r.ok()).isFalse();
        assertThat(r.problem()).contains("多个渠道");
        assertThat(r.options()).hasSize(2);
    }

    @Test
    void 接收人不存在时拒绝并列出候选() {
        Resolution r = resolver.resolve("钉钉机器人", "王五", null, null);

        assertThat(r.ok()).isFalse();
        assertThat(r.problem()).contains("找不到接收人");
        assertThat(r.options()).anySatisfy(option -> assertThat(option).contains("张三"));
    }

    @Test
    void 渠道找不到时拒绝并列出全部渠道() {
        Resolution r = resolver.resolve("飞书机器人", "张三", null, null);

        assertThat(r.ok()).isFalse();
        assertThat(r.problem()).contains("找不到渠道");
        assertThat(r.options()).anySatisfy(option -> assertThat(option).contains("钉钉机器人"));
    }

    @Test
    void 只有一个接收人时可以省略接收人() {
        Resolution r = resolver.resolve("测试机器人", null, null, null);

        assertThat(r.ok()).isTrue();
        assertThat(r.peerKey()).isEqualTo("peer-x");
    }

    @Test
    void 多个接收人且未指定时拒绝() {
        Resolution r = resolver.resolve("钉钉机器人", null, null, null);

        assertThat(r.ok()).isFalse();
        assertThat(r.problem()).contains("多个接收人");
        assertThat(r.options()).hasSize(2);
    }

    @Test
    void 显式指定优先于会话兜底绑定() {
        // 会话绑定 7 号渠道的小李，但用户显式要钉钉机器人的张三 → 以显式为准（不能发给小李）
        Resolution r = resolver.resolve("钉钉机器人", "张三", 7L, "peer-x");

        assertThat(r.channelAppId()).isEqualTo(2L);
        assertThat(r.peerKey()).isEqualTo("0935422236");
    }

    @Test
    void 没给渠道时用会话绑定() {
        Resolution r = resolver.resolve(null, null, 2L, "cid-group");

        assertThat(r.ok()).isTrue();
        assertThat(r.channelAppId()).isEqualTo(2L);
        assertThat(r.peerKey()).isEqualTo("cid-group");
    }

    @Test
    void 渠道没有接收人时给出可操作提示() {
        FakeResolver empty = new FakeResolver().app(2L, "钉钉机器人", "DINGTALK", true);

        Resolution r = empty.resolve("钉钉机器人", "张三", null, null);

        assertThat(r.ok()).isFalse();
        assertThat(r.problem()).contains("还没有绑定任何接收人").contains("发一条消息");
    }

    @Test
    void 完全没有渠道时的提示() {
        FakeResolver none = new FakeResolver();

        Resolution r = none.resolve("钉钉", "张三", null, null);

        assertThat(r.ok()).isFalse();
        assertThat(r.problem()).contains("还没有配置任何 IM 渠道");
    }

    @Test
    void 目标清单文本包含渠道与接收人() {
        String text = resolver.describeTargets();

        assertThat(text).contains("钉钉机器人").contains("张三").contains("项目群").contains("测试机器人");
    }

    @Test
    void 名称包含匹配也能命中() {
        // 用户/模型说"钉钉"，能匹配到"钉钉机器人"；"张"能匹配到"张三"
        Resolution r = resolver.resolve("钉钉", "张", null, null);

        assertThat(r.ok()).isTrue();
        assertThat(r.channelAppId()).isEqualTo(2L);
        assertThat(r.peerKey()).isEqualTo("0935422236");
    }

    @Test
    void 全部接收人清单按渠道分组() {
        List<String> names = resolver.allPeers().stream()
                .map(p -> p.channelName() + "/" + p.peerName())
                .collect(Collectors.toList());

        assertThat(names).contains("钉钉机器人/张三", "钉钉机器人/项目群", "测试机器人/小李");
    }

    @Test
    void 模型把清单整行原样回填也能认() {
        // 模型常见行为：直接把 list_im_targets 的输出行贴回来
        Resolution r = resolver.resolve(
                "#2 钉钉机器人（DINGTALK，2 个接收人）",
                "张三（0935422236，单聊）",
                null, null);

        assertThat(r.ok()).isTrue();
        assertThat(r.channelAppId()).isEqualTo(2L);
        assertThat(r.peerKey()).isEqualTo("0935422236");
    }

    @Test
    void 只贴peerKey也能命中() {
        Resolution r = resolver.resolve("#2 钉钉机器人", "cid-group", null, null);

        assertThat(r.ok()).isTrue();
        assertThat(r.peerKey()).isEqualTo("cid-group");
    }
}
