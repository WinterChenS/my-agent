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
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    /** 取一道滑动验证题（背景图 + 拼图，答案只在后端）。 */
    @GetMapping("/captcha")
    public Result<CaptchaResponse> captcha() {
        return Result.ok(captchaService.create());
    }

    /** 判定拼图位置，对了发一次性 ticket。 */
    @PostMapping("/captcha/verify")
    public Result<CaptchaVerifyResponse> verifyCaptcha(@Valid @RequestBody CaptchaVerifyRequest request) {
        return Result.ok(new CaptchaVerifyResponse(captchaService.verify(request.id(), request.x())));
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        // 先验人机（一次一用），再查账号 —— 让"猜密码"必须先过滑动验证
        captchaService.consumeTicket(request.captchaTicket());
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

    private UserInfo toInfo(SysUser user) {
        return new UserInfo(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole());
    }
}
