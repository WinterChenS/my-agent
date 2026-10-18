package com.myagent.agentconfig.controller;

import com.myagent.agentconfig.dto.AgentRequest;
import com.myagent.agentconfig.dto.AgentResponse;
import com.myagent.agentconfig.service.AgentConfigService;
import com.myagent.common.PageResult;
import com.myagent.common.Result;
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

/** Agent 配置。 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentConfigService service;

    public AgentController(AgentConfigService service) {
        this.service = service;
    }

    /** 列表：普通用户只看到"开放给他"的 Agent（按资源授权），管理员看全部。 */
    @GetMapping
    public Result<PageResult<AgentResponse>> list(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.myagent.auth.JwtPrincipal principal,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        boolean isAdmin = principal != null && "ADMIN".equals(principal.role());
        Long userId = principal == null ? null : principal.userId();
        return Result.ok(service.list(current, size, userId, isAdmin));
    }

    /**
     * 设置该 Agent 的可用用户（按资源授权）。
     *
     * <p>写操作只允许 ADMIN（`/api/agents/**` 的写路径由 SecurityConfig 统一要求 ADMIN）。
     * 传空数组 = 恢复"对所有人开放"。
     */
    @PutMapping("/{id}/users")
    public Result<AgentResponse> setAllowedUsers(
            @PathVariable Long id, @RequestBody AllowedUsersRequest request) {
        return Result.ok(service.setAllowedUsers(id, request.userIds()));
    }

    /** 可用用户请求体；{@code userIds} 为空表示对所有人开放。 */
    public record AllowedUsersRequest(java.util.List<Long> userIds) {}

    @GetMapping("/{id}")
    public Result<AgentResponse> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    @PostMapping
    public Result<AgentResponse> create(@Valid @RequestBody AgentRequest request) {
        return Result.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public Result<AgentResponse> update(
            @PathVariable Long id, @Valid @RequestBody AgentRequest request) {
        return Result.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
