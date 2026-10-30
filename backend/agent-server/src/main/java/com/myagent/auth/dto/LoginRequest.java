package com.myagent.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 登录请求。{@code captchaTicket} 是滑动验证通过后拿到的一次性票据。 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password,
        String captchaTicket) {}
