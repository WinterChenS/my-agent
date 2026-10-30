package com.myagent.auth.captcha.dto;

import com.myagent.auth.captcha.SliderCaptchaImage;

/**
 * 验证码题目（只含"人能看图判断"的信息，**绝不含正确答案**）。
 *
 * <p>{@code required=false} 表示服务端关掉了验证（配置 myagent.captcha.enabled=false），
 * 前端把整块藏起来、登录不带 ticket 即可。
 *
 * @param mode      SLIDER（拼图拖动）/ TEXT（无障碍替代题：算术）
 * @param question  TEXT 模式下的题目文字，如 "7 + 5 = ?"；SLIDER 模式为 null
 * @param piecePad  拼图块图片相对缺口的向外边距（像素，原图坐标系），前端据此对齐
 * @param pieceSize 拼图块图片边长（含边距），前端按 width 比例渲染
 */
public record CaptchaResponse(
        boolean required,
        String mode,
        String id,
        String background,
        String piece,
        Integer pieceY,
        int pieceSize,
        int piecePad,
        int width,
        int height,
        int ttlSeconds,
        String question) {

    public static CaptchaResponse disabled() {
        return new CaptchaResponse(false, "SLIDER", null, null, null, null,
                SliderCaptchaImage.CANVAS, SliderCaptchaImage.PAD,
                SliderCaptchaImage.WIDTH, SliderCaptchaImage.HEIGHT, 0, null);
    }
}
