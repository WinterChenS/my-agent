package com.myagent.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.myagent.artifact.entity.Artifact;
import com.myagent.artifact.mapper.ArtifactMapper;
import com.myagent.artifact.service.ArtifactService;
import com.myagent.artifact.storage.MinioStorageService;
import com.myagent.agentconfig.dto.AgentRequest;
import com.myagent.agentconfig.service.AgentConfigService;
import com.myagent.auth.entity.SysUser;
import com.myagent.auth.mapper.SysUserMapper;
import com.myagent.chat.dto.SessionRequest;
import com.myagent.chat.dto.SessionResponse;
import com.myagent.chat.service.ChatSessionService;
import com.myagent.common.BizException;
import com.myagent.modelconfig.dto.ModelRequest;
import com.myagent.modelconfig.dto.ProviderRequest;
import com.myagent.modelconfig.service.ModelConfigService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 产物存储路由与权限（Task 10.1 门禁）。
 *
 * <p>文本类入 DB、二进制类入 MinIO；同会话 sha256 去重；非属主 403。
 * MinIO 相关用例会真实写对象，结束时通过 {@code delete} 清掉。
 */
@SpringBootTest
@ActiveProfiles("test")
class ArtifactServiceTest {

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private ArtifactMapper artifactMapper;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private AgentConfigService agentConfigService;

    @Autowired
    private ModelConfigService modelConfigService;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private MinioStorageService minio;

    /** 造一个属于 admin 的会话。 */
    private SessionResponse newSession() {
        Long providerId = modelConfigService
                .createProvider(new ProviderRequest(
                        "ArtTest-Provider-" + System.nanoTime(), "OPENAI_COMPATIBLE",
                        "http://localhost:8099/v1", "k", "ENABLED"))
                .id();
        Long modelId = modelConfigService
                .createModel(new ModelRequest(providerId, "art-test-model", null, null, null, null, null, null, null, null, null, null, "ENABLED", null))
                .id();
        var agent = agentConfigService.create(new AgentRequest(
                "ArtTest-Agent-" + System.nanoTime(), "art-test-agent-" + System.nanoTime(),
                "p", modelId, null, null, "ALWAYS_ALLOW", null,null,null,null, "ENABLED", List.of(), List.of()));
        return chatSessionService.create(1L, new SessionRequest(agent.id(), "产物测试", null));
    }

    private Long adminId() {
        SysUser admin = sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, "admin"));
        return admin.getId();
    }

    @Test
    @Transactional
    void 文本类文件走数据库存储() {
        SessionResponse session = newSession();
        byte[] bytes = "# 标题\n正文".getBytes(StandardCharsets.UTF_8);

        Artifact stored = artifactService.store(
                adminId(), session.id(), ArtifactService.ROLE_SOURCE, "note.md", "text/markdown", bytes, false);

        assertThat(stored.getStorage()).isEqualTo("DB");
        assertThat(stored.getStatus()).isEqualTo("READY");
        assertThat(stored.getContent()).contains("# 标题");
        assertThat(stored.getSha256()).hasSize(64);

        // 走 HTTP 语义的下载：DB 存储直接返回正文
        var download = artifactService.download(adminId(), stored.getId());
        assertThat(download.url()).isNull();
        assertThat(new String(download.bytes(), StandardCharsets.UTF_8)).isEqualTo("# 标题\n正文");
    }

    @Test
    @Transactional
    void 文本扩展名但内容带NUL时改走MinIO而不是报500() {
        // 用户实测：上传 .md/.txt 但内容是 UTF-16（含 0x00）→ 原先插 PG 的 text 列直接
        // invalid byte sequence for encoding "UTF8": 0x00，接口 500。现在应改走 MinIO。
        SessionResponse session = newSession();
        byte[] utf16 = "# 标题".getBytes(StandardCharsets.UTF_16); // BOM + 0x00 字节
        assertThat(utf16).contains((byte) 0);

        Artifact stored = artifactService.store(
                adminId(), session.id(), ArtifactService.ROLE_SOURCE, "note.md", "text/markdown", utf16, false);

        assertThat(stored.getStorage()).isEqualTo("MINIO");
        assertThat(stored.getStatus()).isEqualTo("READY");
        assertThat(stored.getContent()).isNull();
        // MINIO 存储的下载返回预签名直链（字节流只用于 DB 存储）
        var download = artifactService.download(adminId(), stored.getId());
        assertThat(download.url()).isNotNull();
        assertThat(download.bytes()).isNull();
    }

    @Test
    @Transactional
    void 非法UTF8字节也改走MinIO() {
        SessionResponse session = newSession();
        byte[] invalidUtf8 = {(byte) 0xC3, (byte) 0x28, (byte) 0xA0, (byte) 0xA1};

        Artifact stored = artifactService.store(
                adminId(), session.id(), ArtifactService.ROLE_SOURCE, "weird.txt", "text/plain", invalidUtf8, false);

        assertThat(stored.getStorage()).isEqualTo("MINIO");
        assertThat(stored.getContent()).isNull();
    }

    @Test
    void 内容入库判定只接受合法且无NUL的UTF8() {
        assertThat(ArtifactService.isStorableText("中文 ok".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(ArtifactService.isStorableText(new byte[0])).isTrue();
        assertThat(ArtifactService.isStorableText(new byte[] {(byte) 0xE4, (byte) 0xB8}))
                .as("截断的多字节序列不是合法 UTF-8")
                .isFalse();
        assertThat(ArtifactService.isStorableText(new byte[] {'a', 0, 'b'})).as("含 NUL").isFalse();
        assertThat(ArtifactService.isStorableText("# 标题".getBytes(StandardCharsets.UTF_16)))
                .as("UTF-16 带 NUL")
                .isFalse();
    }

    @Test
    @Transactional
    void 二进制文件走MinIO且可读回_并清理() {
        SessionResponse session = newSession();
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
        Artifact stored = null;
        try {
            stored = artifactService.store(
                    adminId(), session.id(), ArtifactService.ROLE_ARTIFACT, "pic.png", "image/png", png, false);

            assertThat(stored.getStorage()).isEqualTo("MINIO");
            assertThat(stored.getStatus()).isEqualTo("READY");
            assertThat(stored.getMinioBucket()).isEqualTo(minio.bucket());
            assertThat(stored.getMinioObjectKey()).contains("artifacts/");

            // 对象确实存在于 bucket（直接读回比对）
            byte[] read = minio.get(stored.getMinioObjectKey());
            assertThat(read).isEqualTo(png);

            // 下载返回签名直链
            var download = artifactService.download(adminId(), stored.getId());
            assertThat(download.url()).isNotNull().contains(minio.bucket());
        } catch (Exception ex) {
            throw new AssertionError("MinIO 交互失败: " + ex.getMessage(), ex);
        } finally {
            // 事务会回滚 DB 行，但 MinIO 对象不会——这里显式删掉，避免测试污染共享桶
            if (stored != null && stored.getMinioObjectKey() != null) {
                minio.remove(stored.getMinioObjectKey());
            }
        }
    }

    @Test
    @Transactional
    void 同会话同内容不重复登记() {
        SessionResponse session = newSession();
        byte[] bytes = "same-content".getBytes(StandardCharsets.UTF_8);

        Artifact a1 = artifactService.store(
                adminId(), session.id(), ArtifactService.ROLE_SOURCE, "a.txt", "text/plain", bytes, false);
        Artifact a2 = artifactService.store(
                adminId(), session.id(), ArtifactService.ROLE_SOURCE, "b.txt", "text/plain", bytes, false);

        assertThat(a2.getId()).isEqualTo(a1.getId());
        Long count = artifactMapper.selectCount(Wrappers.<Artifact>lambdaQuery()
                .eq(Artifact::getSessionId, session.id())
                .eq(Artifact::getSha256, a1.getSha256()));
        assertThat(count).isEqualTo(1);
    }

    @Test
    @Transactional
    void 非属主访问他人会话产物被拒() {
        SessionResponse session = newSession();
        Long otherUserId = adminId() + 999_999L;

        assertThatThrownBy(() -> artifactService.list(otherUserId, session.id()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权");
    }

    @Test
    @Transactional
    void 上传大小超限返回业务错误() {
        SessionResponse session = newSession();
        long tooBig = minio.maxFileSizeBytes() + 1;
        MockMultipartFile file = new MockMultipartFile("file", "big.bin", "application/octet-stream", new byte[1024]) {
            @Override
            public long getSize() {
                return tooBig;
            }
        };
        assertThatThrownBy(() -> artifactService.upload(adminId(), session.id(), file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("上限");
    }
}
