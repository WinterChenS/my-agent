package com.myagent.skillconfig.controller;

import com.myagent.common.PageResult;
import com.myagent.common.Result;
import com.myagent.skillconfig.dto.SkillImportRequest;
import com.myagent.skillconfig.dto.SkillImportResult;
import com.myagent.skillconfig.dto.SkillRequest;
import com.myagent.skillconfig.dto.SkillResourceDto;
import com.myagent.skillconfig.dto.SkillResponse;
import java.util.List;
import com.myagent.skillconfig.service.SkillConfigService;
import com.myagent.skillconfig.service.SkillImportService;
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
    private final SkillImportService skillImportService;
    private final com.myagent.agui.SkillGeneratorService skillGeneratorService;

    public SkillController(
            SkillConfigService service,
            SkillImportService skillImportService,
            com.myagent.agui.SkillGeneratorService skillGeneratorService) {
        this.service = service;
        this.skillImportService = skillImportService;
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

    /**
     * 从仓库链接导入标准 Skill（默认只预览，不落库）。
     *
     * <p>POST /api/skills/** 需要 ADMIN：导入会下载外部仓库、写入技能库，属于配置写操作。
     * 两段式：先 {@code preview=true} 看仓库里有哪些技能 + 安全结论，再带 {@code names} 安装选中的。
     */
    @PostMapping("/import")
    public Result<SkillImportResult> importFromRepo(@RequestBody SkillImportRequest request) {
        if (request == null || !org.springframework.util.StringUtils.hasText(request.url())) {
            throw com.myagent.common.BizException.badRequest("请填写 Skill 仓库链接");
        }
        return Result.ok(request.isPreview()
                ? skillImportService.preview(request)
                : skillImportService.install(request));
    }

    /**
     * 技能列表。
     *
     * @param agentId 传了就只返回该 Agent **绑定**的技能（聊天输入框的 `/` 候选用它，
     *     避免列出模型根本调不到的技能）
     */
    @GetMapping
    public Result<PageResult<SkillResponse>> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long agentId) {
        return Result.ok(service.list(current, size, status, agentId));
    }

    @GetMapping("/{id}")
    public Result<SkillResponse> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    /**
     * 某技能的资源文件列表（渐进式披露的第二层：references/ scripts/ data/ …）。
     *
     * <p>只返回路径与大小，不带正文——大型技能有几百个文件、总计几 MB，一次全传会让管理页卡住。
     */
    @GetMapping("/{id}/resources")
    public Result<List<SkillResourceDto>> resources(@PathVariable Long id) {
        return Result.ok(service.listResources(id));
    }

    /** 读某个资源文件的正文（点开才读）。 */
    @GetMapping("/{id}/resources/content")
    public Result<SkillResourceDto> resourceContent(
            @PathVariable Long id, @RequestParam("path") String path) {
        return Result.ok(service.readResource(id, path));
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
