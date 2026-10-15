package com.myagent.user.dto;

/** 新建/修改用户（ADMIN）。 */
public record UserRequest(
        String username,
        /** 新建时必填；修改时留空表示不改密码。 */
        String password,
        String displayName,
        /** ADMIN / USER */
        String role,
        /** ENABLED / DISABLED */
        String status) {}
