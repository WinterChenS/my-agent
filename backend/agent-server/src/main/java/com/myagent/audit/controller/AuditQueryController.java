package com.myagent.audit.controller;

import com.myagent.audit.service.TokenStatsService;
import com.myagent.auth.JwtPrincipal;
import com.myagent.common.BizException;
import com.myagent.common.PageResult;
import com.myagent.common.Result;
import com.myagent.audit.service.AuditTimeParser;
import java.util.List;
import java.util.Map;
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
            // D27：时间参数用字符串接收后自行解析，日期形式也接受，非法输入返回 400 而不是 500
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return Result.ok(service.summary(
                requireUserId(principal), principal.role(), userId,
                AuditTimeParser.parseStart(from), AuditTimeParser.parseEnd(to)));
    }

    @GetMapping("/records")
    public Result<PageResult<Map<String, Object>>> records(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(service.records(
                requireUserId(principal), principal.role(), userId,
                AuditTimeParser.parseStart(from), AuditTimeParser.parseEnd(to), current, size));
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
