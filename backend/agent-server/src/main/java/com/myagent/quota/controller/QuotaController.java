package com.myagent.quota.controller;

import com.myagent.auth.JwtPrincipal;
import com.myagent.auth.entity.SysUser;
import com.myagent.auth.mapper.SysUserMapper;
import com.myagent.common.BizException;
import com.myagent.common.Result;
import com.myagent.quota.dto.QuotaStatus;
import com.myagent.quota.service.QuotaService;
import java.math.BigDecimal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支出配额（tier1-upgrades ④）。
 *
 * <p>前端据此显示「本日/本月已用成本与额度」；发起 run 前由 {@code AguiRunController} 做拦截判断，
 * 用的是同一个 {@link QuotaService#evaluate}。
 */
@RestController
public class QuotaController {

    private final QuotaService quotaService;
    private final SysUserMapper userMapper;

    public QuotaController(QuotaService quotaService, SysUserMapper userMapper) {
        this.quotaService = quotaService;
        this.userMapper = userMapper;
    }

    /** 设置额度的请求体（空值 = 不限）。 */
    public record QuotaRequest(
            BigDecimal dailyQuota, BigDecimal monthlyQuota, String currency, String mode) {}

    /** 我（+ 指定 Agent）当前的额度与用量。 */
    @GetMapping("/api/quota/me")
    public Result<QuotaStatus> me(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam(required = false) Long agentId) {
        Long userId = requireUserId(principal);
        return Result.ok(quotaService.evaluate(userId, agentId));
    }

    /** 管理员查看某用户的额度与用量（审计看板用）。 */
    @GetMapping("/api/users/{id}/quota")
    public Result<QuotaStatus> userQuota(
            @AuthenticationPrincipal JwtPrincipal principal, @PathVariable Long id) {
        if (principal == null || !"ADMIN".equals(principal.role())) {
            throw BizException.forbidden("仅管理员可查看他人额度");
        }
        return Result.ok(quotaService.evaluate(id, null));
    }

    /** 管理员设置某用户的额度（含超限行为 BLOCK/WARN）。 */
    @PutMapping("/api/users/{id}/quota")
    public Result<QuotaStatus> setQuota(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable Long id,
            @RequestBody QuotaRequest request) {
        if (principal == null || !"ADMIN".equals(principal.role())) {
            throw BizException.forbidden("仅管理员可设置额度");
        }
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw BizException.notFound("用户不存在: " + id);
        }
        if (request.dailyQuota() != null && request.dailyQuota().signum() < 0) {
            throw BizException.badRequest("日额度不能为负");
        }
        if (request.monthlyQuota() != null && request.monthlyQuota().signum() < 0) {
            throw BizException.badRequest("月额度不能为负");
        }
        if (request.mode() != null && !request.mode().isBlank()
                && !"BLOCK".equals(request.mode()) && !"WARN".equals(request.mode())) {
            throw BizException.badRequest("超限行为只能是 BLOCK 或 WARN");
        }
        // 注意：额度要支持「清空」（写回 NULL），而 MyBatis-Plus 的 updateById 默认忽略 null 字段，
        // 所以这里用显式 UPDATE（实测 updateById 置 null 不生效，额度清不掉）。
        java.util.Map<String, Object> sets = new java.util.LinkedHashMap<>();
        sets.put("daily_quota", request.dailyQuota());
        sets.put("monthly_quota", request.monthlyQuota());
        if (request.currency() != null && !request.currency().isBlank()) {
            sets.put("quota_currency", request.currency());
        }
        if (request.mode() != null && !request.mode().isBlank()) {
            sets.put("quota_mode", request.mode());
        }
        userMapper.updateQuota(id, sets);
        return Result.ok(quotaService.evaluate(id, null));
    }

    private Long requireUserId(JwtPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw BizException.unauthorized("未登录");
        }
        return principal.userId();
    }
}
