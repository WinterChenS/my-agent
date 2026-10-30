package com.myagent.auth.captcha.dto;

import java.util.List;

/**
 * 提交验证。
 *
 * <p>**这些都是客户端可控数据，服务端一律当不可信输入处理**：
 * <ul>
 *   <li>{@code x}：拖动到的左侧位置（原图像素）—— 服务端自己拿存库的答案比对
 *   <li>{@code answer}：TEXT（无障碍）模式的文字答案
 *   <li>{@code track}：滑动轨迹 {@code [[xRatio, tMs], ...]}，只作为**辅助风险信号**
 * </ul>
 * 注意没有 `success` 之类的字段：前端说了不算。
 */
public record CaptchaVerifyRequest(
        String id,
        String sessionKey,
        Integer x,
        String answer,
        List<List<Double>> track) {}
