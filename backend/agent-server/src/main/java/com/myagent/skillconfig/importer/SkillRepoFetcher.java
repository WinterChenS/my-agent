package com.myagent.skillconfig.importer;

import com.myagent.common.BizException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 把「Skill 仓库链接」变成归档字节流（zip / tar.gz / tar 由 {@link SkillArchive} 嗅探）。
 *
 * <p>支持的链接形态（够用且明确，不做万能爬虫）：
 *
 * <table border="1">
 *   <caption>支持形态</caption>
 *   <tr><th>输入</th><th>下载</th><th>只装</th></tr>
 *   <tr><td>{@code https://github.com/owner/repo}</td><td>{@code codeload.../zip/HEAD}</td><td>仓库里的全部技能</td></tr>
 *   <tr><td>{@code https://github.com/owner/repo/tree/v1.2}</td><td>{@code .../zip/v1.2}</td><td>全部</td></tr>
 *   <tr><td>{@code https://github.com/owner/repo/tree/main/skills/pdf}</td><td>{@code .../zip/main}</td>
 *       <td>只装 {@code skills/pdf}（"我就想装这一个"）</td></tr>
 *   <tr><td>{@code .../blob/main/skills/pdf/SKILL.md}</td><td>同上</td><td>只装该文件所在目录</td></tr>
 *   <tr><td>{@code https://gitlab.com/group/repo/-/tree/main/skills/pdf}</td><td>archive</td><td>该子目录</td></tr>
 *   <tr><td>{@code https://any/path/skill.zip|.tar.gz|.tgz|.skill}</td><td>原样下载</td><td>归档里的全部技能</td></tr>
 * </table>
 *
 * <p>安全：只允许 http/https；拒绝云元数据地址；下载过程有字节上限与超时（仓库内容不可信）。
 */
@Component
public class SkillRepoFetcher {

    private static final Logger log = LoggerFactory.getLogger(SkillRepoFetcher.class);

    /** GitHub 仓库页 → codeload。可选 {@code /tree/<ref>/<subPath>} 或 {@code /blob/<ref>/<file>}。 */
    private static final Pattern GITHUB = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?github\\.com/([^/]+)/([^/]+?)(?:\\.git)?"
                    + "(?:/(?:tree|blob)/([^/]+)(?:/(.*?))?)?/?$",
            Pattern.CASE_INSENSITIVE);

    /** GitLab（支持子组）：可选 {@code /-/tree/<ref>/<subPath>} 或 {@code /-/blob/<ref>/<file>}。 */
    private static final Pattern GITLAB = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?gitlab\\.com/(.+?)(?:\\.git)?"
                    + "(?:/-/(?:tree|blob)/([^/]+)(?:/(.*?))?)?/?$",
            Pattern.CASE_INSENSITIVE);

    private static final String DEFAULT_GITHUB_REF = "HEAD";

    private final HttpClient client;

    private final long maxBytes;

    private final Duration timeout;

    /** 私有仓库/私有制品库用的 token（可选；不配就按匿名访问）。 */
    private final String token;

    public SkillRepoFetcher(
            @Value("${myagent.skill-import.max-archive-mb:20}") int maxArchiveMb,
            @Value("${myagent.skill-import.timeout-seconds:60}") int timeoutSeconds,
            @Value("${myagent.skill-import.token:}") String token) {
        this.maxBytes = Math.max(1, maxArchiveMb) * 1024L * 1024L;
        this.timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        this.token = token == null ? "" : token.trim();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 解析结果。
     *
     * @param downloadUrl 真正要下载的归档地址
     * @param ref 用于展示/落库的分支
     * @param subPath 只装这个子目录下的技能（空 = 全部）
     */
    public record Resolved(String downloadUrl, String ref, String subPath) {}

    /** 下载结果。 */
    public record Fetched(byte[] bytes, String downloadUrl) {}

    /**
     * 按链接抓取归档。
     *
     * @param url 用户给的仓库链接（或归档直链）
     * @param ref 可选分支/标签（链接里没带时用）
     */
    public Fetched fetch(String url, String ref) {
        Resolved resolved = resolve(url, ref);
        log.info("Skill 导入：{} -> {}{}", url, resolved.downloadUrl(),
                resolved.subPath().isEmpty() ? "" : " (只装 " + resolved.subPath() + ")");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(resolved.downloadUrl()))
                .timeout(timeout)
                .header("User-Agent", "myagent-skill-import")
                .header("Accept", "application/zip, application/gzip, application/x-gzip, application/octet-stream, */*")
                .GET();
        if (!token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                throw BizException.badRequest(
                        "下载失败（HTTP " + response.statusCode() + "）：" + resolved.downloadUrl());
            }
            long declared = response.headers()
                    .firstValueAsLong("Content-Length")
                    .orElse(-1);
            if (declared > maxBytes) {
                throw BizException.badRequest("仓库归档超过上限 " + (maxBytes / 1024 / 1024) + "MB");
            }
            return new Fetched(readLimited(response.body()), resolved.downloadUrl());
        } catch (IOException ex) {
            throw BizException.badRequest("下载仓库失败：" + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw BizException.badRequest("下载被中断");
        }
    }

    /** 纯函数：链接 + ref → 下载地址 + 子目录。单独抽出来便于单测（不联网）。 */
    public static Resolved resolve(String url, String ref) {
        String raw = url == null ? "" : url.trim();
        if (raw.isEmpty()) {
            throw BizException.badRequest("请填写 Skill 仓库链接");
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw BizException.badRequest("链接必须以 http:// 或 https:// 开头");
        }
        String effectiveRef = ref == null ? "" : ref.trim();

        Matcher github = GITHUB.matcher(raw);
        if (github.matches()) {
            String owner = github.group(1);
            String repo = github.group(2);
            String linkRef = github.group(3);
            String subPath = subPathOf(github.group(4), raw.endsWith("SKILL.md") || raw.toLowerCase(Locale.ROOT).contains("/blob/"));
            String finalRef = !effectiveRef.isEmpty()
                    ? effectiveRef
                    : (linkRef == null || linkRef.isEmpty() ? DEFAULT_GITHUB_REF : linkRef);
            String finalSub = !effectiveRef.isEmpty() ? "" : subPath;
            return new Resolved(
                    "https://codeload.github.com/" + owner + "/" + repo + "/zip/" + finalRef, finalRef, finalSub);
        }

        Matcher gitlab = GITLAB.matcher(raw);
        if (gitlab.matches()) {
            String project = gitlab.group(1);
            String linkRef = gitlab.group(2);
            String subPath = subPathOf(gitlab.group(3), raw.toLowerCase(Locale.ROOT).contains("/-/blob/"));
            String finalRef = !effectiveRef.isEmpty() ? effectiveRef : (linkRef == null ? "main" : linkRef);
            String finalSub = !effectiveRef.isEmpty() ? "" : subPath;
            String repoName = project.substring(project.lastIndexOf('/') + 1);
            return new Resolved(
                    "https://gitlab.com/" + project + "/-/archive/" + finalRef + "/" + repoName + "-" + finalRef + ".zip",
                    finalRef,
                    finalSub);
        }

        if (isArchiveLink(lower)) {
            return new Resolved(raw, effectiveRef.isEmpty() ? "archive" : effectiveRef, "");
        }

        throw BizException.badRequest("暂不支持的链接：" + raw
                + "。支持 GitHub / GitLab 仓库或子目录地址，以及 .zip / .tar.gz / .tgz / .skill 归档直链");
    }

    /** 子目录：{@code blob} 链接指向文件，取它的父目录；{@code tree} 链接就是目录本身。 */
    private static String subPathOf(String captured, boolean isFileLink) {
        if (captured == null) {
            return "";
        }
        String path = captured.replaceAll("^/+", "").replaceAll("/+$", "");
        if (isFileLink) {
            int slash = path.lastIndexOf('/');
            path = slash < 0 ? "" : path.substring(0, slash);
        }
        return path;
    }

    private static boolean isArchiveLink(String lowerUrl) {
        String path = lowerUrl;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        return path.endsWith(".zip") || path.endsWith(".tar.gz") || path.endsWith(".tgz")
                || path.endsWith(".skill") || path.endsWith(".tar");
    }

    /** 元数据地址一律不碰（云环境的凭证端点）。 */
    public static boolean isBlockedHost(String host) {
        if (host == null) {
            return true;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("169.254.169.254") || h.equals("metadata.google.internal") || h.equals("metadata");
    }

    private byte[] readLimited(InputStream body) throws IOException {
        try (InputStream in = body) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw BizException.badRequest("仓库归档超过上限 " + (maxBytes / 1024 / 1024) + "MB");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /** 供校验：从下载地址取 host，元数据地址直接拒绝。 */
    public static Optional<String> hostOf(String url) {
        try {
            return Optional.ofNullable(URI.create(url).getHost());
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }
}
