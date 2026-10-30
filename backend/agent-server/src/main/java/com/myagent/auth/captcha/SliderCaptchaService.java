package com.myagent.auth.captcha;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.myagent.auth.captcha.dto.CaptchaResponse;
import com.myagent.common.BizException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 登录滑动验证。
 *
 * <p>流程（前后端联动）：
 * <ol>
 *   <li>{@code GET /api/auth/captcha} → 后端画图、把正确位置**只**存库，返回两张图 + 题目 id
 *   <li>用户把拼图拖到位后 {@code POST /api/auth/captcha/verify {id, x}} → 后端按 {@code tolerance}
 *       容差判定；对了返回一次性 ticket，错了就消耗一次机会并让对方刷新
 *   <li>{@code POST /api/auth/login {username, password, captchaTicket}} → 校验并**删除**该 ticket
 * </ol>
 *
 * <p>为什么用库不用内存：后端重启或多副本时，内存态会把正在登录的人踢掉。
 */
@Service
public class SliderCaptchaService {

    private static final Logger log = LoggerFactory.getLogger(SliderCaptchaService.class);

    private final CaptchaChallengeMapper mapper;
    private final SecureRandom random = new SecureRandom();

    @Value("${myagent.captcha.enabled:true}")
    private boolean enabled;

    @Value("${myagent.captcha.ttl-seconds:120}")
    private int ttlSeconds;

    /** 容差（原图像素）。拼图 48px 宽，±6px 肉眼几乎看不出，但脚本不可能猜中。 */
    @Value("${myagent.captcha.tolerance:6}")
    private int tolerance;

    @Value("${myagent.captcha.max-attempts:5}")
    private int maxAttempts;

    public SliderCaptchaService(CaptchaChallengeMapper mapper) {
        this.mapper = mapper;
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

    /** 出一道新题。 */
    public CaptchaResponse create() {
        if (!enabled) {
            return CaptchaResponse.disabled();
        }
        SliderCaptchaImage.Render render = SliderCaptchaImage.render(random);
        LocalDateTime now = LocalDateTime.now();

        CaptchaChallenge challenge = new CaptchaChallenge();
        challenge.setId(newId(20));
        challenge.setAnswerX(render.answerX());
        challenge.setPieceY(render.pieceY());
        challenge.setVerified(false);
        challenge.setAttempts(0);
        challenge.setCreatedAt(now);
        challenge.setExpiresAt(now.plusSeconds(ttlSeconds));
        mapper.insert(challenge);

        return new CaptchaResponse(true, challenge.getId(), render.background(), render.piece(),
                render.pieceY(), SliderCaptchaImage.PIECE, SliderCaptchaImage.WIDTH,
                SliderCaptchaImage.HEIGHT, ttlSeconds);
    }

    /**
     * 判定拼图位置。
     *
     * @param id 题目 id
     * @param x  用户拖到的左侧位置（原图像素）
     * @return 一次性 ticket，登录时带上
     */
    public String verify(String id, int x) {
        if (!enabled) {
            return null;
        }
        if (id == null || id.isBlank()) {
            throw BizException.badRequest("验证题目已失效，请刷新后重试");
        }
        CaptchaChallenge challenge = mapper.selectById(id);
        if (challenge == null || isExpired(challenge)) {
            throw BizException.badRequest("验证已过期，请刷新后重试");
        }
        if (Boolean.TRUE.equals(challenge.getVerified())) {
            throw BizException.badRequest("这次验证已经用过了，请刷新后重试");
        }

        int attempts = (challenge.getAttempts() == null ? 0 : challenge.getAttempts()) + 1;
        boolean matched = Math.abs(challenge.getAnswerX() - x) <= tolerance;
        if (matched) {
            String ticket = newId(24);
            challenge.setVerified(true);
            challenge.setTicket(ticket);
            challenge.setAttempts(attempts);
            mapper.updateById(challenge);
            return ticket;
        }

        if (attempts >= maxAttempts) {
            // 试探太多次：直接作废这道题，让对面重新拉图
            mapper.deleteById(challenge.getId());
            throw BizException.badRequest("拼图位置连续不对，已作废，请重新验证");
        }
        challenge.setAttempts(attempts);
        mapper.updateById(challenge);
        throw BizException.badRequest("拼图位置不对，请再试一次");
    }

    /**
     * 登录时消费票据（一次一用）。
     *
     * <p>调用即删除，避免同一个 ticket 被重复提交刷接口。
     */
    public void consumeTicket(String ticket) {
        if (!enabled) {
            return;
        }
        if (ticket == null || ticket.isBlank()) {
            throw BizException.badRequest("请先完成滑动验证");
        }
        CaptchaChallenge challenge = mapper.selectOne(
                Wrappers.<CaptchaChallenge>lambdaQuery().eq(CaptchaChallenge::getTicket, ticket));
        if (challenge == null) {
            throw BizException.badRequest("滑动验证无效，请重新验证");
        }
        mapper.deleteById(challenge.getId());
        if (isExpired(challenge)) {
            throw BizException.badRequest("滑动验证已过期，请重新验证");
        }
    }

    /** 过期的题目没人再来问，定期清掉（一分钟一次足够）。 */
    @Scheduled(fixedDelayString = "${myagent.captcha.cleanup-millis:60000}")
    public void cleanup() {
        try {
            int removed = mapper.delete(Wrappers.<CaptchaChallenge>lambdaQuery()
                    .lt(CaptchaChallenge::getExpiresAt, LocalDateTime.now()));
            if (removed > 0) {
                log.debug("清理过期滑动验证题目 {} 条", removed);
            }
        } catch (RuntimeException e) {
            log.warn("清理过期滑动验证题目失败：{}", e.getMessage());
        }
    }

    private boolean isExpired(CaptchaChallenge challenge) {
        return challenge.getExpiresAt() == null || challenge.getExpiresAt().isBefore(LocalDateTime.now());
    }

    private String newId(int byteCount) {
        byte[] bytes = new byte[byteCount];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
