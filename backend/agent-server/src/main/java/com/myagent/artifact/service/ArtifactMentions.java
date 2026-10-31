package com.myagent.artifact.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 从 Agent 的最终回复里找出「它说已经交付的文件」，补登记到本轮产物。
 *
 * <p>解决的实测问题：第一次生成报告后，用户说「再发一份给我」，模型往往只回一句
 * 「文件在 outputs/xxx.html」而**不再调用 {@code deliverArtifact}**；文件本身也没变化，
 * 自动快照对比同样收不到 —— 于是这一轮的气泡下面就没有产物，用户以为没生成。
 *
 * <p>边界：只认**可交付类型**（报告/表格/图片/压缩包/PDF），且文件必须在工作区真实存在；
 * 这只是兜底，正常路径仍然是 Agent 主动 {@code deliverArtifact}（意图优先）。
 */
@Component
public class ArtifactMentions {

    private static final Logger log = LoggerFactory.getLogger(ArtifactMentions.class);

    /** 可交付的扩展名（只有这些才会因为"被提到"而补登记）。 */
    private static final Set<String> DELIVERABLE = Set.of(
            "html", "htm", "pdf", "docx", "xlsx", "xls", "pptx", "csv",
            "png", "jpg", "jpeg", "svg", "webp", "gif", "zip", "md");

    /**
     * 文件名/路径：字母（含中文，用 \p{L}）、数字、下划线、短横、点、斜杠，最后是可交付扩展名。
     *
     * <p>刻意不用 {@code \\.} / {@code \\-} 这类转义：方括号里把 {@code -} 放末尾、扩展名用
     * {@code [.]} 前缀，正则和 Java 字符串都不用再套一层反斜杠（少一半坑）。
     */
    private static final Pattern FILE_TOKEN = Pattern.compile(
            "([\\p{L}\\p{N}_./-]+[.](html|htm|pdf|docx|xlsx|xls|pptx|csv|png|jpg|jpeg|svg|webp|gif|zip|md))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final WorkspaceArtifactScanner scanner;

    public ArtifactMentions(WorkspaceArtifactScanner scanner) {
        this.scanner = scanner;
    }

    /**
     * 找到回复里提到、且在工作区真实存在、且本轮还没登记过的可交付文件。
     *
     * @param alreadyRegistered 本轮已经登记过的文件名（避免重复）
     */
    public java.util.List<Path> resolve(Long userId, String assistantText, Set<String> alreadyRegistered) {
        if (assistantText == null || assistantText.isBlank()) {
            return java.util.List.of();
        }
        Path root = scanner.workspaceDir(String.valueOf(userId));
        if (root == null || !Files.isDirectory(root)) {
            return java.util.List.of();
        }
        Set<String> seen = new HashSet<>();
        java.util.List<Path> result = new java.util.ArrayList<>();
        Matcher matcher = FILE_TOKEN.matcher(assistantText);
        while (matcher.find()) {
            String token = matcher.group(1);
            String extension = matcher.group(2).toLowerCase(Locale.ROOT);
            if (!DELIVERABLE.contains(extension)) {
                continue;
            }
            String fileName = fileNameOf(token);
            if (alreadyRegistered.contains(fileName) || !seen.add(fileName)) {
                continue;
            }
            Path found = find(root, token, fileName);
            if (found != null) {
                result.add(found);
            }
        }
        return result;
    }

    private static String fileNameOf(String token) {
        int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        return slash >= 0 ? token.substring(slash + 1) : token;
    }

    /** 先按给出的相对/绝对路径找，再按文件名在工作区里找（取最新修改的那个）。 */
    private static Path find(Path root, String token, String fileName) {
        try {
            Path direct = root.resolve(token.replace('\\', '/')).normalize();
            if (direct.startsWith(root) && Files.isRegularFile(direct)) {
                return direct;
            }
        } catch (RuntimeException ignored) {
            // 路径非法（含特殊字符）时退回按文件名找
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .filter(path -> !WorkspaceArtifactScanner.isExcluded(root.relativize(path).toString()))
                    .max(java.util.Comparator.comparingLong(ArtifactMentions::lastModified))
                    .orElse(null);
        } catch (Exception ex) {
            log.debug("按文件名找工作区文件失败 {}: {}", fileName, ex.toString());
            return null;
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
