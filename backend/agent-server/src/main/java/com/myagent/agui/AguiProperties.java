package com.myagent.agui;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * AG-UI / AgentScope 运行时属性。
 *
 * <p>{@code workspaceRoot} 是 HarnessAgent 的工作区根目录（记忆、事件日志、文件系统工具都落在这里）。
 * 它是**运行时状态**而非源码，默认放在仓库外的可见目录并已被 {@code .gitignore} 忽略；
 * 部署时用 {@code MYAGENT_WORKSPACE} 指向持久化磁盘。
 */
@Configuration
public class AguiProperties {

    private final String workspaceRoot;

    public AguiProperties(
            @Value("${myagent.workspace-root:./.myagent-workspace}") String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public String workspaceRoot() {
        return workspaceRoot;
    }
}
