package com.myagent.agui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * AG-UI / AgentScope 运行时属性。
 *
 * <p>{@code workspaceRoot} 是 HarnessAgent 的工作区根目录（记忆、事件日志、文件系统工具都落在这里）。
 * 它是**运行时状态**而非源码，默认放在仓库外的可见目录并已被 {@code .gitignore} 忽略；
 * 部署时用 {@code MYAGENT_WORKSPACE} 指向持久化磁盘。
 *
 * <p>注意：默认值是相对路径，会被**按进程工作目录**解析——若从不同目录启动 jar，就会得到两个不同的
 * 工作区（实测踩过：从仓库根启动与从模块目录启动各一份）。所以这里统一规范成绝对路径并在启动时打印，
 * 让「上传物化到哪」「产物扫描哪」「记忆写哪」始终一致且可核对。
 */
@Configuration
public class AguiProperties {

    private static final Logger log = LoggerFactory.getLogger(AguiProperties.class);

    private final String workspaceRoot;
    /** 上下文超过该 token 数即触发官方压缩（CompactionConfig.triggerTokens）。 */
    private final int compactionTriggerTokens;
    /** 压缩后保留的 token 数（CompactionConfig.keepTokens）。 */
    private final int compactionKeepTokens;
    /** 单条工具结果超过该字符数就外置到文件、只留预览（ToolResultEvictionConfig.maxResultChars）。 */
    private final int toolResultMaxChars;
    /** 外置后保留的预览字符数。 */
    private final int toolResultPreviewChars;
    /** 模型未配上下文窗口时的兜底窗口长度（token）。 */
    private final int contextWindowFallback;

    public AguiProperties(
            @Value("${myagent.workspace-root:./.myagent-workspace}") String workspaceRoot,
            @Value("${myagent.compaction.trigger-tokens:120000}") int compactionTriggerTokens,
            @Value("${myagent.compaction.keep-tokens:32000}") int compactionKeepTokens,
            @Value("${myagent.tool-result.max-chars:30000}") int toolResultMaxChars,
            @Value("${myagent.tool-result.preview-chars:1500}") int toolResultPreviewChars,
            @Value("${myagent.compaction.context-window-fallback:128000}") int contextWindowFallback) {
        this.workspaceRoot = java.nio.file.Path.of(workspaceRoot)
                .toAbsolutePath()
                .normalize()
                .toString();
        this.compactionTriggerTokens = compactionTriggerTokens;
        this.compactionKeepTokens = compactionKeepTokens;
        this.toolResultMaxChars = toolResultMaxChars;
        this.toolResultPreviewChars = toolResultPreviewChars;
        this.contextWindowFallback = contextWindowFallback;
        log.info("Agent 工作区根目录（已规范为绝对路径）: {}", this.workspaceRoot);
    }

    public String workspaceRoot() {
        return workspaceRoot;
    }

    public int compactionTriggerTokens() {
        return compactionTriggerTokens;
    }

    public int compactionKeepTokens() {
        return compactionKeepTokens;
    }

    /** 模型未配上下文窗口时的兜底窗口长度（token）。 */
    public int contextWindowFallback() {
        return contextWindowFallback;
    }

    public int toolResultMaxChars() {
        return toolResultMaxChars;
    }

    public int toolResultPreviewChars() {
        return toolResultPreviewChars;
    }
}
