package com.myagent.auth.captcha.dto;

/** 滑动验证通过后的票据，登录时带上。 */
public record CaptchaVerifyResponse(String ticket) {}
