package com.myagent.agui.browser;

import com.myagent.agui.reach.ReachUrlPolicy;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 浏览器会话池：**每个用户一个 Chromium**，全局并发受控，空闲自动回收。
 *
 * <p>三件必须做的事（缺一个这台机器就会被打爆）：
 * <ol>
 *   <li>并发闸门：一个 Chromium 打开重页面能吃掉半台机器（实测 3 页面 CPU 162%/2 核），
 *       所以默认 {@code max-concurrent-sessions=1}，超了排队 + 超时给明确提示；
 *   <li>空闲回收：没人看也没人操作超过 {@code idle-timeout-seconds} 就杀掉进程；
 *   <li>出网校验：浏览器能访问内网/云元数据，比 {@code internet_read} 更危险 →
 *       复用 {@link ReachUrlPolicy}（与联网工具同一套规则）。
 * </ol>
 */
@Component
public class BrowserSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(BrowserSessionRegistry.class);

    private final BrowserProperties properties;
    private final ReachUrlPolicy urlPolicy;
    private final Map<String, BrowserSession> sessions = new ConcurrentHashMap<>();
    private final Semaphore gate;

    @Value("${myagent.browser.profile-root:}")
    private String profileRoot;

    public BrowserSessionRegistry(BrowserProperties properties) {
        this.properties = properties;
        this.urlPolicy = new ReachUrlPolicy(false, true);
        this.gate = new Semaphore(Math.max(1, properties.maxConcurrentSessions()), true);
    }

    public BrowserProperties properties() {
        return properties;
    }

    /** 打开（或复用）某个用户的浏览器。并发被占满时抛带说明的异常。 */
    public BrowserSession acquire(String userId) {
        if (!properties.enabled()) {
            throw new IllegalStateException("浏览器功能未启用（服务端 myagent.browser.enabled=false）");
        }
        BrowserSession existing = sessions.get(userId);
        if (existing != null) {
            if (existing.isAlive()) {
                existing.touch();
                return existing;
            }
            log.info("回收已死的浏览器会话 userId={}", userId);
            sessions.remove(userId);
            existing.close();
            gate.release(); // 之前占的名额还回来
        }
        boolean acquired;
        try {
            acquired = gate.tryAcquire(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待浏览器资源被中断");
        }
        if (!acquired) {
            throw new IllegalStateException("浏览器资源被占用（最多同时 " + properties.maxConcurrentSessions()
                    + " 个），请稍后再试或先关掉别的浏览器会话");
        }
        BrowserSession session = new BrowserSession(userId, properties, profileDir(userId));
        // 注意顺序：**先启动成功再放进 map**。
        // 反过来的话，cleanupIdle 可能看到"还没 cdp 的半初始化会话"，误判成死会话并 close 掉
        // —— 实测就是这里导致的 Page.navigate 莫名报「CDP 已关闭」。
        try {
            session.ensureStarted();
        } catch (RuntimeException e) {
            session.close();
            gate.release();
            throw e;
        }
        BrowserSession previous = sessions.put(userId, session);
        if (previous != null && previous != session) {
            previous.close();
        }
        return session;
    }

    /** 只查不改（给"状态查询"用）。 */
    public BrowserSession find(String userId) {
        BrowserSession session = sessions.get(userId);
        if (session == null) {
            return null;
        }
        if (!session.isAlive()) {
            sessions.remove(userId);
            session.close();
            gate.release();
            return null;
        }
        return session;
    }

    public void close(String userId) {
        BrowserSession session = sessions.remove(userId);
        if (session != null) {
            session.close();
            gate.release();
        }
    }

    /** 空闲回收：没人看 + 没操作 + 超过时限就杀。 */
    @Scheduled(fixedDelayString = "${myagent.browser.cleanup-millis:60000}")
    public void cleanupIdle() {
        long now = System.currentTimeMillis();
        long limit = properties.idleTimeoutSeconds() * 1000L;
        sessions.entrySet().removeIf(entry -> {
            BrowserSession session = entry.getValue();
            if (!session.isAlive()) {
                log.info("回收已死的浏览器会话 userId={}", entry.getKey());
                session.close();
                gate.release();
                return true;
            }
            // 刚启动 20 秒内的会话不回收：保证请求处理期间不会被回收线程掐断
            if (now - session.startedAt() < 20_000) {
                return false;
            }
            boolean idle = now - session.lastActiveAt() > limit;
            if (idle) {
                log.info("浏览器空闲回收 userId={}（{} 秒无操作）", entry.getKey(),
                        properties.idleTimeoutSeconds());
                session.close();
                gate.release();
            }
            return idle;
        });
    }

    /** URL 安全校验：通过返回 null，否则返回给模型/用户看的中文原因。 */
    public String rejectUrl(String url) {
        String reason = urlPolicy.reject(url);
        if (reason != null) {
            return reason;
        }
        java.util.List<String> allowList = properties.hostAllowList();
        if (allowList.isEmpty()) {
            return null;
        }
        try {
            String host = java.net.URI.create(url.trim()).getHost();
            String lower = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
            boolean allowed = allowList.stream().anyMatch(rule -> {
                String r = rule.toLowerCase(java.util.Locale.ROOT);
                return lower.equals(r) || lower.endsWith("." + r);
            });
            return allowed ? null : "这个域名不在允许列表里：" + host;
        } catch (RuntimeException e) {
            return "url 解析失败";
        }
    }

    private Path profileDir(String userId) {
        String root = profileRoot == null || profileRoot.isBlank()
                ? Path.of(System.getenv().getOrDefault("MYAGENT_WORKSPACE", "workspace"))
                        .resolve(".browser-profiles").toString()
                : profileRoot;
        return Path.of(root).resolve(userId == null || userId.isBlank() ? "anonymous" : userId);
    }
}
