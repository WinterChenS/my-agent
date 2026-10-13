package com.myagent.skillconfig.controller;

import com.myagent.common.PageResult;
import com.myagent.common.Result;
import com.myagent.skillconfig.dto.SkillRequest;
import com.myagent.skillconfig.dto.SkillResponse;
import com.myagent.skillconfig.service.SkillConfigService;
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

/** Skill 配置。 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillConfigService service;
    private final com.myagent.agui.SkillGeneratorService skillGeneratorService;

    public SkillController(
            SkillConfigService service, com.myagent.agui.SkillGeneratorService skillGeneratorService) {
        this.service = service;
        this.skillGeneratorService = skillGeneratorService;
    }

    /** AI 生成技能的请求体。 */
    public record GenerateSkillRequest(String requirement, Long modelId) {}

    /**
     * 用 AI 根据自然语言需求生成技能草稿（不落库，只返回内容供编辑）。
     *
     * <p>POST /api/skills/** 需要 ADMIN（见 SecurityConfig）——生成要花模型 token，限定管理员合理。
     */
    @PostMapping("/generate")
    public Result<com.myagent.agui.SkillGeneratorService.Draft> generate(
            @RequestBody GenerateSkillRequest request) {
        return Result.ok(skillGeneratorService.generate(request.requirement(), request.modelId()));
    }

    @GetMapping
    public Result<PageResult<SkillResponse>> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status) {
        return Result.ok(service.list(current, size, status));
    }

    @GetMapping("/{id}")
    public Result<SkillResponse> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    @PostMapping
    public Result<SkillResponse> create(@Valid @RequestBody SkillRequest request) {
        return Result.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public Result<SkillResponse> update(
            @PathVariable Long id, @Valid @RequestBody SkillRequest request) {
        return Result.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
