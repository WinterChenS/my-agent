package com.myagent.artifact.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 产物自动登记（D22，用户选「自动全收」）。
 *
 * <p>做法：run 开始前对该会话工作区做一次快照（路径 → mtime+size），run 结束后对比，
 * 把新增或变更的文件登记为产物。
 *
 * <p>必须排除框架内部状态目录，否则产物区会被会话转录、记忆文件、技能缓存灌满：
 * {@code agents/**\/sessions/**}、{@code memory/**}、{@code .skills-cache/**}、{@code uploads/**}。
 */
@Component
public class WorkspaceArtifactScanner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceArtifactScanner.class);

    /** 相对工作区根、需要排除的路径片段。 */
    private static final List<String> EXCLUDED_SEGMENTS = List.of(
            "/agents/", "/sessions/", "/default/", "/memory/", "/.skills-cache/", "/uploads/");

    private static final Set<String> EXCLUDED_NAMES = Set.of(
            ".DS_Store",
            // 框架自管的工作区文件：它们是框架状态而不是「用户产物」，不应出现在右侧栏
            "MEMORY.md",
            "AGENTS.md",
            "KNOWLEDGE.md",
            "tools.json");

    private final com.myagent.agui.AguiProperties aguiProperties;

    public WorkspaceArtifactScanner(com.myagent.agui.AguiProperties aguiProperties) {
        this.aguiProperties = aguiProperties;
    }

    /**
     * run 前快照：key = 相对该属主工作区的路径，value = mtime+size。
     *
     * <p>实测布局是 {@code <workspaceRoot>/<owner>/…}（owner 为调用方身份，通常是 userId），
     * 而不是按 threadId 分目录——所以这里按 owner 扫描，再排除框架内部目录。
     */
    public Map<String, Long> snapshot(String owner) {
        Map<String, Long> map = new HashMap<>();
        Path root = workspaceDir(owner);
        if (root == null || !Files.isDirectory(root)) {
            return map;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !isExcluded(root.relativize(p).toString()))
                    .forEach(p -> {
                        try {
                            map.put(root.relativize(p).toString(), stamp(p));
                        } catch (IOException ignored) {
                            // 读不到就跳过
                        }
                    });
        } catch (IOException ex) {
            log.warn("扫描工作区失败 owner={}: {}", owner, ex.toString());
        }
        return map;
    }

    /**
     * run 后对比，返回本次新增或变更的**文件的绝对路径**（按路径排序，保证可测）。
     */
    public List<Path> changedFiles(String owner, Map<String, Long> before) {
        Path root = workspaceDir(owner);
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> !isExcluded(root.relativize(p).toString()))
                    .filter(p -> {
                        try {
                            String rel = root.relativize(p).toString();
                            Long old = before.get(rel);
                            return old == null || old != stamp(p);
                        } catch (IOException ex) {
                            return false;
                        }
                    })
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            log.warn("对比工作区失败 owner={}: {}", owner, ex.toString());
            return List.of();
        }
    }

    /** 属主工作区目录：{@code <workspaceRoot>/<owner>}。 */
    public Path workspaceDir(String owner) {
        if (owner == null || owner.isBlank()) {
            return null;
        }
        return Path.of(aguiProperties.workspaceRoot(), owner);
    }

    private static long stamp(Path p) throws IOException {
        return Files.getLastModifiedTime(p).toMillis() * 1_000_000L + Files.size(p);
    }

    /** 该相对路径是否属于「框架内部状态」而不应登记为产物。public 便于单测覆盖规则。 */
    public static boolean isExcluded(String relativePath) {
        String normalized = "/" + relativePath.replace('\\', '/');
        for (String segment : EXCLUDED_SEGMENTS) {
            if (normalized.contains(segment)) {
                return true;
            }
        }
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        return EXCLUDED_NAMES.contains(name);
    }
}
