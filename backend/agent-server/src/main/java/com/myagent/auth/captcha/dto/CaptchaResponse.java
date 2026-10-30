package com.myagent.auth.captcha.dto;

import com.myagent.auth.captcha.SliderCaptchaImage;

/**
 * 滑动验证题目。
 *
 * <p>{@code required=false} 表示服务端关掉了验证（配置 myagent.captcha.enabled=false），
 * 此时前端把滑块整块藏起来、登录不带 ticket 即可。
 */
public record CaptchaResponse(
        boolean required,
        String id,
        String background,
        String piece,
        Integer pieceY,
        int pieceSize,
        int width,
        int height,
        int ttlSeconds) {

    public static CaptchaResponse disabled() {
        return new CaptchaResponse(false, null, null, null, null,
                SliderCaptchaImage.PIECE, SliderCaptchaImage.WIDTH, SliderCaptchaImage.HEIGHT, 0);
    }
}
