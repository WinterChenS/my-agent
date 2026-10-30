package com.myagent.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 *
 * <p>密码**默认走密文**：前端用服务端下发的一次性公钥做 RSA-OAEP(SHA-256) 加密，
 * 明文里包着 `p`(密码) / `t`(时间戳) / `n`(一次性随机数) / `s`(会话标识)，
 * 服务端解密后校验新鲜度、nonce 不复用、会话一致。明文 `password` 仅在
 * {@code myagent.auth.allow-plaintext-password=true} 时兼容（仅供本地脚本/联调）。
 *
 * @param captchaTicket 验证码通过后拿到的一次性票据（服务端会在登录时校验并消费）
 * @param sessionKey    发起验证码时用的会话标识（票据只能被同一个会话消费）
 * @param encryptedPassword RSA-OAEP(SHA-256) 密文的 base64
 * @param keyId         加密用的公钥标识（服务端会校验是不是当前这把）
 * @param password      明文密码 —— 已废弃，仅在显式放开兼容开关时可用
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        String password,
        String encryptedPassword,
        String keyId,
        String captchaTicket,
        String sessionKey) {}
