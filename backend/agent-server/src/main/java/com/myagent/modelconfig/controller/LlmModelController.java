package com.myagent.modelconfig.controller;

import com.myagent.common.PageResult;
import com.myagent.common.Result;
import com.myagent.modelconfig.dto.ModelRequest;
import com.myagent.modelconfig.dto.ModelResponse;
import com.myagent.modelconfig.probe.ModelRetryTester;
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

/** 大模型（模型）配置。 */
@RestController
@RequestMapping("/api/models")
public class LlmModelController {

    private final ModelConfigService service;

    public LlmModelController(ModelConfigService service) {
        this.service = service;
    }

    @GetMapping
    public Result<PageResult<ModelResponse>> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Long providerId) {
        return Result.ok(service.listModels(current, size, providerId));
    }

    /**
     * 当前生效的模型重试策略（管理台「重试测试」弹窗顶部展示）。
     *
     * <p>注意：必须放在 {@code /{id}} 之前，否则会被当成 id 解析。
     */
    @GetMapping("/retry-policy")
    public Result<ModelRetryTester.PolicyView> retryPolicy() {
        return Result.ok(service.retryPolicy());
    }

    /**
     * 重试测试。
     *
     * @param id 模型 id
     * @param simulate true=模拟断流（验证重试链路，不碰真实上游）；默认 false=真实调用一次
     */
    @PostMapping("/{id}/retry-test")
    public Result<ModelRetryTester.TestResult> retryTest(
            @PathVariable Long id, @RequestParam(defaultValue = "false") boolean simulate) {
        return Result.ok(service.testRetry(id, simulate));
    }

    @GetMapping("/{id}")
    public Result<ModelResponse> get(@PathVariable Long id) {
        return Result.ok(service.getModel(id));
    }

    @PostMapping
    public Result<ModelResponse> create(@Valid @RequestBody ModelRequest request) {
        return Result.ok(service.createModel(request));
    }

    @PutMapping("/{id}")
    public Result<ModelResponse> update(
            @PathVariable Long id, @Valid @RequestBody ModelRequest request) {
        return Result.ok(service.updateModel(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.deleteModel(id);
        return Result.ok();
    }
}
