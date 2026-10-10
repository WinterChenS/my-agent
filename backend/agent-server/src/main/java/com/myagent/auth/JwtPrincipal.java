package com.myagent.auth;

/** 从 JWT 解析出的当前调用者。 */
public record JwtPrincipal(Long userId, String username, String role) {}
