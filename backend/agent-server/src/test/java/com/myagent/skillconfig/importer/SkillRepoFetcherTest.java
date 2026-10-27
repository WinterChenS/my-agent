package com.myagent.skillconfig.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myagent.common.BizException;
import org.junit.jupiter.api.Test;

/** 仓库链接归一化：GitHub / GitLab / .zip 直链 / 非法输入。全部离线，不发请求。 */
class SkillRepoFetcherTest {

    @Test
    void github仓库页转成codeload归档地址() {
        SkillRepoFetcher.Resolved resolved =
                SkillRepoFetcher.resolve("https://github.com/anthropics/skills", null);
        assertThat(resolved.downloadUrl())
                .isEqualTo("https://codeload.github.com/anthropics/skills/zip/HEAD");
        assertThat(resolved.ref()).isEqualTo("HEAD");
    }

    @Test
    void github链接里的分支优先_显式ref最优先() {
        SkillRepoFetcher.Resolved byLink =
                SkillRepoFetcher.resolve("https://github.com/owner/repo/tree/dev", null);
        assertThat(byLink.ref()).isEqualTo("dev");
        assertThat(byLink.downloadUrl()).endsWith("/zip/dev");

        SkillRepoFetcher.Resolved explicit =
                SkillRepoFetcher.resolve("https://github.com/owner/repo/tree/dev", "v1.2.0");
        assertThat(explicit.ref()).isEqualTo("v1.2.0");
        assertThat(explicit.downloadUrl()).isEqualTo("https://codeload.github.com/owner/repo/zip/v1.2.0");
    }

    @Test
    void gitlab仓库转成archive地址() {
        SkillRepoFetcher.Resolved resolved =
                SkillRepoFetcher.resolve("https://gitlab.com/group/sub/my-skill", "main");
        assertThat(resolved.downloadUrl())
                .isEqualTo("https://gitlab.com/group/sub/my-skill/-/archive/main/my-skill-main.zip");
    }

    @Test
    void 归档直链原样使用_zip与tar_gz与skill都认() {
        for (String link : new String[] {
            "https://example.com/artifacts/skill.zip",
            "https://example.com/artifacts/skill.tar.gz",
            "https://example.com/artifacts/skill.tgz",
            "https://example.com/artifacts/skill.skill"
        }) {
            SkillRepoFetcher.Resolved resolved = SkillRepoFetcher.resolve(link, null);
            assertThat(resolved.downloadUrl()).isEqualTo(link);
            assertThat(resolved.ref()).isEqualTo("archive");
            assertThat(resolved.subPath()).isEmpty();
        }
    }

    @Test
    void 指向仓库里某个技能目录时只装该子目录() {
        SkillRepoFetcher.Resolved resolved = SkillRepoFetcher.resolve(
                "https://github.com/anthropics/skills/tree/main/skills/pdf", null);
        assertThat(resolved.downloadUrl()).isEqualTo("https://codeload.github.com/anthropics/skills/zip/main");
        assertThat(resolved.ref()).isEqualTo("main");
        assertThat(resolved.subPath()).isEqualTo("skills/pdf");
    }

    @Test
    void 指向SKILL_md文件时只装它所在目录() {
        SkillRepoFetcher.Resolved resolved = SkillRepoFetcher.resolve(
                "https://github.com/anthropics/skills/blob/main/skills/pdf/SKILL.md", null);
        assertThat(resolved.subPath()).isEqualTo("skills/pdf");
    }

    @Test
    void gitlab子目录链接同样支持() {
        SkillRepoFetcher.Resolved resolved = SkillRepoFetcher.resolve(
                "https://gitlab.com/group/repo/-/tree/main/skills/pdf", null);
        assertThat(resolved.ref()).isEqualTo("main");
        assertThat(resolved.subPath()).isEqualTo("skills/pdf");
    }

    @Test
    void 非http链接与不支持的形式报业务错误() {
        assertThatThrownBy(() -> SkillRepoFetcher.resolve("", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("链接");
        assertThatThrownBy(() -> SkillRepoFetcher.resolve("ftp://example.com/x.zip", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("http");
        assertThatThrownBy(() -> SkillRepoFetcher.resolve("https://example.com/some/page", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("暂不支持");
    }

    @Test
    void 云元数据地址被拒绝() {
        assertThat(SkillRepoFetcher.isBlockedHost("169.254.169.254")).isTrue();
        assertThat(SkillRepoFetcher.isBlockedHost("metadata.google.internal")).isTrue();
        assertThat(SkillRepoFetcher.isBlockedHost("github.com")).isFalse();
        assertThat(SkillRepoFetcher.isBlockedHost(null)).isTrue();
    }
}
