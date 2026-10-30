package com.myagent.auth.captcha;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.myagent.auth.captcha.dto.CaptchaResponse;
import com.myagent.auth.captcha.dto.CaptchaVerifyRequest;
import com.myagent.common.BizException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 登录验证码（滑块拼图 + 无障碍文字题）。
 *
 * <p><b>安全模型（关键约束，改代码前先读）</b>：
 * <ul>
 *   <li><b>答案只在服务端</b>：坐标/文字答案只存库，响应里绝不出现（也不塞进 JWT/Base64 之类可解码的数据）
 *   <li><b>判定只看服务端</b>：请求体里没有 `success` 这类字段，前端改任何变量都改变不了结论
 *   <li><b>题目与会话绑定</b>：{@code session_key} 必须一致，ticket 也只能被同一个会话消费
 *   <li><b>一次性 + 防并发</b>：用 SQL 原子条件更新抢占（{@code used=false AND verified=false}），
 *       并发提交同一题只有一个能成功；ticket 消费用一条 DELETE 完成"校验+删除"
 *   <li><b>防重放/防爆破</b>：单题尝试次数上限、会话/ IP 双维度限流、失败渐进式冷却
 *   <li><b>行为分析只做辅助</b>：轨迹可以伪造，位置对但风险分高才追加拒绝；绝不因为"像人"而放行错位置
 * </ul>
 */
@Service
public class SliderCaptchaService {

    private static final Logger log = LoggerFactory.getLogger(SliderCaptchaService.class);

    private static final String MODE_SLIDER = "SLIDER";
    private static final String MODE_TEXT = "TEXT";

    private final CaptchaChallengeMapper mapper;
    private final CaptchaRateLimiter rateLimiter;
    private final SlideBehaviorAnalyzer behaviorAnalyzer;
    private final SecureRandom random = new SecureRandom();

    @Value("${myagent.captcha.enabled:true}")
    private boolean enabled;

    /** 题目有效期（秒）：默认 90 */
    @Value("${myagent.captcha.ttl-seconds:90}")
    private int ttlSeconds;

    /** 位置容差（原图像素） */
    @Value("${myagent.captcha.tolerance:6}")
    private int tolerance;

    @Value("${myagent.captcha.max-attempts:5}")
    private int maxAttempts;

    /** 是否启用行为风险分析 */
    @Value("${myagent.captcha.behavior.enabled:true}")
    private boolean behaviorEnabled;

    public SliderCaptchaService(CaptchaChallengeMapper mapper, CaptchaRateLimiter rateLimiter,
            SlideBehaviorAnalyzer behaviorAnalyzer) {
        this.mapper = mapper;
        this.rateLimiter = rateLimiter;
        this.behaviorAnalyzer = behaviorAnalyzer;
    }

    public boolean enabled() {
        return enabled;
    }

    public int ttlSeconds() {
        return ttlSeconds;
    }

    public int tolerance() {
        return tolerance;
    }

    /** 出一道新题（默认拼图；{@code mode=text} 出无障碍文字题）。 */
    public CaptchaResponse create(String sessionKey, String ip, String mode) {
        if (!enabled) {
            return CaptchaResponse.disabled();
        }
        String session = requireSession(sessionKey);
        rateLimiter.checkCreate(session, ip);

        LocalDateTime now = LocalDateTime.now();
        CaptchaChallenge challenge = new CaptchaChallenge();
        challenge.setId(newId(20));
        challenge.setVerified(false);
        challenge.setUsed(false);
        challenge.setAttempts(0);
        challenge.setSessionKey(session);
        challenge.setCreatedAt(now);
        challenge.setExpiresAt(now.plusSeconds(ttlSeconds));

        if (MODE_TEXT.equalsIgnoreCase(mode)) {
            int a = 2 + random.nextInt(9);
            int b = 2 + random.nextInt(9);
            boolean plus = random.nextBoolean();
            int answer = plus ? a + b : Math.max(a, b) - Math.min(a, b);
            String question = plus
                    ? a + " + " + b + " = ?"
                    : Math.max(a, b) + " − " + Math.min(a, b) + " = ?";
            challenge.setMode(MODE_TEXT);
            challenge.setAnswerX(0);
            challenge.setPieceY(0);
            challenge.setTextAnswer(String.valueOf(answer));
            mapper.insert(challenge);
            return new CaptchaResponse(true, MODE_TEXT, challenge.getId(), null, null, null,
                    SliderCaptchaImage.CANVAS, SliderCaptchaImage.PAD,
                    SliderCaptchaImage.WIDTH, SliderCaptchaImage.HEIGHT, ttlSeconds, question);
        }

        SliderCaptchaImage.Render render = SliderCaptchaImage.render(random);
        challenge.setMode(MODE_SLIDER);
        challenge.setAnswerX(render.answerX());
        challenge.setPieceY(render.pieceY());
        mapper.insert(challenge);
        return new CaptchaResponse(true, MODE_SLIDER, challenge.getId(), render.background(),
                render.piece(), render.pieceY(), render.pieceSize(), render.pad(),
                SliderCaptchaImage.WIDTH, SliderCaptchaImage.HEIGHT, ttlSeconds, null);
    }

    /**
     * 判定一次提交，成功则返回一次性 ticket。
     *
     * <p>失败信息一律**不透露正确答案**：只说"位置不对/已过期/已使用"。
     */
    public String verify(CaptchaVerifyRequest request, String ip) {
        if (!enabled) {
            return null;
        }
        String session = requireSession(request.sessionKey());
        rateLimiter.checkVerify(session, ip);

        CaptchaChallenge challenge = request.id() == null ? null : mapper.selectById(request.id());
        if (challenge == null) {
            throw BizException.badRequest("验证已失效，请刷新后重试");
        }
        if (challenge.getSessionKey() != null && !challenge.getSessionKey().equals(session)) {
            // 题目不属于这个会话：可能是被搬来的题，直接拒绝（不透露细节）
            throw BizException.badRequest("验证已失效，请刷新后重试");
        }
        if (Boolean.TRUE.equals(challenge.getUsed()) || Boolean.TRUE.equals(challenge.getVerified())) {
            throw BizException.badRequest("这次验证已经用过了，请刷新后重试");
        }
        if (challenge.getExpiresAt() == null
                || challenge.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw BizException.badRequest("验证已过期，请刷新后重试");
        }

        boolean matched;
        int riskScore = 0;

        if (MODE_TEXT.equals(challenge.getMode())) {
            matched = request.answer() != null
                    && request.answer().trim().equals(challenge.getTextAnswer());
        } else {
            // 1) 行为风险（辅助信号）：位置对但明显是脚本，同样拒绝
            SlideBehaviorAnalyzer.Report report =
                    behaviorEnabled ? behaviorAnalyzer.analyze(request.track(), session)
                            : new SlideBehaviorAnalyzer.Report(0, List.of());
            riskScore = report.score();
            if (report.rejected(behaviorAnalyzer.rejectScore())) {
                mapper.invalidate(challenge.getId());
                rateLimiter.recordFailure(session);
                log.info("验证码行为风险拦截 id={} score={} reasons={}", challenge.getId(),
                        report.score(), report.reasons());
                throw BizException.badRequest("验证未通过，请重试");
            }
            // 2) 位置判定：服务端独立计算（容差可配，与渲染比例无关 —— 前端传的是原图像素）
            matched = request.x() != null
                    && Math.abs(challenge.getAnswerX() - request.x()) <= tolerance;
        }

        if (matched) {
            String ticket = newId(24);
            int updated = mapper.claim(challenge.getId(), ticket);
            if (updated != 1) {
                // 并发/重放：别人已经先把这道题用掉了
                throw BizException.badRequest("这次验证已经用过了，请刷新后重试");
            }
            rateLimiter.recordSuccess(session);
            if (behaviorEnabled && MODE_SLIDER.equals(challenge.getMode())) {
                behaviorAnalyzer.remember(session, request.track());
            }
            return ticket;
        }

        int attempts = (challenge.getAttempts() == null ? 0 : challenge.getAttempts()) + 1;
        rateLimiter.recordFailure(session);
        if (attempts >= maxAttempts) {
            mapper.invalidate(challenge.getId());
            log.info("验证码尝试次数超限，作废 id={} attempts={}", challenge.getId(), attempts);
            throw BizException.badRequest("这次验证已作废，请重新获取验证码");
        }
        mapper.recordFailure(challenge.getId(), riskScore);
        throw BizException.badRequest(MODE_TEXT.equals(challenge.getMode())
                ? "答案不对，请再试一次"
                : "拼图位置不对，请再试一次");
    }

    /**
     * 登录时消费 ticket（一次性、且必须属于同一会话）。
     *
     * <p>业务接口（登录）**必须**先过这一步 —— 绕过验证码接口直接打业务接口拿不到有效 ticket。
     */
    public void consumeTicket(String ticket, String sessionKey) {
        if (!enabled) {
            return;
        }
        // 先看有没有票（给用户的提示更准确），再看会话是否合法
        if (ticket == null || ticket.isBlank()) {
            throw BizException.badRequest("请先完成滑动验证");
        }
        String session = requireSession(sessionKey);
        int consumed = mapper.consumeTicket(ticket, session);
        if (consumed != 1) {
            throw BizException.badRequest("滑动验证无效或已过期，请重新验证");
        }
        behaviorAnalyzer.forget(session);
    }

    /** 过期的题目没人再来问，定期清掉。 */
    @Scheduled(fixedDelayString = "${myagent.captcha.cleanup-millis:60000}")
    public void cleanup() {
        try {
            int removed = mapper.delete(Wrappers.<CaptchaChallenge>lambdaQuery()
                    .lt(CaptchaChallenge::getExpiresAt, LocalDateTime.now()));
            if (removed > 0) {
                log.debug("清理过期验证码题目 {} 条", removed);
            }
        } catch (RuntimeException e) {
            log.warn("清理过期验证码题目失败：{}", e.getMessage());
        }
    }

    /** 会话标识是防重放/限流的基础，缺失或明显异常直接拒。 */
    private String requireSession(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            throw BizException.badRequest("缺少会话标识，请刷新页面后重试");
        }
        String trimmed = sessionKey.trim();
        if (trimmed.length() < 8 || trimmed.length() > 64) {
            throw BizException.badRequest("会话标识不合法，请刷新页面后重试");
        }
        return trimmed;
    }

    private String newId(int byteCount) {
        byte[] bytes = new byte[byteCount];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
