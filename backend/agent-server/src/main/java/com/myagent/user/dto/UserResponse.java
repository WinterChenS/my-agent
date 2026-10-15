package com.myagent.user.dto;

import java.time.OffsetDateTime;

/** 用户（**绝不包含密码哈希**）。 */
public record UserResponse(
        Long id,
        String username,
        String displayName,
        String role,
        String status,
        OffsetDateTime createdAt) {}
