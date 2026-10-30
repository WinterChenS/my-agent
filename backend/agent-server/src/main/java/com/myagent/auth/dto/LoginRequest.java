package com.myagent.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 *
 * @param captchaTicket 验证码通过后拿到的一次性票据（服务端会在登录时校验并消费）
 * @param sessionKey    发起验证码时用的会话标识（票据只能被同一个会话消费）
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password,
        String captchaTicket,
        String sessionKey) {}
