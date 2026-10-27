package com.myagent.agui.skillimport;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 找出「技能资源落盘后的目录」（官方 {@code MarketplaceStager} 的缓存目录）。
 *
 * <p>为什么需要它（实测，2026-10-27）：官方把技能资源写到
 * {@code <workspace>/.skills-cache/<userId>/<仓库命名空间>/<技能名>/}，
 * 但注入给模型的 {@code <available_skills>} 里**没有 files-root**（只有 name/description/skill-id）。
 * 于是模型要跑 {@code bin/archify.mjs} 时不知道文件在哪，只能 {@code dir /s} 全盘找，
 * 常常找到过期的临时解压目录、或者干脆把步数耗光（实测 archify 就这样跑到迭代上限）。
 *
 * <p>这里只做"查目录"，不改任何技能加载逻辑（门禁 G8）。
 */
public final class SkillFileLocator {

    private static final Logger log = LoggerFactory.getLogger(SkillFileLocator.class);

    /** 官方 stager 的缓存目录名。 */
    public static final String CACHE_DIR = ".skills-cache";

    private final Path workspaceRoot;

    public SkillFileLocator(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    /**
     * 该用户下「技能名 → 已落盘目录」。
     *
     * <p>真实结构是 {@code .skills-cache/<userId>/<namespace>/<skillName>}；
     * namespace 由官方仓库信息推导（如 {@code postgresql_agentscope_agentscope_skills}），
     * 这里不写死，直接扫第二层。
     */
    public Map<String, Path> locate(String userId) {
        Map<String, Path> result = new LinkedHashMap<>();
        if (userId == null || userId.isBlank()) {
            return result;
        }
        Path userCache = workspaceRoot.resolve(CACHE_DIR).resolve(userId);
        if (!Files.isDirectory(userCache)) {
            return result;
        }
        try (DirectoryStream<Path> namespaces = Files.newDirectoryStream(userCache)) {
            for (Path namespace : namespaces) {
                if (!Files.isDirectory(namespace)) {
                    continue;
                }
                try (DirectoryStream<Path> skills = Files.newDirectoryStream(namespace)) {
                    for (Path skillDir : skills) {
                        if (Files.isDirectory(skillDir)) {
                            result.putIfAbsent(skillDir.getFileName().toString(), skillDir);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("扫描技能缓存目录失败 userId={}: {}", userId, ex.getMessage());
        }
        return result;
    }

    /** 单个技能的落盘目录（找不到返回 empty）。 */
    public Optional<Path> locate(String userId, String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(locate(userId).get(skillName));
    }

    /**
     * 目录里可执行入口的提示（如 {@code bin/archify.mjs}），用于让模型直接跑。
     *
     * <p>只做浅层猜测：{@code bin/} 下的第一个 .mjs/.js/.py/.sh，或根目录同名脚本。
     */
    public static Optional<String> guessEntrypoint(Path skillDir, String skillName) {
        if (skillDir == null) {
            return Optional.empty();
        }
        List<String> candidates = new ArrayList<>();
        Path bin = skillDir.resolve("bin");
        if (Files.isDirectory(bin)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(bin)) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".mjs") || name.endsWith(".js") || name.endsWith(".py")) {
                        candidates.add("bin/" + name);
                    }
                }
            } catch (Exception ex) {
                log.debug("读取 bin 目录失败 {}: {}", bin, ex.getMessage());
            }
        }
        for (String ext : List.of(".mjs", ".js", ".py", ".sh")) {
            Path direct = skillDir.resolve(skillName + ext);
            if (Files.isRegularFile(direct)) {
                candidates.add(skillName + ext);
            }
        }
        return candidates.stream().sorted().findFirst();
    }
}
