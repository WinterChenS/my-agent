package com.myagent.user.controller;

import com.myagent.auth.JwtPrincipal;
import com.myagent.common.BizException;
import com.myagent.common.PageResult;
import com.myagent.common.Result;
import com.myagent.user.dto.PasswordResetRequest;
import com.myagent.user.dto.UserRequest;
import com.myagent.user.dto.UserResponse;
import com.myagent.user.service.UserService;
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
 * 用户管理（C1，仅 ADMIN）。
 *
 * <p>权限由 SecurityConfig 的 {@code CONFIG_PATHS} 兜底（/api/users/** 的写操作要求 ADMIN）；
 * 这里再显式判一次角色，避免有人改了安全配置就顺带把接口暴露出去。
 *
 * <p>所有响应都不含密码哈希（{@link UserResponse} 里根本没有这个字段）。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public Result<PageResult<UserResponse>> list(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status) {
        requireAdmin(principal);
        return Result.ok(userService.list(current, size, keyword, role, status));
    }

    @PostMapping
    public Result<UserResponse> create(
            @AuthenticationPrincipal JwtPrincipal principal, @RequestBody UserRequest request) {
        requireAdmin(principal);
        return Result.ok(userService.create(request));
    }

    @PutMapping("/{id}")
    public Result<UserResponse> update(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable Long id,
            @RequestBody UserRequest request) {
        requireAdmin(principal);
        return Result.ok(userService.update(principal.userId(), id, request));
    }

    @PutMapping("/{id}/password")
    public Result<Void> resetPassword(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable Long id,
            @RequestBody PasswordResetRequest request) {
        requireAdmin(principal);
        userService.resetPassword(id, request.password());
        return Result.ok(null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @AuthenticationPrincipal JwtPrincipal principal, @PathVariable Long id) {
        requireAdmin(principal);
        userService.delete(principal.userId(), id);
        return Result.ok(null);
    }

    private void requireAdmin(JwtPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw BizException.unauthorized("未登录");
        }
        if (!"ADMIN".equals(principal.role())) {
            throw BizException.forbidden("仅管理员可管理用户");
        }
    }
}
