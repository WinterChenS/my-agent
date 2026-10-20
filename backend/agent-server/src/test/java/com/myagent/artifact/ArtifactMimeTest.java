package com.myagent.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import com.myagent.artifact.service.ArtifactMime;
import org.junit.jupiter.api.Test;

/**
 * 「扩展名 → MIME」只有一份表；图片类型必须被认出来（否则既不显示也不可预览）。
 *
 * <p>此前交付路径与扫描路径各有一份，且已经不一致（前者认 webp/svg、后者不认）——
 * 结果是 Agent 写出的 svg/webp 图片在扫描路径下变成 application/octet-stream。
 */
class ArtifactMimeTest {

    @Test
    void 图片类型都归到image() {
        assertThat(ArtifactMime.guess("a.png")).isEqualTo("image/png");
        assertThat(ArtifactMime.guess("a.JPG")).isEqualTo("image/jpeg");
        assertThat(ArtifactMime.guess("a.jpeg")).isEqualTo("image/jpeg");
        assertThat(ArtifactMime.guess("a.gif")).isEqualTo("image/gif");
        assertThat(ArtifactMime.guess("a.webp")).isEqualTo("image/webp");
        assertThat(ArtifactMime.guess("a.svg")).isEqualTo("image/svg+xml");
    }

    @Test
    void 文本与二进制的兜底不变() {
        assertThat(ArtifactMime.guess("a.md")).isEqualTo("text/markdown");
        assertThat(ArtifactMime.guess("a.csv")).isEqualTo("text/csv");
        assertThat(ArtifactMime.guess("a.json")).isEqualTo("application/json");
        assertThat(ArtifactMime.guess("a.zip")).isEqualTo("application/zip");
        assertThat(ArtifactMime.guess("a.unknown")).isEqualTo("application/octet-stream");
        assertThat(ArtifactMime.guess(null)).isEqualTo("application/octet-stream");
        assertThat(ArtifactMime.guess("noext")).isEqualTo("application/octet-stream");
    }
}
