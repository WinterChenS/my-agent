package com.myagent.auth.dto;

/** 登录响应：JWT + 当前用户。 */
public record LoginResponse(String token, UserInfo user) {}
