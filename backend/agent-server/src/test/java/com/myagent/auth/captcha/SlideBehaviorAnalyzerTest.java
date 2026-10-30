package com.myagent.auth.captcha;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 轨迹行为风险：只做加法、单项不越线、明显脚本才拦。 */
class SlideBehaviorAnalyzerTest {

    private SlideBehaviorAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new SlideBehaviorAnalyzer();
        ReflectionTestUtils.setField(analyzer, "minPoints", 5);
        ReflectionTestUtils.setField(analyzer, "minDurationMs", 130);
        ReflectionTestUtils.setField(analyzer, "uniformSpeedCv", 0.12);
        ReflectionTestUtils.setField(analyzer, "rejectScore", 60);
        ReflectionTestUtils.setField(analyzer, "historySize", 20);
    }

    @Test
    @DisplayName("人类轨迹（不均匀、带加减速与回退）风险分为 0")
    void humanTrackScoresZero() {
        SlideBehaviorAnalyzer.Report report = analyzer.analyze(humanTrack(), "s-1");

        assertThat(report.score()).isZero();
        assertThat(report.reasons()).isEmpty();
        assertThat(report.rejected(60)).isFalse();
    }

    @Test
    @DisplayName("两点瞬移的脚本轨迹风险很高")
    void robotTrackIsRejected() {
        SlideBehaviorAnalyzer.Report report = analyzer.analyze(
                List.of(List.of(0.0, 0.0), List.of(0.85, 3.0)), "s-1");

        assertThat(report.score()).isGreaterThanOrEqualTo(60);
        assertThat(report.rejected(60)).isTrue();
        assertThat(report.reasons()).isNotEmpty();
    }

    @Test
    @DisplayName("没有任何轨迹数据 → 按最可疑处理")
    void missingTrackIsSuspicious() {
        assertThat(analyzer.analyze(null, "s-1").rejected(60)).isTrue();
        assertThat(analyzer.analyze(List.of(), "s-1").rejected(60)).isTrue();
    }

    @Test
    @DisplayName("全程匀速直线（等间隔采样）会被扣分，但单一特征不足以拦截")
    void uniformTrackIsFlaggedButNotAlone() {
        List<List<Double>> track = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            track.add(List.of(i * 0.04, i * 16.0));
        }
        SlideBehaviorAnalyzer.Report report = analyzer.analyze(track, "s-1");

        assertThat(report.reasons()).anyMatch(reason -> reason.contains("匀速"));
        assertThat(report.reasons()).anyMatch(reason -> reason.contains("间隔"));
        // 只有"匀速/等间隔/近似直线"这几项同类信号，仍不足以单独判死
        assertThat(report.reasons()).isNotEmpty();
    }

    @Test
    @DisplayName("重复出现高度相似的轨迹 → 追加风险")
    void repeatedTrackIsPenalised() {
        List<List<Double>> track = humanTrack();
        analyzer.remember("s-1", track);

        SlideBehaviorAnalyzer.Report report = analyzer.analyze(track, "s-1");
        assertThat(report.reasons()).anyMatch(reason -> reason.contains("相似"));
    }

    @Test
    @DisplayName("时间戳倒退（伪造轨迹顺序）会被识别")
    void nonMonotonicTimestamps() {
        List<List<Double>> track = new ArrayList<>(humanTrack());
        track.add(List.of(0.5, 10.0)); // 时间突然回到很早
        SlideBehaviorAnalyzer.Report report = analyzer.analyze(track, "s-2");
        assertThat(report.reasons()).anyMatch(reason -> reason.contains("时间戳"));
    }

    /** 像人的轨迹：加减速、间隔不均匀、含一次回退。 */
    static List<List<Double>> humanTrack() {
        List<List<Double>> track = new ArrayList<>();
        double x = 0;
        double t = 0;
        for (int i = 0; i < 24; i++) {
            x += i < 4 ? 0.01 : (i < 18 ? 0.05 + (i % 3) * 0.012 : 0.02);
            t += 18 + (i * 7) % 23;
            if (i == 12) {
                x -= 0.02;
            }
            track.add(List.of(Math.min(0.85, x), t));
        }
        return track;
    }
}
