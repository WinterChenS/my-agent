package com.myagent.auth.captcha;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 滑动轨迹行为分析（**辅助风险信号，不是放行依据**）。
 *
 * <p>必须先说清楚前提：**前端上传的轨迹和时间戳可以任意伪造**。所以这里只做"加成本"，
 * 不做"判真伪"：
 * <ul>
 *   <li>任何**单一**特征都不允许直接判机器人 —— 单项最高 +40，而默认拦截线是 60，
 *       也就是说至少要有两个独立信号叠加才会被拒
 *   <li>位置判定永远由服务端独立完成；行为分只在"位置对"的前提下用来追加拒绝，
 *       绝不会因为"行为看起来像人"就让一个错位置通过
 *   <li>触摸屏 / 触控板 / 鼠标 / 辅助设备的差异已通过阈值宽松化 + 多信号叠加来兼容
 * </ul>
 */
@Component
public class SlideBehaviorAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SlideBehaviorAnalyzer.class);

    /** 少于这么多采样点，基本只能是脚本 */
    @Value("${myagent.captcha.behavior.min-points:5}")
    private int minPoints;

    /** 人拖动一般不会低于这个总时长（毫秒） */
    @Value("${myagent.captcha.behavior.min-duration-ms:130}")
    private int minDurationMs;

    /** 速度变异系数低于该值 → 视为"全程匀速" */
    @Value("${myagent.captcha.behavior.uniform-speed-cv:0.12}")
    private double uniformSpeedCv;

    /** 风险分达到该值就拒绝（默认 60 > 任何单项，保证是多信号叠加才拒） */
    @Value("${myagent.captcha.behavior.reject-score:60}")
    private int rejectScore;

    /** 同一会话保留多少条历史轨迹用于"重复轨迹"比对 */
    @Value("${myagent.captcha.behavior.history-size:20}")
    private int historySize;

    private final Map<String, Deque<long[]>> history = new ConcurrentHashMap<>();

    public int rejectScore() {
        return rejectScore;
    }

    /**
     * 分析一次滑动轨迹。
     *
     * @param raw 前端上传的采样点：每项 {@code [xRatio(0~1), 相对时间毫秒]}；可以为 null/空（那就按"无数据"重罚）
     * @param sessionKey 会话标识，用于"重复高度相似轨迹"判断
     */
    public Report analyze(List<List<Double>> raw, String sessionKey) {
        List<String> reasons = new ArrayList<>();
        int score = 0;

        List<double[]> points = parse(raw);
        if (points.size() < minPoints) {
            score += 35;
            reasons.add("采样点过少(" + points.size() + ")");
        }

        double duration = points.isEmpty() ? 0 : points.get(points.size() - 1)[1];
        if (duration < minDurationMs) {
            score += 40;
            reasons.add("拖动过快(" + Math.round(duration) + "ms)");
        }

        if (!monotonic(points)) {
            score += 30;
            reasons.add("时间戳非单调");
        }
        if (hasTeleport(points)) {
            score += 30;
            reasons.add("单步瞬移");
        }
        if (points.size() >= 6 && uniformSpeed(points)) {
            score += 25;
            reasons.add("全程匀速");
        }
        if (points.size() >= 6 && uniformIntervals(points)) {
            score += 20;
            reasons.add("采样间隔完全一致");
        }
        if (points.size() >= 6 && almostLinear(points)) {
            score += 15;
            reasons.add("轨迹近似直线");
        }
        if (repeatedLikeHistory(points, sessionKey)) {
            score += 30;
            reasons.add("与近期轨迹高度相似");
        }

        score = Math.min(100, score);
        return new Report(score, reasons);
    }

    /** 记住一条"通过位置校验"的轨迹，供后续重复检测。 */
    public void remember(String sessionKey, List<List<Double>> raw) {
        List<double[]> points = parse(raw);
        if (points.size() < 3) {
            return;
        }
        long[] signature = signature(points);
        Deque<long[]> queue = history.computeIfAbsent(sessionKey, k -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(signature);
            while (queue.size() > historySize) {
                queue.pollFirst();
            }
        }
    }

    public void forget(String sessionKey) {
        history.remove(sessionKey);
    }

    /** 分析结果：分数 + 人话原因（原因只写日志，不回给客户端，避免给攻击者调参）。 */
    public record Report(int score, List<String> reasons) {
        public boolean rejected(int threshold) {
            return score >= threshold;
        }

        @Override
        public String toString() {
            return score + reasons.toString();
        }
    }

    // ===================== 特征 =====================

    private List<double[]> parse(List<List<Double>> raw) {
        List<double[]> points = new ArrayList<>();
        if (raw == null) {
            return points;
        }
        for (List<Double> item : raw) {
            if (item == null || item.size() < 2) {
                continue;
            }
            Double x = item.get(0);
            Double t = item.get(1);
            if (x == null || t == null || x.isNaN() || t.isNaN()) {
                continue;
            }
            points.add(new double[] {x, t});
        }
        return points;
    }

    private boolean monotonic(List<double[]> points) {
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i)[1] < points.get(i - 1)[1] - 1e-3) {
                return false;
            }
        }
        return true;
    }

    /** 一步跳掉大半个滑轨且几乎不耗时 → 瞬移。 */
    private boolean hasTeleport(List<double[]> points) {
        for (int i = 1; i < points.size(); i++) {
            double dx = Math.abs(points.get(i)[0] - points.get(i - 1)[0]);
            double dt = points.get(i)[1] - points.get(i - 1)[1];
            if (dx >= 0.45 && dt <= 20) {
                return true;
            }
        }
        return false;
    }

    private boolean uniformSpeed(List<double[]> points) {
        List<Double> speeds = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) {
            double dt = points.get(i)[1] - points.get(i - 1)[1];
            if (dt <= 0.5) {
                continue;
            }
            speeds.add(Math.abs(points.get(i)[0] - points.get(i - 1)[0]) / dt);
        }
        if (speeds.size() < 4) {
            return false;
        }
        double mean = speeds.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        if (mean <= 1e-9) {
            return true; // 全程静止
        }
        double var = speeds.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        double cv = Math.sqrt(var) / mean;
        return cv < uniformSpeedCv;
    }

    private boolean uniformIntervals(List<double[]> points) {
        List<Double> intervals = new ArrayList<>();
        for (int i = 1; i < points.size(); i++) {
            intervals.add(points.get(i)[1] - points.get(i - 1)[1]);
        }
        if (intervals.size() < 4) {
            return false;
        }
        double mean = intervals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        if (mean <= 0) {
            return true;
        }
        double var = intervals.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        return Math.sqrt(var) < 1.2;
    }

    /** x 与 t 的线性拟合优度接近 1（说明是"匀速直线"，人做不到）。 */
    private boolean almostLinear(List<double[]> points) {
        int n = points.size();
        double sx = 0;
        double st = 0;
        for (double[] p : points) {
            sx += p[0];
            st += p[1];
        }
        double mx = sx / n;
        double mt = st / n;
        double cov = 0;
        double varT = 0;
        double varX = 0;
        for (double[] p : points) {
            cov += (p[1] - mt) * (p[0] - mx);
            varT += (p[1] - mt) * (p[1] - mt);
            varX += (p[0] - mx) * (p[0] - mx);
        }
        if (varT <= 0 || varX <= 0) {
            return false;
        }
        double r = cov / Math.sqrt(varT * varX);
        return r * r > 0.9995;
    }

    private boolean repeatedLikeHistory(List<double[]> points, String sessionKey) {
        Deque<long[]> queue = history.get(sessionKey);
        if (queue == null || sessionKey == null) {
            return false;
        }
        long[] current = signature(points);
        List<long[]> past;
        synchronized (queue) {
            past = new ArrayList<>(queue);
        }
        for (long[] previous : past) {
            if (similar(previous, current)) {
                log.debug("检测到与历史轨迹高度相似：{}", sessionKey);
                return true;
            }
        }
        return false;
    }

    /** 把轨迹归一化成 8 个等时间片，量化成整数，方便做相似比较。 */
    private long[] signature(List<double[]> points) {
        int buckets = 8;
        long[] signature = new long[buckets];
        double end = points.get(points.size() - 1)[1];
        if (end <= 0) {
            return signature;
        }
        int cursor = 0;
        for (int i = 0; i < buckets; i++) {
            double target = end * (i + 1) / buckets;
            while (cursor < points.size() - 1 && points.get(cursor)[1] < target) {
                cursor++;
            }
            signature[i] = Math.round(points.get(cursor)[0] * 1000);
        }
        return signature;
    }

    private boolean similar(long[] a, long[] b) {
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 15) { // 1.5% 偏差内视为同一条
                return false;
            }
        }
        return true;
    }

}
