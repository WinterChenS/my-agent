package com.myagent.agui;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 观察「模型调用失败了几次」的探针。
 *
 * <p>为什么要用全局集合而不是 ThreadLocal：模型调用在 Reactor 的线程池里跑，
 * 判定重试的谓词也在那个线程上被回调，而发起自检的请求线程是另一条。
 *
 * <p>只在自检（模型管理页的"重试测试"）期间有活跃探针，平时集合为空，判定链路只是多一次
 * 空集合遍历，开销可忽略。
 */
public final class RetryProbeCounter implements AutoCloseable {

    private static final Set<RetryProbeCounter> ACTIVE = ConcurrentHashMap.newKeySet();

    private final AtomicInteger failures = new AtomicInteger();

    private RetryProbeCounter() {}

    /** 开始一次观测；务必 try-with-resources 关闭。 */
    public static RetryProbeCounter start() {
        RetryProbeCounter counter = new RetryProbeCounter();
        ACTIVE.add(counter);
        return counter;
    }

    /** 由 {@link ModelFactory} 的重试判定调用：一次可重试失败 +1。 */
    static void recordRetryableFailure() {
        for (RetryProbeCounter counter : ACTIVE) {
            counter.failures.incrementAndGet();
        }
    }

    /** 已观测到的可重试失败次数。 */
    public int failures() {
        return failures.get();
    }

    @Override
    public void close() {
        ACTIVE.remove(this);
    }
}
