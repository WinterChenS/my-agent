package com.myagent.modelconfig.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.myagent.agentconfig.cache.AgentCacheInvalidator;
import com.myagent.common.BizException;
import com.myagent.common.CryptoService;
import com.myagent.common.PageResult;
import com.myagent.modelconfig.dto.ModelRequest;
import com.myagent.modelconfig.dto.ModelResponse;
import com.myagent.modelconfig.dto.ProviderRequest;
import com.myagent.modelconfig.dto.ProviderResponse;
import com.myagent.modelconfig.entity.LlmModel;
import com.myagent.modelconfig.entity.LlmProvider;
import com.myagent.modelconfig.mapper.LlmModelMapper;
import com.myagent.modelconfig.mapper.LlmProviderMapper;
import com.myagent.modelconfig.probe.ModelConnectivityProbe;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 大模型配置：provider / model CRUD 与连通性测试。 */
@Service
public class ModelConfigService {

    /** 与 DDL 的 CHECK 约束保持一致。 */
    private static final Set<String> PROVIDER_TYPES =
            Set.of("OPENAI", "OPENAI_COMPATIBLE", "DASHSCOPE", "ANTHROPIC", "GEMINI", "OLLAMA");
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");
    private static final String MASK = "****";

    private final LlmProviderMapper providerMapper;
    private final LlmModelMapper modelMapper;
    private final CryptoService cryptoService;
    private final ModelConnectivityProbe connectivityProbe;
    private final AgentCacheInvalidator cacheInvalidator;

    public ModelConfigService(
            LlmProviderMapper providerMapper,
            LlmModelMapper modelMapper,
            CryptoService cryptoService,
            ModelConnectivityProbe connectivityProbe,
            AgentCacheInvalidator cacheInvalidator) {
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.cryptoService = cryptoService;
        this.connectivityProbe = connectivityProbe;
        this.cacheInvalidator = cacheInvalidator;
    }

    // ===================== provider =====================

    public PageResult<ProviderResponse> listProviders(long current, long size) {
        Page<LlmProvider> page = providerMapper.selectPage(
                new Page<>(current, size), Wrappers.<LlmProvider>lambdaQuery().orderByAsc(LlmProvider::getId));
        return PageResult.of(
                page.getRecords().stream().map(ModelConfigService::toProviderResponse).toList(),
                page.getTotal(),
                page.getCurrent(),
                page.getSize());
    }

    public ProviderResponse getProvider(Long id) {
        return toProviderResponse(requireProvider(id));
    }

    @Transactional
    public ProviderResponse createProvider(ProviderRequest request) {
        validateProvider(request);
        ensureProviderNameUnique(request.name(), null);
        LlmProvider provider = new LlmProvider();
        provider.setName(request.name());
        provider.setProviderType(normalizeType(request.providerType()));
        provider.setBaseUrl(request.baseUrl());
        provider.setApiKeyCipher(cryptoService.encrypt(request.apiKey()));
        provider.setStatus(normalizeStatus(request.status()));
        providerMapper.insert(provider);
        cacheInvalidator.invalidateAll();
        return toProviderResponse(provider);
    }

    @Transactional
    public ProviderResponse updateProvider(Long id, ProviderRequest request) {
        LlmProvider provider = requireProvider(id);
        validateProvider(request);
        ensureProviderNameUnique(request.name(), id);
        provider.setName(request.name());
        provider.setProviderType(normalizeType(request.providerType()));
        provider.setBaseUrl(request.baseUrl());
        provider.setStatus(normalizeStatus(request.status()));
        // 未传 apiKey 表示保留旧密钥（只写不回读）。
        if (StringUtils.hasText(request.apiKey())) {
            provider.setApiKeyCipher(cryptoService.encrypt(request.apiKey()));
        }
        providerMapper.updateById(provider);
        cacheInvalidator.invalidateAll();
        return toProviderResponse(provider);
    }

    @Transactional
    public void deleteProvider(Long id) {
        requireProvider(id);
        long modelCount = modelMapper.selectCount(
                Wrappers.<LlmModel>lambdaQuery().eq(LlmModel::getProviderId, id));
        if (modelCount > 0) {
            throw BizException.conflict("该供应商下仍有 " + modelCount + " 个模型，无法删除");
        }
        providerMapper.deleteById(id);
        cacheInvalidator.invalidateAll();
    }

    /** 连通性测试：对该供应商下第一个模型发一次最小补全。 */
    public ModelConnectivityProbe.ProbeResult testProvider(Long id, Long modelId) {
        LlmProvider provider = requireProvider(id);
        LlmModel model = modelId == null
                ? modelMapper.selectOne(Wrappers.<LlmModel>lambdaQuery()
                        .eq(LlmModel::getProviderId, id)
                        .orderByAsc(LlmModel::getId)
                        .last("limit 1"))
                : modelMapper.selectById(modelId);
        if (model == null) {
            throw BizException.badRequest("该供应商下没有可用于测试的模型");
        }
        if (!id.equals(model.getProviderId())) {
            throw BizException.badRequest("模型不属于该供应商");
        }
        String apiKey = cryptoService.decrypt(provider.getApiKeyCipher());
        // Phase 15：连通测试必须测「真实配置」——把显式列并入参数，
        // 否则表单填的 max_tokens 不会参与探测，非法配置在测试阶段就暴露不出来（用户实测踩过）。
        Map<String, Object> probeParams = new java.util.HashMap<>(
                model.getParams() == null ? Map.of() : model.getParams());
        if (model.getTemperature() != null) {
            probeParams.put("temperature", model.getTemperature());
        }
        if (model.getTopP() != null) {
            probeParams.put("topP", model.getTopP());
        }
        if (model.getMaxTokens() != null) {
            probeParams.put("maxTokens", model.getMaxTokens());
        }
        return connectivityProbe.probe(
                provider.getProviderType(),
                provider.getBaseUrl(),
                apiKey,
                model.getModelId(),
                probeParams);
    }

    // ===================== model =====================

    public PageResult<ModelResponse> listModels(long current, long size, Long providerId) {
        var query = Wrappers.<LlmModel>lambdaQuery()
                .eq(providerId != null, LlmModel::getProviderId, providerId)
                .orderByAsc(LlmModel::getId);
        Page<LlmModel> page = modelMapper.selectPage(new Page<>(current, size), query);
        return PageResult.of(
                page.getRecords().stream().map(ModelConfigService::toModelResponse).toList(),
                page.getTotal(),
                page.getCurrent(),
                page.getSize());
    }

    public ModelResponse getModel(Long id) {
        return toModelResponse(requireModel(id));
    }

    @Transactional
    public ModelResponse createModel(ModelRequest request) {
        requireProvider(request.providerId());
        ensureModelUnique(request.providerId(), request.modelId(), null);
        LlmModel model = new LlmModel();
        model.setProviderId(request.providerId());
        model.setModelId(request.modelId());
        model.setDisplayName(request.displayName());
        model.setParams(request.params() == null ? Map.of() : request.params());
        applyParams(model, request);
        model.setStatus(normalizeStatus(request.status()));
        modelMapper.insert(model);
        cacheInvalidator.invalidateAll();
        return toModelResponse(model);
    }

    @Transactional
    public ModelResponse updateModel(Long id, ModelRequest request) {
        LlmModel model = requireModel(id);
        requireProvider(request.providerId());
        ensureModelUnique(request.providerId(), request.modelId(), id);
        model.setProviderId(request.providerId());
        model.setModelId(request.modelId());
        model.setDisplayName(request.displayName());
        model.setParams(request.params() == null ? Map.of() : request.params());
        applyParams(model, request);
        model.setStatus(normalizeStatus(request.status()));
        modelMapper.updateById(model);
        cacheInvalidator.invalidateAll();
        return toModelResponse(model);
    }

    @Transactional
    public void deleteModel(Long id) {
        requireModel(id);
        long referencing = modelMapper.countAgentsUsing(id);
        if (referencing > 0) {
            throw BizException.conflict("该模型被 " + referencing + " 个 Agent 引用，无法删除");
        }
        modelMapper.deleteById(id);
        cacheInvalidator.invalidateAll();
    }


    /**
     * 写入并校验模型参数（Phase 15）：温度 / Top-P / 上下文长度 / 输出长度 / 价格。
     *
     * <p>关键规则：**输出长度不得超过上下文窗口**——否则就会出现"补全预算占满窗口"的配置，
     * 一发请求就被厂商以 context_length_exceeded 拒绝（实测踩过）。
     */
    private void applyParams(LlmModel model, ModelRequest request) {
        java.math.BigDecimal temperature = request.temperature();
        if (temperature != null && (temperature.compareTo(java.math.BigDecimal.ZERO) < 0
                || temperature.compareTo(new java.math.BigDecimal("2")) > 0)) {
            throw BizException.badRequest("温度必须在 0–2 之间");
        }
        java.math.BigDecimal topP = request.topP();
        if (topP != null && (topP.compareTo(java.math.BigDecimal.ZERO) <= 0
                || topP.compareTo(java.math.BigDecimal.ONE) > 0)) {
            throw BizException.badRequest("Top-P 必须在 (0, 1] 之间");
        }
        Integer contextWindow = request.contextWindow();
        if (contextWindow != null && contextWindow <= 0) {
            throw BizException.badRequest("上下文长度必须大于 0");
        }
        Integer maxTokens = request.maxTokens();
        if (maxTokens != null && maxTokens <= 0) {
            throw BizException.badRequest("输出长度必须大于 0");
        }
        if (contextWindow != null && maxTokens != null && maxTokens > contextWindow) {
            throw BizException.badRequest(
                    "输出长度（" + maxTokens + "）不能大于上下文长度（" + contextWindow + "）");
        }
        java.math.BigDecimal priceInput = request.priceInput();
        java.math.BigDecimal priceOutput = request.priceOutput();
        if ((priceInput != null && priceInput.signum() < 0) || (priceOutput != null && priceOutput.signum() < 0)) {
            throw BizException.badRequest("价格不能为负数");
        }
        String currency = request.priceCurrency() == null ? null : request.priceCurrency().trim().toUpperCase(java.util.Locale.ROOT);
        if (currency != null && !currency.isEmpty() && !currency.equals("CNY") && !currency.equals("USD")) {
            throw BizException.badRequest("价格货币只支持 CNY 或 USD");
        }

        model.setTemperature(temperature);
        model.setTopP(topP);
        model.setContextWindow(contextWindow);
        model.setMaxTokens(maxTokens);
        model.setPriceInput(priceInput);
        model.setPriceOutput(priceOutput);
        model.setPriceCurrency(currency == null || currency.isEmpty() ? "CNY" : currency);
        model.setMultimodal(Boolean.TRUE.equals(request.multimodal()));
    }

    // ===================== helpers =====================

    private LlmProvider requireProvider(Long id) {
        LlmProvider provider = id == null ? null : providerMapper.selectById(id);
        if (provider == null) {
            throw BizException.notFound("供应商不存在: " + id);
        }
        return provider;
    }

    private LlmModel requireModel(Long id) {
        LlmModel model = id == null ? null : modelMapper.selectById(id);
        if (model == null) {
            throw BizException.notFound("模型不存在: " + id);
        }
        return model;
    }

    private void validateProvider(ProviderRequest request) {
        if (!PROVIDER_TYPES.contains(normalizeType(request.providerType()))) {
            throw BizException.badRequest("provider_type 非法: " + request.providerType());
        }
        if (request.status() != null && !STATUSES.contains(request.status().toUpperCase())) {
            throw BizException.badRequest("status 非法: " + request.status());
        }
    }

    private void ensureProviderNameUnique(String name, Long excludeId) {
        Long existing = providerMapper.selectCount(Wrappers.<LlmProvider>lambdaQuery()
                .eq(LlmProvider::getName, name)
                .ne(excludeId != null, LlmProvider::getId, excludeId));
        if (existing > 0) {
            throw BizException.conflict("供应商名称已存在: " + name);
        }
    }

    private void ensureModelUnique(Long providerId, String modelId, Long excludeId) {
        Long existing = modelMapper.selectCount(Wrappers.<LlmModel>lambdaQuery()
                .eq(LlmModel::getProviderId, providerId)
                .eq(LlmModel::getModelId, modelId)
                .ne(excludeId != null, LlmModel::getId, excludeId));
        if (existing > 0) {
            throw BizException.conflict("该供应商下已存在同名模型: " + modelId);
        }
    }

    private static String normalizeType(String providerType) {
        return providerType == null ? "" : providerType.trim().toUpperCase();
    }

    private static String normalizeStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase() : "ENABLED";
    }

    private static ProviderResponse toProviderResponse(LlmProvider provider) {
        String masked = provider.getApiKeyCipher() == null ? null : MASK;
        return new ProviderResponse(
                provider.getId(),
                provider.getName(),
                provider.getProviderType(),
                provider.getBaseUrl(),
                masked,
                provider.getStatus(),
                provider.getCreatedAt(),
                provider.getUpdatedAt());
    }

    private static ModelResponse toModelResponse(LlmModel model) {
        return new ModelResponse(
                model.getId(),
                model.getProviderId(),
                model.getModelId(),
                model.getDisplayName(),
                model.getParams(),
                model.getTemperature(),
                model.getTopP(),
                model.getContextWindow(),
                model.getMaxTokens(),
                model.getPriceInput(),
                model.getPriceOutput(),
                model.getPriceCurrency(),
                model.getMultimodal(),
                model.getStatus(),
                model.getCreatedAt(),
                model.getUpdatedAt());
    }
}
