package com.myagent.modelconfig.controller;

import com.myagent.common.PageResult;
import com.myagent.common.Result;
import com.myagent.modelconfig.dto.ProviderRequest;
import com.myagent.modelconfig.dto.ProviderResponse;
import com.myagent.modelconfig.probe.ModelConnectivityProbe;
import com.myagent.modelconfig.service.ModelConfigService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 大模型供应商配置。 */
@RestController
@RequestMapping("/api/model-providers")
public class ModelProviderController {

    private final ModelConfigService service;

    public ModelProviderController(ModelConfigService service) {
        this.service = service;
    }

    @GetMapping
    public Result<PageResult<ProviderResponse>> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(service.listProviders(current, size));
    }

    @GetMapping("/{id}")
    public Result<ProviderResponse> get(@PathVariable Long id) {
        return Result.ok(service.getProvider(id));
    }

    @PostMapping
    public Result<ProviderResponse> create(@Valid @RequestBody ProviderRequest request) {
        return Result.ok(service.createProvider(request));
    }

    @PutMapping("/{id}")
    public Result<ProviderResponse> update(
            @PathVariable Long id, @Valid @RequestBody ProviderRequest request) {
        return Result.ok(service.updateProvider(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.deleteProvider(id);
        return Result.ok();
    }

    /**
     * 连通性测试。
     *
     * @param modelId 可选；不传则使用该供应商下第一个模型
     */
    @PostMapping("/{id}/test")
    public Result<ModelConnectivityProbe.ProbeResult> test(
            @PathVariable Long id, @RequestParam(required = false) Long modelId) {
        return Result.ok(service.testProvider(id, modelId));
    }
}
