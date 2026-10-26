package com.myagent.agui.shell;

import com.myagent.agui.AguiProperties;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 在工作区里执行命令/脚本（跑 Python、npm、命令行工具）。
 *
 * <p>为什么需要：Excel/数据分析这类任务，模型必须能真正跑代码；没有这个工具它只能"看文本猜结论"。
 *
 * <p>跨平台：Windows 走 {@code cmd.exe /c}，Linux/macOS 走 {@code /bin/sh -c}，
 * 由 {@link ShellRunner} 自动选择；工作目录固定为**当前用户的工作区**，
 * 因此 {@code uploads/xxx.xlsx} 这类相对路径可以直接用。
 *
 * <p>权限：本工具是写操作（{@link #isReadOnly()} = false）。Agent 处于
 * {@code REQUIRE_APPROVAL} 模式时，平台会自动为它弹审批卡，模型无法自行执行。
 */
public class RunCommandTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(RunCommandTool.class);

    private final AguiProperties aguiProperties;
    private final String name;
    private final boolean enabled;
    private final PythonEnv pythonEnv;
    /** 缺 Python 包时是否自动 pip install（默认开；装到该用户自己的 venv 里，不动系统环境）。 */
    private final boolean autoInstallPythonDeps;
    private final ShellRunner runner = new ShellRunner();

    public RunCommandTool(
            AguiProperties aguiProperties,
            String name,
            boolean enabled,
            PythonEnv pythonEnv,
            boolean autoInstallPythonDeps) {
        this.aguiProperties = aguiProperties;
        this.name = name;
        this.enabled = enabled;
        this.pythonEnv = pythonEnv;
        this.autoInstallPythonDeps = autoInstallPythonDeps;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        if (!enabled) {
            return "命令执行未启用。";
        }
        return "在你的工作区里执行一条命令（可以跑 Python / Node / 命令行工具），用来处理文件、做数据分析。"
                + "当前运行环境：" + ShellRunner.platformHint() + "。"
                + "工作目录默认是你的工作区，上传的文件在 uploads/ 下（例如 uploads/数据.xlsx）。"
                + "命令必须是非交互式的（不要 sleep 等输入、不要开浏览器）；默认 60 秒超时，可用 timeoutSeconds 调整（上限 600）。"
                + "返回内容包含退出码、标准输出、标准错误；失败时先看 stderr。"
                + "写完脚本要执行就用它，例如 command=\"python analyze.py\"。"
                + "python/pip 已指向**你自己的虚拟环境**（首次自动创建），缺第三方库时可以直接 "
                + "`python -m pip install 包名`；平台也会在报 ModuleNotFoundError 时自动尝试装一次。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("command", Map.of(
                "type", "string",
                "description", "要执行的完整命令，例如 python3 analyze.py 或 node -v"));
        properties.put("cwd", Map.of(
                "type", "string",
                "description", "可选：相对工作区的工作目录，默认工作区根（例如 uploads）"));
        properties.put("timeoutSeconds", Map.of(
                "type", "integer",
                "description", "可选：超时秒数，默认 60，最大 600"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("command"));
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> execute(param))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex -> Mono.just(text("执行失败：" + ex.getMessage())));
    }

    private ToolResultBlock execute(ToolCallParam param) {
        if (!enabled) {
            return text("命令执行未启用（服务端 myagent.shell.enabled=false）。");
        }
        String command = asString(param.getInput().get("command"));
        if (command == null || command.isBlank()) {
            return text("command 不能为空");
        }
        String userId = param.getRuntimeContext() == null ? null : param.getRuntimeContext().getUserId();
        if (userId == null || userId.isBlank()) {
            return text("当前没有会话上下文，无法确定工作区");
        }
        Path workspaceRoot = Path.of(aguiProperties.workspaceRoot()).toAbsolutePath().normalize();
        Path userWorkspace = workspaceRoot.resolve(userId).normalize();
        Path cwd = userWorkspace;
        String requestedCwd = asString(param.getInput().get("cwd"));
        if (requestedCwd != null && !requestedCwd.isBlank()) {
            Path candidate = userWorkspace.resolve(requestedCwd).normalize();
            if (!candidate.startsWith(userWorkspace)) {
                return text("cwd 必须位于你的工作区内：" + requestedCwd);
            }
            cwd = candidate;
        }
        int timeout = asInt(param.getInput().get("timeoutSeconds"), ShellRunner.DEFAULT_TIMEOUT_SECONDS);

        java.util.Optional<Path> pythonBin = pythonEnv.binDir(userId);
        java.util.List<Path> pathPrefix = pythonBin.map(java.util.List::of).orElse(null);

        log.info("执行命令 userId={} cwd={} cmd={}", userId, cwd, abbreviate(command));
        ShellRunner.Result result = runner.run(command, cwd, timeout, pathPrefix);

        // 缺包自动补装一次：模型经常直接写 pandas/numpy，环境里没有 → 我们自己装到 venv 再重试
        java.util.Optional<String> missing = PythonEnv.missingModule(result.stderr());
        if (!result.ok() && missing.isPresent() && autoInstallPythonDeps) {
            String module = missing.get();
            if (pythonEnv.pipInstall(userId, cwd, module)) {
                log.info("补装 {} 后重试命令 userId={}", module, userId);
                ShellRunner.Result retry = runner.run(command, cwd, timeout, pathPrefix);
                return text("（缺少 " + module + "，已自动安装到你的虚拟环境并重试一次）\n"
                        + format(retry, cwd, command));
            }
            return text("（缺少 Python 包 " + module + "，自动安装失败；可以自己跑 "
                    + "`python -m pip install " + module + "` 再试）\n" + format(result, cwd, command));
        }
        return text(format(result, cwd, command));
    }

    private static String format(ShellRunner.Result result, Path cwd, String command) {
        StringBuilder out = new StringBuilder();
        out.append(result.timedOut() ? "超时终止" : (result.ok() ? "成功" : "失败"))
                .append("（exit=").append(result.exitCode())
                .append(", ").append(result.elapsedMs()).append("ms, cwd=").append(cwd).append("）\n");
        out.append("> ").append(abbreviate(command)).append('\n');
        String stdout = result.stdout() == null ? "" : result.stdout().strip();
        String stderr = result.stderr() == null ? "" : result.stderr().strip();
        if (!stdout.isEmpty()) {
            out.append("\n--- stdout ---\n").append(stdout).append('\n');
        }
        if (!stderr.isEmpty()) {
            out.append("\n--- stderr ---\n").append(stderr).append('\n');
        }
        if (stdout.isEmpty() && stderr.isEmpty()) {
            out.append("\n（没有任何输出）\n");
        }
        return out.toString();
    }

    private static ToolResultBlock text(String content) {
        return ToolResultBlock.builder().output(TextBlock.builder().text(content).build()).build();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }
}
