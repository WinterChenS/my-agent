package com.myagent.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myagent.common.BizException;
import com.myagent.modelconfig.dto.ModelRequest;
import com.myagent.modelconfig.dto.ModelResponse;
import com.myagent.modelconfig.dto.ProviderRequest;
import com.myagent.modelconfig.dto.ProviderResponse;
import com.myagent.modelconfig.entity.LlmProvider;
import com.myagent.modelconfig.mapper.LlmProviderMapper;
import com.myagent.modelconfig.service.ModelConfigService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** provider/model CRUD 与密钥脱敏（连接专用测试库，事务回滚）。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ModelConfigServiceTest {

    private static final String PLAIN_KEY = "sk-plain-secret-key-123";

    @Autowired
    private ModelConfigService service;

    @Autowired
    private LlmProviderMapper providerMapper;

    private ProviderResponse newProvider(String name) {
        return service.createProvider(
                new ProviderRequest(name, "OPENAI_COMPATIBLE", "http://localhost:9999/v1", PLAIN_KEY, "ENABLED"));
    }

    @Test
    void 新建供应商时密钥加密入库且响应只返回掩码() {
        ProviderResponse created = newProvider("测试供应商A");

        assertThat(created.apiKey()).isEqualTo("****");
        assertThat(created.providerType()).isEqualTo("OPENAI_COMPATIBLE");

        // 直接查库确认存的是密文而非明文
        LlmProvider raw = providerMapper.selectById(created.id());
        assertThat(raw.getApiKeyCipher()).isNotNull().isNotEqualTo(PLAIN_KEY);
        assertThat(raw.getApiKeyCipher()).doesNotContain(PLAIN_KEY);
    }

    @Test
    void 更新时不传apiKey则保留原密钥() {
        ProviderResponse created = newProvider("测试供应商B");
        String cipherBefore = providerMapper.selectById(created.id()).getApiKeyCipher();

        service.updateProvider(
                created.id(),
                new ProviderRequest("测试供应商B改", "OPENAI_COMPATIBLE", "http://localhost:9998/v1", null, "DISABLED"));

        LlmProvider after = providerMapper.selectById(created.id());
        assertThat(after.getApiKeyCipher()).isEqualTo(cipherBefore);
        assertThat(after.getName()).isEqualTo("测试供应商B改");
        assertThat(after.getStatus()).isEqualTo("DISABLED");
    }

    @Test
    void 更新时传apiKey则替换密钥() {
        ProviderResponse created = newProvider("测试供应商C");
        String cipherBefore = providerMapper.selectById(created.id()).getApiKeyCipher();

        service.updateProvider(
                created.id(),
                new ProviderRequest("测试供应商C", "OPENAI_COMPATIBLE", "http://localhost:9999/v1", "sk-new", "ENABLED"));

        assertThat(providerMapper.selectById(created.id()).getApiKeyCipher()).isNotEqualTo(cipherBefore);
    }

    @Test
    void 供应商名称重复返回业务错误() {
        newProvider("测试供应商D");
        assertThatThrownBy(() -> newProvider("测试供应商D"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void 非法providerType返回业务错误() {
        assertThatThrownBy(() -> service.createProvider(
                        new ProviderRequest("非法类型", "NOT_A_TYPE", null, "k", "ENABLED")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("provider_type");
    }

    @Test
    void 模型CRUD与params_json往返() {
        ProviderResponse provider = newProvider("测试供应商E");

        ModelResponse model = service.createModel(new ModelRequest(
                provider.id(), "gpt-4o-mini", "示例模型",
                Map.of("temperature", 0.3, "maxTokens", 512), null, null, null, null, null, null, null, "ENABLED"));

        assertThat(model.id()).isNotNull();
        ModelResponse loaded = service.getModel(model.id());
        assertThat(loaded.params()).containsEntry("temperature", 0.3);
        assertThat(loaded.params()).containsEntry("maxTokens", 512);

        // 同一供应商下同名模型冲突
        assertThatThrownBy(() -> service.createModel(new ModelRequest(
                        provider.id(), "gpt-4o-mini", "重复", null, null, null, null, null, null, null, null, "ENABLED")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void 供应商下有模型时不允许删除() {
        ProviderResponse provider = newProvider("测试供应商F");
        service.createModel(new ModelRequest(provider.id(), "m-1", "模型1", null, null, null, null, null, null, null, null, "ENABLED"));

        assertThatThrownBy(() -> service.deleteProvider(provider.id()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无法删除");
    }

    @Test
    void 供应商不存在返回404业务错误() {
        assertThatThrownBy(() -> service.getProvider(999_999_999L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("供应商不存在");
    }
}
