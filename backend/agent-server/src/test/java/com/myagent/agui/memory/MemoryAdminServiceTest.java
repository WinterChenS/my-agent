package com.myagent.agui.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myagent.common.BizException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「记忆可视化」服务：列 / 读 / 改 / 删。
 *
 * <p>MEMORY.md 走临时工作区（不碰真实文件），每日台账走真实测试库里的官方 {@link PgBaseStore}。
 * 用独立的 userId 避免影响别人的记忆数据。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MemoryAdminServiceTest {

    @Autowired
    private PgBaseStore baseStore;

    @TempDir
    Path workspaceRoot;

    private MemoryAdminService service;

    /** 每次用不同 userId，测试之间互不干扰。 */
    private final Long userId = 900_000L + (System.nanoTime() % 100_000);

    @BeforeEach
    void setUp() {
        service = new MemoryAdminService(baseStore, workspaceRoot);
    }

    @Test
    void 台账写进去能读回来_并出现在清单里() {
        service.save(userId, "2026-10-20.md", "# 今天\n- 用户喜欢简洁回答\n");

        MemoryDocument read = service.read(userId, "2026-10-20.md");
        assertThat(read.content()).contains("用户喜欢简洁回答");
        assertThat(read.kind()).isEqualTo(MemoryDocument.KIND_DAILY);

        assertThat(service.list(userId))
                .extracting(MemoryDocument::name)
                .contains("2026-10-20.md");
        // 列表不带正文（省流量，也避免列表接口把整份记忆抖出去）
        assertThat(service.list(userId)).allMatch(doc -> doc.content() == null);
    }

    @Test
    void 台账按日期倒序_MEMORY在清单里排最前() throws Exception {
        service.save(userId, "2026-10-18.md", "a");
        service.save(userId, "2026-10-20.md", "c");
        service.save(userId, "2026-10-19.md", "b");
        Path curated = workspaceRoot.resolve(String.valueOf(userId)).resolve(MemoryDocument.CURATED_NAME);
        Files.createDirectories(curated.getParent());
        Files.writeString(curated, "# MEMORY.md\n- 长期事实\n", StandardCharsets.UTF_8);

        assertThat(service.list(userId))
                .extracting(MemoryDocument::name)
                .containsExactly(
                        MemoryDocument.CURATED_NAME, "2026-10-20.md", "2026-10-19.md", "2026-10-18.md");
    }

    @Test
    void 覆盖写MEMORY_写进库且读得到新内容() {
        service.save(userId, MemoryDocument.CURATED_NAME, "# MEMORY.md\n- 第一版\n");
        assertThat(service.read(userId, MemoryDocument.CURATED_NAME).content()).contains("第一版");

        service.save(userId, MemoryDocument.CURATED_NAME, "# MEMORY.md\n- 第二版\n");
        MemoryDocument read = service.read(userId, MemoryDocument.CURATED_NAME);
        assertThat(read.content()).contains("第二版").doesNotContain("第一版");
        assertThat(read.sizeBytes()).isPositive();
        assertThat(read.updatedAt()).isNotNull();
        // 长期记忆存库、不落磁盘（以前是写 <workspace>/<userId>/MEMORY.md）
        assertThat(Files.exists(workspaceRoot.resolve(String.valueOf(userId)).resolve("MEMORY.md")))
                .as("MEMORY.md 不应该再写到磁盘")
                .isFalse();
    }

    @Test
    void 删台账可以_删MEMORY被拒() {
        service.save(userId, "2026-10-20.md", "x");
        service.delete(userId, "2026-10-20.md");
        assertThat(service.list(userId)).extracting(MemoryDocument::name).doesNotContain("2026-10-20.md");

        assertThatThrownBy(() -> service.delete(userId, MemoryDocument.CURATED_NAME))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能删除");
    }

    @Test
    void 文件名只认日期或MEMORY_挡住路径穿越() {
        assertThatThrownBy(() -> service.read(userId, "../secret.md")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.save(userId, "../../etc/passwd", "x"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.save(userId, "notes.md", "x")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.save(userId, "2026-13-99.md", "x"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void 读不存在的记忆报404语义() {
        assertThatThrownBy(() -> service.read(userId, "2026-01-01.md"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有这份记忆");
    }

    @Test
    void 保存时保留原有created_at并刷新modified_at() {
        service.save(userId, "2026-10-20.md", "v1");
        String key = MemoryAdminService.dailyKey("2026-10-20.md");
        String createdBefore = String.valueOf(
                baseStore.get(MemoryAdminService.namespace(userId), key).value().get("created_at"));

        service.save(userId, "2026-10-20.md", "v2");
        var payload = baseStore.get(MemoryAdminService.namespace(userId), key).value();
        assertThat(payload.get("content")).isEqualTo("v2");
        assertThat(String.valueOf(payload.get("created_at"))).isEqualTo(createdBefore);
        assertThat(payload.get("encoding")).isEqualTo("utf-8");
        assertThat(List.of("content", "encoding", "created_at", "modified_at"))
                .containsAll(payload.keySet());
    }
}
