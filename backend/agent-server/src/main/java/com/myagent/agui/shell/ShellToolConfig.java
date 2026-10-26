package com.myagent.agui.shell;

import com.myagent.agui.AguiProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 命令执行工具的装配。
 *
 * <p>注册两个名字（{@code execute} 与 {@code run_command}）：不同模型对"跑命令"的工具名偏好差别很大，
 * 实测有的模型固定叫 {@code execute}，有的叫 {@code run_command}；两个都挂上就不用让模型猜。
 *
 * <p>总开关：{@code myagent.shell.enabled}（默认开）。关掉后工具仍存在但会直接返回"未启用"，
 * 便于灰度与排障。
 */
@Configuration
public class ShellToolConfig {

    /** 每用户一份 Python venv（自动装包不污染系统环境）。 */
    @Bean
    public PythonEnv pythonEnv(
            AguiProperties aguiProperties,
            @Value("${myagent.shell.python-venv:true}") boolean venvEnabled) {
        return new PythonEnv(
                java.nio.file.Path.of(aguiProperties.workspaceRoot()).toAbsolutePath().normalize(),
                venvEnabled);
    }

    @Bean
    public RunCommandTool executeTool(
            AguiProperties aguiProperties,
            PythonEnv pythonEnv,
            @Value("${myagent.shell.enabled:true}") boolean enabled,
            @Value("${myagent.shell.auto-install-python-deps:true}") boolean autoInstall) {
        return new RunCommandTool(aguiProperties, "execute", enabled, pythonEnv, autoInstall);
    }

    @Bean
    public RunCommandTool runCommandTool(
            AguiProperties aguiProperties,
            PythonEnv pythonEnv,
            @Value("${myagent.shell.enabled:true}") boolean enabled,
            @Value("${myagent.shell.auto-install-python-deps:true}") boolean autoInstall) {
        return new RunCommandTool(aguiProperties, "run_command", enabled, pythonEnv, autoInstall);
    }
}
