package com.myagent.channelconfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 渠道类型清单：加一家 IM 只加一条描述，校验/掩码/回调路径都从这里读。 */
class ChannelTypeRegistryTest {

    private final ChannelTypeRegistry registry = new ChannelTypeRegistry();

    @Test
    void 内置钉钉与飞书() {
        assertThat(registry.supportedTypes()).contains("DINGTALK", "FEISHU");
        assertThat(registry.find("dingtalk")).isNotNull();
        assertThat(registry.find("DingTalk").displayName()).isEqualTo("钉钉");
        assertThat(registry.find("unknown")).isNull();
        assertThat(registry.supports("nope")).isFalse();
    }

    @Test
    void 钉钉必填项与密钥掩码集合() {
        ChannelTypeDescriptor dingtalk = registry.find("DINGTALK");

        assertThat(dingtalk.requiredKeys()).containsExactly("appKey", "appSecret", "robotCode");
        assertThat(dingtalk.isSecret("appSecret")).isTrue();
        assertThat(dingtalk.isSecret("appKey")).isFalse();
        assertThat(registry.secretKeys("DINGTALK")).contains("aesKey");
    }

    @Test
    void 回调路径会替换渠道id() {
        assertThat(registry.find("DINGTALK").callbackPath(7L))
                .isEqualTo("/api/channels/dingtalk/7/callback");
        assertThat(registry.find("FEISHU").callbackPath(9L))
                .isEqualTo("/api/channels/feishu/9/callback");
    }

    @Test
    void 每个类型都有表单字段且顺序稳定() {
        assertThat(registry.all()).hasSize(registry.supportedTypes().size());
        registry.all().forEach(descriptor -> {
            assertThat(descriptor.fields()).isNotEmpty();
            // 必填项必须都有对应输入框，否则用户无从填写
            descriptor.requiredKeys()
                    .forEach(key -> assertThat(descriptor.fields())
                            .extracting(ChannelTypeDescriptor.Field::key)
                            .contains(key));
        });
    }

    @Test
    void 未知类型的密钥集合为空() {
        assertThat(registry.secretKeys("WHATEVER")).isEmpty();
    }
}
