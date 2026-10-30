package com.myagent.auth.captcha.dto;

import jakarta.validation.constraints.NotNull;

/** 滑动验证提交：题目 id + 用户拖到的左侧位置（原图像素）。 */
public record CaptchaVerifyRequest(
        String id, @NotNull(message = "缺少拼图位置") Integer x) {}
