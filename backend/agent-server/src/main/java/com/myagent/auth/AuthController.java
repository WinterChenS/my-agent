package com.myagent.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.myagent.auth.captcha.SliderCaptchaService;
import com.myagent.auth.captcha.dto.CaptchaResponse;
import com.myagent.auth.crypto.LoginKeyService;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SliderCaptchaService captchaService;
    private final LoginKeyService loginKeyService;

    /** 是否仍接受明文密码（默认 false）。仅供本地脚本/联调，生产务必保持关闭。 */
    @Value("${myagent.auth.allow-plaintext-password:false}")
    private boolean allowPlaintextPassword;

    public AuthController(
            SysUserMapper userMapper,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            SliderCaptchaService captchaService,
            LoginKeyService loginKeyService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.captchaService = captchaService;
        this.loginKeyService = loginKeyService;
    }

    /**
     * 下发登录密码的加密公钥。
     *
     * <p>只给公钥，私钥永远留在服务端；前端拿它做 RSA-OAEP(SHA-256) 加密，链路上不再出现明文密码。
     */
    @GetMapping("/login-key")
    public Result<LoginKeyService.PublicKeyInfo> loginKey() {
        return Result.ok(loginKeyService.publicKey());
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
        String password = resolvePassword(request);
        SysUser user = userMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, request.username()));
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
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

    /**
     * 取出真实密码：优先解密前端密文；明文只在显式放开兼容开关时接受（并留痕）。
     *
     * <p>这里不把"解密失败"和"密码错误"混在一起：解密失败直接 400，避免让人误以为是密码错。
     */
    private String resolvePassword(LoginRequest request) {
        String encrypted = request.encryptedPassword();
        if (encrypted != null && !encrypted.isBlank()) {
            if (!loginKeyService.keyIdMatches(request.keyId())) {
                throw BizException.badRequest("加密公钥已更新，请刷新页面后重试");
            }
            return loginKeyService.decrypt(encrypted, request.sessionKey()).password();
        }
        String plain = request.password();
        if (plain == null || plain.isEmpty()) {
            throw BizException.badRequest("密码必须加密传输");
        }
        if (!allowPlaintextPassword) {
            throw BizException.badRequest("密码必须加密传输");
        }
        log.warn("收到明文密码登录（兼容开关已打开），建议尽快关闭 myagent.auth.allow-plaintext-password");
        return plain;
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
