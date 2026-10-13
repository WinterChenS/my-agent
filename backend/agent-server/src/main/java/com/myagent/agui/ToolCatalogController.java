package com.myagent.agui;

import com.myagent.auth.JwtPrincipal;
import com.myagent.common.BizException;
import com.myagent.common.Result;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可用工具清单（供 Skill 编辑器输入 {@code /} 时选用）。
 *
 * <p>只读：MCP 工具来自已同步的 tool_cache，本地工具来自框架自身的 Toolkit schema。
 * 平台**不提供本地工具的增删改**——本地工具由 AgentScope harness 官方注册（read_file /
 * memory_* / agent_* 等），没必要也不应该再叠一层管理。
 */
@RestController
public class ToolCatalogController {

    private final ToolCatalogService toolCatalogService;

    public ToolCatalogController(ToolCatalogService toolCatalogService) {
        this.toolCatalogService = toolCatalogService;
    }

    @GetMapping("/api/tools")
    public Result<List<ToolCatalogService.ToolEntry>> list(
            @AuthenticationPrincipal JwtPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw BizException.unauthorized("未登录");
        }
        return Result.ok(toolCatalogService.list());
    }
}
