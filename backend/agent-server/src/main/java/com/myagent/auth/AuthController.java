package com.myagent.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.myagent.auth.captcha.SliderCaptchaService;
import com.myagent.auth.captcha.dto.CaptchaResponse;
import com.myagent.auth.captcha.dto.CaptchaVerifyRequest;
import com.myagent.auth.captcha.dto.CaptchaVerifyResponse;
import com.myagent.auth.dto.LoginRequest;
import com.myagent.auth.dto.LoginResponse;
import com.myagent.auth.dto.UserInfo;
import com.myagent.auth.entity.SysUser;
import com.myagent.auth.mapper.SysUserMapper;
import com.myagent.common.BizException;
import com.myagent.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 认证接口。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String STATUS_ENABLED = "ENABLED";

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SliderCaptchaService captchaService;

    public AuthController(
            SysUserMapper userMapper,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            SliderCaptchaService captchaService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.captchaService = captchaService;
    }

    /**
     * 取一道验证码题（拼图；{@code mode=text} 是无障碍替代的文字题）。
     *
     * <p>响应里只有图和题面，**没有正确答案**。
     */
    @GetMapping("/captcha")
    public Result<CaptchaResponse> captcha(
            HttpServletRequest http,
            @RequestParam(name = "sessionKey", required = false) String sessionKey,
            @RequestParam(name = "mode", required = false) String mode) {
        return Result.ok(captchaService.create(sessionKey, clientIp(http), mode));
    }

    /** 判定一次提交（位置/答案由服务端比对，轨迹只作辅助风险信号），对了发一次性 ticket。 */
    @PostMapping("/captcha/verify")
    public Result<CaptchaVerifyResponse> verifyCaptcha(
            @Valid @RequestBody CaptchaVerifyRequest request, HttpServletRequest http) {
        return Result.ok(new CaptchaVerifyResponse(captchaService.verify(request, clientIp(http))));
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        // 先验人机（一次性 + 会话绑定），再查账号 —— 让"猜密码"必须先过验证码
        captchaService.consumeTicket(request.captchaTicket(), request.sessionKey());
        SysUser user = userMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, request.username()));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw BizException.unauthorized("用户名或密码错误");
        }
        if (!STATUS_ENABLED.equals(user.getStatus())) {
            throw BizException.forbidden("账号已被禁用");
        }
        String token = jwtService.issue(user.getId(), user.getUsername(), user.getRole());
        return Result.ok(new LoginResponse(token, toInfo(user)));
    }

    @GetMapping("/me")
    public Result<UserInfo> me(@AuthenticationPrincipal JwtPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw BizException.unauthorized("未登录");
        }
        SysUser user = userMapper.selectById(principal.userId());
        if (user == null) {
            throw BizException.unauthorized("用户不存在");
        }
        return Result.ok(toInfo(user));
    }

    /** 取客户端 IP：优先 X-Forwarded-For（nginx 反代），否则用直连地址。IP 只做辅助限流。 */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private UserInfo toInfo(SysUser user) {
        return new UserInfo(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole());
    }
}
