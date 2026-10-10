package com.myagent.auth.dto;

/** 当前用户信息（对外返回，不含口令哈希）。 */
public record UserInfo(Long id, String username, String displayName, String role) {}
