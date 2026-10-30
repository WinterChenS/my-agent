package com.myagent.auth.captcha;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 验证码限流与渐进式冷却。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>**会话（sessionKey）为主**、**IP 为辅**：IP 只做辅助限流，且阈值明显更宽 ——
 *       NAT / 动态 IP / 公司出口都很常见，拿 IP 当唯一身份必然误伤正常用户
 *   <li>失败后**渐进式冷却**：连续失败越多，要求等待越久（指数退避，设上限），
 *       把"无限重试猜坐标"的成本抬上去
 *   <li>进程内滑动窗口（单实例足够）。若将来多副本，把 {@link #hits} 换成 Redis 的
 *       ZSET（ZREMRANGEBYSCORE + ZCARD）即可，接口不用改
 * </ul>
 */
@Component
public class CaptchaRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(CaptchaRateLimiter.class);

    /** bucket:key → 最近若干次命中的时间戳 */
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    /** sessionKey → 连续失败次数 */
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    @Value("${myagent.captcha.rate-limit.create-per-minute-session:20}")
    private int createPerMinuteSession;

    @Value("${myagent.captcha.rate-limit.create-per-minute-ip:60}")
    private int createPerMinuteIp;

    @Value("${myagent.captcha.rate-limit.verify-per-minute-session:12}")
    private int verifyPerMinuteSession;

    @Value("${myagent.captcha.rate-limit.verify-per-minute-ip:60}")
    private int verifyPerMinuteIp;

    /** 连续失败达到这个次数开始冷却 */
    @Value("${myagent.captcha.rate-limit.cooldown-threshold:5}")
    private int cooldownThreshold;

    /** 冷却基数（秒），之后按失败次数指数增长 */
    @Value("${myagent.captcha.rate-limit.cooldown-base-seconds:15}")
    private int cooldownBaseSeconds;

    /** 冷却上限（秒） */
    @Value("${myagent.captcha.rate-limit.cooldown-max-seconds:300}")
    private int cooldownMaxSeconds;

    /** 失败计数窗口（秒）：超过这么久没失败，计数清零 */
    @Value("${myagent.captcha.rate-limit.fail-window-seconds:300}")
    private int failWindowSeconds;

    /** 出题限流：会话维度优先，IP 维度兜底。 */
    public void checkCreate(String sessionKey, String ip) {
        requireWithin("create:session", sessionKey, createPerMinuteSession, 60,
                "验证码刷新太频繁，请稍后再试");
        requireWithin("create:ip", ip, createPerMinuteIp, 60, "请求过于频繁，请稍后再试");
    }

    /** 校验限流：先看冷却，再看频率。 */
    public void checkVerify(String sessionKey, String ip) {
        long cooldown = cooldownRemainingSeconds(sessionKey);
        if (cooldown > 0) {
            throw com.myagent.common.BizException.tooManyRequests(
                    "验证失败次数过多，请 " + cooldown + " 秒后再试");
        }
        requireWithin("verify:session", sessionKey, verifyPerMinuteSession, 60,
                "验证太频繁，请稍后再试");
        requireWithin("verify:ip", ip, verifyPerMinuteIp, 60, "请求过于频繁，请稍后再试");
    }

    /** 记一次失败（用于渐进式冷却）。 */
    public void recordFailure(String sessionKey) {
        long now = System.currentTimeMillis();
        Deque<Long> queue = failures.computeIfAbsent(sessionKey, k -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(now);
            long cutoff = now - failWindowSeconds * 1000L;
            while (!queue.isEmpty() && queue.peekFirst() < cutoff) {
                queue.pollFirst();
            }
        }
    }

    /** 验证成功后清空该会话的失败计数（正常用户不该被自己之前的错误拖累太久）。 */
    public void recordSuccess(String sessionKey) {
        failures.remove(sessionKey);
    }

    /** 还要等多少秒才能再试；0 表示不受冷却限制。 */
    public long cooldownRemainingSeconds(String sessionKey) {
        Deque<Long> queue = failures.get(sessionKey);
        if (queue == null) {
            return 0;
        }
        List<Long> recent;
        synchronized (queue) {
            recent = new ArrayList<>(queue);
        }
        if (recent.size() < cooldownThreshold) {
            return 0;
        }
        int over = recent.size() - cooldownThreshold;
        long wait = (long) (cooldownBaseSeconds * Math.pow(2, Math.min(over, 6)));
        wait = Math.min(wait, cooldownMaxSeconds);
        long last = recent.get(recent.size() - 1);
        long elapsed = (System.currentTimeMillis() - last) / 1000L;
        return Math.max(0, wait - elapsed);
    }

    private void requireWithin(String bucket, String key, int limit, int windowSeconds,
            String message) {
        if (key == null || key.isBlank() || limit <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long cutoff = now - windowSeconds * 1000L;
        Deque<Long> queue = hits.computeIfAbsent(bucket + ":" + key, k -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && queue.peekFirst() < cutoff) {
                queue.pollFirst();
            }
            if (queue.size() >= limit) {
                throw com.myagent.common.BizException.tooManyRequests(message);
            }
            queue.addLast(now);
        }
    }

    /** 定期清理空窗口，避免长期运行后 map 无限膨胀。 */
    @Scheduled(fixedDelayString = "${myagent.captcha.rate-limit.cleanup-millis:120000}")
    public void cleanup() {
        long now = System.currentTimeMillis();
        long cutoff = now - 10 * 60 * 1000L;
        hits.entrySet().removeIf(entry -> {
            Deque<Long> queue = entry.getValue();
            synchronized (queue) {
                return queue.isEmpty() || queue.peekLast() < cutoff;
            }
        });
        failures.entrySet().removeIf(entry -> {
            Deque<Long> queue = entry.getValue();
            synchronized (queue) {
                return queue.isEmpty() || queue.peekLast() < cutoff;
            }
        });
        log.debug("验证码限流窗口清理完成，剩余 {} 个窗口", hits.size());
    }
}
