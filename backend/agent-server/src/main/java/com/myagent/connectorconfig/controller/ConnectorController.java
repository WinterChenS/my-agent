package com.myagent.connectorconfig.controller;

import com.myagent.auth.JwtPrincipal;
import com.myagent.common.BizException;
import com.myagent.common.Result;
import com.myagent.connectorconfig.ConnectorTypeDescriptor;
import com.myagent.connectorconfig.ConnectorTypeRegistry;
import com.myagent.connectorconfig.dto.ConnectorRequest;
import com.myagent.connectorconfig.dto.ConnectorResponse;
import com.myagent.connectorconfig.dto.ConnectorTestResult;
import com.myagent.connectorconfig.ConnectorService;
import com.myagent.connectorconfig.dto.ConnectorBindingRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 连接器（管理页 + 连接器绑定）。
 *
 * <p>权限：**普通用户只能操作自己的连接器**（服务层按属主校验），管理员可看全部。
 * 因此这里不像渠道配置那样整条路径要求 ADMIN。
 */
@RestController
@RequestMapping("/api/connectors")
public class ConnectorController {

    private final ConnectorService service;
    private final ConnectorTypeRegistry typeRegistry;

    public ConnectorController(ConnectorService service, ConnectorTypeRegistry typeRegistry) {
        this.service = service;
        this.typeRegistry = typeRegistry;
    }

    /** 支持的类型（驱动前端动态表单）。 */
    @GetMapping("/types")
    public Result<List<ConnectorTypeDescriptor>> types() {
        return Result.ok(typeRegistry.all());
    }

    @GetMapping
    public Result<List<ConnectorResponse>> list(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam(name = "all", defaultValue = "false") boolean all) {
        return Result.ok(service.list(principal.userId(), isAdmin(principal), all));
    }

    @GetMapping("/{id}")
    public Result<ConnectorResponse> get(@AuthenticationPrincipal JwtPrincipal principal, @PathVariable Long id) {
        return Result.ok(service.get(id, principal.userId(), isAdmin(principal)));
    }

    @PostMapping
    public Result<ConnectorResponse> create(
            @AuthenticationPrincipal JwtPrincipal principal,
            @Valid @RequestBody ConnectorRequest request) {
        return Result.ok(service.create(request, principal.userId()));
    }

    @PutMapping("/{id}")
    public Result<ConnectorResponse> update(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody ConnectorRequest request) {
        return Result.ok(service.update(id, request, principal.userId(), isAdmin(principal)));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@AuthenticationPrincipal JwtPrincipal principal, @PathVariable Long id) {
        service.delete(id, principal.userId(), isAdmin(principal));
        return Result.ok(null);
    }

    /** 测试连接（已保存的）。 */
    @PostMapping("/{id}/test")
    public Result<ConnectorTestResult> test(
            @AuthenticationPrincipal JwtPrincipal principal, @PathVariable Long id) {
        return Result.ok(service.test(id, principal.userId(), isAdmin(principal)));
    }

    /** 测试连接（新建表单，未保存的配置）。 */
    @PostMapping("/test")
    public Result<ConnectorTestResult> testDraft(@Valid @RequestBody ConnectorRequest request) {
        return Result.ok(service.testDraft(request.type(), request.config()));
    }

    @GetMapping("/{id}/agents")
    public Result<List<Long>> agents(
            @AuthenticationPrincipal JwtPrincipal principal, @PathVariable Long id) {
        return Result.ok(service.boundAgents(id, principal.userId(), isAdmin(principal)));
    }

    @PutMapping("/{id}/agents")
    public Result<List<Long>> bindAgents(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable Long id,
            @RequestBody ConnectorBindingRequest request) {
        return Result.ok(service.bindAgents(id, request.agentIds(), principal.userId(), isAdmin(principal)));
    }

    /** 写操作审计（发信/回复/标记/删除）。 */
    @GetMapping("/{id}/actions")
    public Result<List<Map<String, Object>>> actions(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable Long id,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return Result.ok(service.recentActions(id, principal.userId(), isAdmin(principal), limit));
    }

    private static boolean isAdmin(JwtPrincipal principal) {
        if (principal == null) {
            throw BizException.badRequest("未登录");
        }
        return "ADMIN".equalsIgnoreCase(principal.role());
    }
}
