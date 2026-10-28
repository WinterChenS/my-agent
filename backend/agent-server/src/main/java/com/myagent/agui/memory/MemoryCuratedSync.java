package com.myagent.agui.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 长期记忆（{@code MEMORY.md}）**存数据库**，不落磁盘；本类只负责把历史遗留的磁盘副本迁进库。
 *
 * <p>背景：框架原本把 {@code MEMORY.md} 写在工作区磁盘上（每日台账才进库），换成 Docker 部署、
 * 工作区换新卷后长期记忆整份消失（用户实测）。现在 {@code AgentFactory} 用官方
 * {@code filesystemRoute("MEMORY.md", 远程库文件系统)} 精确路由这一个文件，框架的读写都直接落库。
 *
 * <p>那这个类还留着的唯一职责：**升级兼容**。老部署的磁盘上已经有 {@code MEMORY.md}，
 * 启动时导入库并删除磁盘副本，之后就完全以库为准（不再有双向同步、也不再往磁盘写回）。
 */
@Component
public class MemoryCuratedSync {

    private static final Logger log = LoggerFactory.getLogger(MemoryCuratedSync.class);

    private final MemoryAdminService memoryAdminService;
    private final JdbcTemplate jdbc;
    private final Path workspaceRoot;

    public MemoryCuratedSync(
            MemoryAdminService memoryAdminService,
            JdbcTemplate jdbc,
            com.myagent.agui.AguiProperties aguiProperties) {
        this.memoryAdminService = memoryAdminService;
        this.jdbc = jdbc;
        this.workspaceRoot = Path.of(aguiProperties.workspaceRoot()).toAbsolutePath().normalize();
    }

    /**
     * 启动时把磁盘上的历史 {@code MEMORY.md} 迁进库（导完即删磁盘副本）。
     *
     * <p>幂等：库里已有的用户直接跳过；磁盘上已经没有文件的用户什么也不做。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void migrateLegacyOnStartup() {
        int imported = 0;
        for (Long userId : candidateUserIds()) {
            if (memoryAdminService.importLegacyCurated(userId) != null) {
                imported++;
            }
        }
        if (imported > 0) {
            log.info("长期记忆已迁移到数据库：{} 份（磁盘副本已删除）", imported);
        }
    }

    /** 候选用户：库里已有记忆命名空间的用户 + 磁盘上还有 MEMORY.md 的用户目录。 */
    private List<Long> candidateUserIds() {
        List<Long> ids = new ArrayList<>();
        try {
            jdbc.queryForList("select distinct namespace_path from memory_store", String.class)
                    .stream()
                    .map(MemoryCuratedSync::userIdOfNamespace)
                    .filter(id -> id != null)
                    .forEach(ids::add);
        } catch (Exception ex) {
            log.debug("查询记忆命名空间失败: {}", ex.getMessage());
        }
        for (Path file : legacyDiskFiles()) {
            try {
                ids.add(Long.valueOf(file.getParent().getFileName().toString()));
            } catch (NumberFormatException ignored) {
                // 不是 <userId> 目录，跳过
            }
        }
        return ids.stream().distinct().toList();
    }

    /** `<workspace>/<userId>/MEMORY.md`（用户目录名必须是数字，防止误碰其它目录）。 */
    private List<Path> legacyDiskFiles() {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(workspaceRoot)) {
            return files;
        }
        try (var stream = Files.list(workspaceRoot)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                String name = dir.getFileName().toString();
                if (!name.chars().allMatch(Character::isDigit)) {
                    return;
                }
                Path file = dir.resolve(MemoryAdminService.CURATED_KEY.substring(1));
                if (Files.isRegularFile(file)) {
                    files.add(file);
                }
            });
        } catch (IOException ex) {
            log.debug("扫描工作区长期记忆失败: {}", ex.getMessage());
        }
        return files;
    }

    /** `user/1` → 1L */
    static Long userIdOfNamespace(String namespacePath) {
        if (namespacePath == null) {
            return null;
        }
        int slash = namespacePath.lastIndexOf('/');
        String tail = slash < 0 ? namespacePath : namespacePath.substring(slash + 1);
        try {
            return Long.valueOf(tail);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
