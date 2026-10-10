package com.myagent.audit.controller;

import com.myagent.audit.service.TokenStatsService;
import com.myagent.auth.JwtPrincipal;
import com.myagent.common.BizException;
import com.myagent.common.PageResult;
import com.myagent.common.Result;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Token 审计查询。 */
@RestController
@RequestMapping("/api/audit/token")
public class AuditQueryController {

    private final TokenStatsService service;

    public AuditQueryController(TokenStatsService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public Result<Map<String, Object>> summary(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to) {
        return Result.ok(service.summary(
                requireUserId(principal), principal.role(), userId, from, to));
    }

    @GetMapping("/records")
    public Result<PageResult<Map<String, Object>>> records(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(service.records(
                requireUserId(principal), principal.role(), userId, from, to, current, size));
    }

    @GetMapping("/users")
    public Result<List<Map<String, Object>>> users(@AuthenticationPrincipal JwtPrincipal principal) {
        return Result.ok(service.users(requireUserId(principal), principal.role()));
    }

    private Long requireUserId(JwtPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw BizException.unauthorized("未登录");
        }
        return principal.userId();
    }
}
