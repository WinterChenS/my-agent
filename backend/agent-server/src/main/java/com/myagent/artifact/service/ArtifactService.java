package com.myagent.artifact.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.myagent.agui.AguiProperties;
import com.myagent.artifact.config.MinioProperties;
import com.myagent.artifact.dto.ArtifactResponse;
import com.myagent.artifact.entity.Artifact;
import com.myagent.artifact.mapper.ArtifactMapper;
import com.myagent.artifact.storage.MinioStorageService;
import com.myagent.chat.entity.ChatSession;
import com.myagent.chat.mapper.ChatSessionMapper;
import com.myagent.common.BizException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 产物与来源文件服务（技术方案 §12）。
 *
 * <p>职责：存储路由（文本类入库 / 其余进 MinIO）、同一会话内 sha256 去重、上传物化到会话工作区、
 * 以及按会话列出 / 下载 / 删除。
 *
 * <p>MinIO 不可用时不得影响对话主链路：失败只把该条记录标记为 FAILED 并给出原因。
 */
@Service
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    public static final String ROLE_SOURCE = "SOURCE";
    public static final String ROLE_ARTIFACT = "ARTIFACT";

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "md", "markdown", "txt", "text", "log", "json", "jsonl", "csv", "tsv", "xml", "yaml", "yml",
            "html", "htm", "css", "js", "ts", "tsx", "jsx", "py", "java", "sh", "sql", "properties",
            "ini", "conf", "cfg", "toml", "env", "gitignore", "dockerfile", "makefile");

    private final ArtifactMapper artifactMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final MinioStorageService minio;
    private final MinioProperties minioProperties;
    private final AguiProperties aguiProperties;

    public ArtifactService(
            ArtifactMapper artifactMapper,
            ChatSessionMapper chatSessionMapper,
            MinioStorageService minio,
            MinioProperties minioProperties,
            AguiProperties aguiProperties) {
        this.artifactMapper = artifactMapper;
        this.chatSessionMapper = chatSessionMapper;
        this.minio = minio;
        this.minioProperties = minioProperties;
        this.aguiProperties = aguiProperties;
    }

    // ===================== 查询 =====================

    public List<ArtifactResponse> list(Long userId, Long sessionId) {
        requireOwned(userId, sessionId);
        return artifactMapper
                .selectList(Wrappers.<Artifact>lambdaQuery()
                        .eq(Artifact::getSessionId, sessionId)
                        .orderByAsc(Artifact::getRole)
                        .orderByDesc(Artifact::getId))
                .stream()
                .map(ArtifactService::toResponse)
                .toList();
    }

    /** 下载：MINIO 返回直链；DB 直接返回正文。 */
    public DownloadResult download(Long userId, Long artifactId) {
        Artifact artifact = requireOwnedArtifact(userId, artifactId);
        if ("DB".equals(artifact.getStorage())) {
            byte[] bytes = (artifact.getContent() == null ? "" : artifact.getContent())
                    .getBytes(StandardCharsets.UTF_8);
            return new DownloadResult(null, bytes, artifact.getFileName(), artifact.getMime());
        }
        String url = minio.presignedUrl(artifact.getMinioObjectKey());
        if (url == null) {
            // 直链生成失败时退化为后端代理读取，保证仍能下载
            try {
                return new DownloadResult(
                        null,
                        minio.get(artifact.getMinioObjectKey()),
                        artifact.getFileName(),
                        artifact.getMime());
            } catch (Exception ex) {
                throw BizException.badRequest("下载失败: " + ex.getMessage());
            }
        }
        return new DownloadResult(url, null, artifact.getFileName(), artifact.getMime());
    }

    @Transactional
    public void delete(Long userId, Long artifactId) {
        Artifact artifact = requireOwnedArtifact(userId, artifactId);
        if ("MINIO".equals(artifact.getStorage()) && StringUtils.hasText(artifact.getMinioObjectKey())) {
            minio.remove(artifact.getMinioObjectKey());
        }
        artifactMapper.deleteById(artifactId);
    }

    // ===================== 写入 =====================

    /** 处理聊天上传：路由存储 + 去重 + 物化到会话工作区。 */
    /**
     * 直接取产物字节（不看存储位置）。
     *
     * <p>为什么需要：{@link #download} 对 MinIO 的产物只返回**预签名 URL**（bytes 为 null），
     * 而 IM 渠道要把文件原样上传给钉钉/飞书，必须拿到真实字节。
     * 实测踩过：直接用 download() 发图，bytes 为 null 被静默跳过 —— 用户看到"发了张图但没收到"。
     */
    /** 取产物的文件名（IM 渠道发送媒体时要用它判断图片/文件）。 */
    public String fileNameOf(Long userId, Long artifactId) {
        return requireOwnedArtifact(userId, artifactId).getFileName();
    }

    public byte[] contentBytes(Long userId, Long artifactId) {
        Artifact artifact = requireOwnedArtifact(userId, artifactId);
        if ("DB".equals(artifact.getStorage())) {
            return (artifact.getContent() == null ? "" : artifact.getContent())
                    .getBytes(StandardCharsets.UTF_8);
        }
        try {
            return minio.get(artifact.getMinioObjectKey());
        } catch (Exception ex) {
            throw BizException.badRequest("读取产物内容失败: " + ex.getMessage());
        }
    }

    public ArtifactResponse upload(Long userId, Long sessionId, MultipartFile file) {
        ChatSession session = requireOwned(userId, sessionId);
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("上传文件为空");
        }
        if (file.getSize() > minio.maxFileSizeBytes()) {
            throw BizException.badRequest(
                    "文件超过上限 " + minioProperties.maxFileSizeMb() + "MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw BizException.badRequest("读取上传内容失败: " + ex.getMessage());
        }
        Artifact artifact = store(
                userId, sessionId, ROLE_SOURCE, safeFileName(file.getOriginalFilename()),
                file.getContentType(), bytes, true);
        if ("FAILED".equals(artifact.getStatus())) {
            // 存储失败必须让调用方明确知道：若返回 200 + FAILED，前端会当成上传成功（实测踩过）。
            // 失败记录已由 store() 提交，保留下来便于排查。
            throw BizException.badRequest("上传失败：" + artifact.getErrorMessage());
        }
        // owner 用 userId：Agent 的实际工作目录是 <workspaceRoot>/<userId>
        materialize(String.valueOf(userId), bytes, artifact.getFileName());
        return toResponse(artifact);
    }

    /**
     * 核心写入：按 D18 路由（文本类入库 / 其余进 MinIO），同会话 sha256 去重。
     *
     * @param materializeToWorkspace 是否额外落一份到会话工作区（上传场景需要，产物场景不需要）
     */
    @Transactional
    public Artifact store(
            Long userId,
            Long sessionId,
            String role,
            String fileName,
            String mime,
            byte[] bytes,
            boolean materializeToWorkspace) {
        return store(userId, sessionId, role, fileName, mime, bytes, materializeToWorkspace, null);
    }

    /**
     * 登记产物，并记下「属于哪一轮」。
     *
     * @param runId 产出它的那一轮 run id（Agent 主动交付时平台传入；上传/扫描等旧路径为 null）
     */
    public Artifact store(
            Long userId,
            Long sessionId,
            String role,
            String fileName,
            String mime,
            byte[] bytes,
            boolean materializeToWorkspace,
            String runId) {
        String sha = sha256(bytes);
        Optional<Artifact> existing = artifactMapper
                .selectList(Wrappers.<Artifact>lambdaQuery()
                        .eq(Artifact::getSessionId, sessionId)
                        .eq(Artifact::getSha256, sha)
                        .eq(Artifact::getRole, role))
                .stream()
                .findFirst();
        if (existing.isPresent() && java.util.Objects.equals(existing.get().getRunId(), runId)) {
            return existing.get(); // 同一轮里同内容重复交付：只留一条
        }
        /*
         * 内容相同但**不是同一轮**：要新建一条。
         *
         * 实测问题（用户反馈"再发一份给我，气泡下面没有产物"）：模型第二轮又调了一次
         * deliverArtifact，内容与上一轮完全一样 → 以前命中"同会话同内容不重复登记"直接返回旧记录，
         * 那条的 runId 还是上一轮的 → 本轮气泡下自然看不到。产物是按 runId 挂到气泡下面的，
         * 所以"每轮的交付"必须是独立记录。
         */

        Artifact artifact = new Artifact();
        artifact.setSessionId(sessionId);
        artifact.setUserId(userId);
        artifact.setRole(role);
        artifact.setFileName(fileName);
        artifact.setMime(mime);
        artifact.setSizeBytes((long) bytes.length);
        artifact.setSha256(sha);
        artifact.setRunId(runId);

        // 路由还要看内容：MIME/扩展名说是文本，但内容可能带 NUL 或不是合法 UTF-8
        // （实测踩过：UTF-16 的 .md 或扩展名是文本但内容是二进制 → 插进 PG 的 text 列直接报
        //  invalid byte sequence for encoding "UTF8": 0x00，接口 500）。这类内容改走 MinIO。
        boolean textual = isTextual(mime, fileName);
        if (textual && !isStorableText(bytes)) {
            log.warn("内容不是可入库的 UTF-8 文本（可能是 UTF-16/二进制），改走 MinIO: fileName={} size={}",
                    fileName, bytes.length);
            textual = false;
        }
        if (textual) {
            artifact.setStorage("DB");
            artifact.setContent(new String(bytes, StandardCharsets.UTF_8));
            artifact.setStatus("READY");
        } else {
            artifact.setStorage("MINIO");
            String objectKey = buildObjectKey(userId, sessionId, sha, fileName);
            try {
                minio.put(objectKey, bytes, mime);
                artifact.setMinioBucket(minio.bucket());
                artifact.setMinioObjectKey(objectKey);
                artifact.setStatus("READY");
            } catch (Exception ex) {
                // 产物失败不能影响对话：记 FAILED + 原因
                log.warn("写入 MinIO 失败 fileName={}: {}", fileName, ex.toString());
                artifact.setStatus("FAILED");
                artifact.setErrorMessage(truncate(ex.getMessage(), 1000));
            }
        }
        artifactMapper.insert(artifact);
        if (materializeToWorkspace) {
            // 由 upload() 负责，避免 store() 被多处调用时重复物化
            log.debug("artifact {} 待物化到工作区", artifact.getId());
        }
        return artifact;
    }

    /**
     * 把上传内容写到 Agent 实际工作目录下的 uploads/，供 Agent 用常规工具读取（D20）。
     *
     * <p>实测 HarnessAgent 的工作目录是 {@code <workspaceRoot>/<owner>}（owner 为调用方身份，
     * 通常是 userId），不是按 threadId 分目录；所以必须按 owner 落盘，否则 Agent 用相对路径读不到。
     */
    public void materialize(String owner, byte[] bytes, String fileName) {
        if (!StringUtils.hasText(owner)) {
            return;
        }
        try {
            Path dir = Path.of(aguiProperties.workspaceRoot(), owner, "uploads");
            Files.createDirectories(dir);
            Files.write(dir.resolve(safeFileName(fileName)), bytes);
        } catch (Exception ex) {
            log.warn("物化上传文件到工作区失败 owner={} file={}: {}", owner, fileName, ex.toString());
        }
    }

    // ===================== 辅助 =====================

    private static String buildObjectKey(Long userId, Long sessionId, String sha, String fileName) {
        return "artifacts/" + userId + "/" + sessionId + "/" + sha.substring(0, 8) + "-" + fileName;
    }

    /** 内容是否适合存进数据库的 text 列：合法 UTF-8 且不含 NUL（PG 的 text/jsonb 都不接受 NUL）。 */
    public static boolean isStorableText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return true;
        }
        for (byte b : bytes) {
            if (b == 0) {
                return false;
            }
        }
        java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (java.nio.charset.CharacterCodingException ex) {
            return false;
        }
    }

    /** 文本类判定：MIME 优先，其次扩展名（D18）。 */
    static boolean isTextual(String mime, String fileName) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.startsWith("text/")) {
            return true;
        }
        if (m.contains("json") || m.contains("xml") || m.contains("yaml") || m.contains("csv")
                || m.contains("javascript") || m.contains("x-sh") || m.contains("x-python")) {
            return true;
        }
        String ext = extension(fileName);
        return TEXT_EXTENSIONS.contains(ext);
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 去掉路径分隔符与 ..，避免路径穿越；限制长度。 */
    public static String safeFileName(String name) {
        if (!StringUtils.hasText(name)) {
            return "unnamed";
        }
        String cleaned = name.replace("\\", "/");
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        cleaned = cleaned.replace("..", "_").trim();
        if (cleaned.isEmpty()) {
            cleaned = "unnamed";
        }
        return cleaned.length() > 200 ? cleaned.substring(cleaned.length() - 200) : cleaned;
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private ChatSession requireOwned(Long userId, Long sessionId) {
        ChatSession session = sessionId == null ? null : chatSessionMapper.selectById(sessionId);
        if (session == null) {
            throw BizException.notFound("会话不存在: " + sessionId);
        }
        if (!session.getUserId().equals(userId)) {
            throw BizException.forbidden("无权访问他人会话的产物");
        }
        return session;
    }

    private Artifact requireOwnedArtifact(Long userId, Long artifactId) {
        Artifact artifact = artifactId == null ? null : artifactMapper.selectById(artifactId);
        if (artifact == null) {
            throw BizException.notFound("产物不存在: " + artifactId);
        }
        if (!artifact.getUserId().equals(userId)) {
            throw BizException.forbidden("无权访问他人产物");
        }
        return artifact;
    }

    public static ArtifactResponse toResponse(Artifact a) {
        return new ArtifactResponse(
                a.getId(),
                a.getSessionId(),
                a.getRole(),
                a.getStorage(),
                a.getFileName(),
                a.getMime(),
                a.getSizeBytes(),
                a.getSha256(),
                a.getStatus(),
                a.getErrorMessage(),
                "DB".equals(a.getStorage()),
                "DB".equals(a.getStorage()) ? a.getContent() : null,
                a.getRunId(),
                a.getCreatedAt());
    }

    /**
     * 下载结果：二选一——直链（{@code url}）或后端直接返回的字节（{@code bytes}）。
     */
    public record DownloadResult(String url, byte[] bytes, String fileName, String mime) {}
}
