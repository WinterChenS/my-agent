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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 长期记忆（{@code MEMORY.md}）与数据库的双向同步。
 *
 * <p>为什么需要：框架把 {@code MEMORY.md} 写在**工作区磁盘**上，而每日台账走数据库。
 * 换成 Docker 部署（或换机器/换卷）后磁盘是全新的 →「长期记忆」整份消失，只剩台账
 * （用户实测："记忆管理里面永久记忆没了"）。现在库里也留一份镜像，这里负责两头对齐：
 *
 * <ul>
 *   <li><b>启动时</b> 库 → 磁盘：保证框架（它只读磁盘）下一轮就能读到长期记忆；</li>
 *   <li><b>周期性</b> 磁盘 → 库：框架 consolidation 合并出新内容后，把镜像刷新，
 *       下次换机器才带得走。周期同步也是为了不改框架内部写文件的行为（门禁 G8）。</li>
 * </ul>
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

    /** 启动时先恢复：库里有、磁盘没有 → 写回磁盘。 */
    @EventListener(ApplicationReadyEvent.class)
    public void restoreOnStartup() {
        int restored = restoreMissing();
        int uploaded = uploadChanged();
        if (restored > 0 || uploaded > 0) {
            log.info("长期记忆同步完成：从库恢复 {} 份，上传镜像 {} 份", restored, uploaded);
        }
    }

    /**
     * 周期同步（默认 5 分钟）：先把框架新写的 MEMORY.md 上传镜像，再把缺的从库补回磁盘。
     *
     * <p>为什么是周期而不是实时：MEMORY.md 由框架 consolidation 自己写文件，平台不去拦截它的写路径
     * （不重写框架逻辑）；周期对齐足够，且对性能无感。
     */
    @Scheduled(fixedDelayString = "${myagent.memory.curated-sync-millis:300000}", initialDelay = 60_000)
    public void syncPeriodically() {
        try {
            int uploaded = uploadChanged();
            int restored = restoreMissing();
            if (uploaded > 0 || restored > 0) {
                log.debug("长期记忆周期同步：上传 {} 份，恢复 {} 份", uploaded, restored);
            }
        } catch (Exception ex) {
            log.warn("长期记忆周期同步失败（不影响对话）: {}", ex.toString());
        }
    }

    /** 磁盘 → 库：把所有用户的 MEMORY.md 镜像进库（内容没变会被跳过）。 */
    int uploadChanged() {
        int count = 0;
        for (Path file : diskCuratedFiles()) {
            memoryAdminService.mirrorCuratedToStore(file);
            count++;
        }
        return count;
    }

    /** 库 → 磁盘：库里有的用户，若磁盘上没有就写回去。 */
    int restoreMissing() {
        List<Long> userIds = new ArrayList<>();
        try {
            userIds.addAll(jdbc.queryForList(
                    "select distinct namespace_path from memory_store where item_key = ?",
                    String.class,
                    MemoryAdminService.CURATED_KEY).stream()
                    .map(MemoryCuratedSync::userIdOfNamespace)
                    .filter(id -> id != null)
                    .toList());
        } catch (Exception ex) {
            log.debug("查询已镜像的长期记忆失败: {}", ex.getMessage());
            return 0;
        }
        int restored = 0;
        for (Long userId : userIds) {
            Path file = workspaceRoot.resolve(String.valueOf(userId)).resolve("MEMORY.md");
            if (Files.isRegularFile(file)) {
                continue;
            }
            if (memoryAdminService.restoreCuratedFromStore(file)) {
                restored++;
            }
        }
        return restored;
    }

    /** `<workspace>/<userId>/MEMORY.md` 列表（用户目录名必须是数字，防止误碰其它目录）。 */
    private List<Path> diskCuratedFiles() {
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
                Path file = dir.resolve("MEMORY.md");
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
