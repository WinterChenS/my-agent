package com.myagent.agui.shell;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 跨平台命令执行器（Windows / Linux / macOS 同一套逻辑）。
 *
 * <p>为什么不直接 {@code Runtime.exec}：三个坑必须一起处理——
 * <ol>
 *   <li><b>shell 差异</b>：Windows 只有 {@code cmd.exe}（PowerShell 也行但参数转义不同），
 *       Unix 用 {@code /bin/sh}；这里按 OS 自动选，调用方只给一整条命令串；</li>
 *   <li><b>编码</b>：Windows 控制台常是 GBK，脚本输出中文会乱码；统一注入
 *       {@code PYTHONIOENCODING=utf-8} / {@code LANG=C.UTF-8}，读回时先按 UTF-8 解，失败再退本地编码；</li>
 *   <li><b>超时与清理</b>：必须杀掉**整棵进程树**（python 会 fork 子进程），否则超时后残留进程继续跑。</li>
 * </ol>
 *
 * <p>安全边界：工作目录锁定在调用方给的工作区内；本类不做白名单（由权限体系决定谁能用），
 * 但会限制超时与输出体积，避免一次调用把服务打爆。
 */
public class ShellRunner {

    private static final Logger log = LoggerFactory.getLogger(ShellRunner.class);

    /** 默认超时（秒）。 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /** 允许的最大超时（秒）：再长就该走异步任务，而不是阻塞一轮对话。 */
    public static final int MAX_TIMEOUT_SECONDS = 600;

    /** stdout / stderr 各自保留的最大字符数。 */
    static final int MAX_OUTPUT_CHARS = 20_000;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    /** 一次执行的结果。 */
    public record Result(int exitCode, String stdout, String stderr, boolean timedOut, long elapsedMs) {

        public boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    /** 当前平台给模型看的提示（模型据此决定用 python 还是 python3）。 */
    public static String platformHint() {
        if (WINDOWS) {
            return "Windows（shell=cmd.exe；解释器一般用 python、node，也可以用 powershell -Command）";
        }
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String name = os.contains("mac") ? "macOS" : "Linux";
        return name + "（shell=/bin/sh；解释器一般用 python3、node）";
    }

    private static boolean isWindows() {
        return WINDOWS;
    }

    /**
     * 在给定工作目录执行一条命令。
     *
     * @param command 完整命令行（调用方负责拼参数；本类直接交给 shell 执行）
     * @param cwd 工作目录（不存在会创建）
     * @param timeoutSeconds 超时秒数；<=0 用默认值
     */
    public Result run(String command, Path cwd, int timeoutSeconds) {
        if (command == null || command.isBlank()) {
            return new Result(-1, "", "命令为空", false, 0);
        }
        int timeout = timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS);
        Path workDir = cwd == null ? Path.of(".").toAbsolutePath() : cwd.toAbsolutePath().normalize();
        try {
            Files.createDirectories(workDir);
        } catch (IOException ex) {
            return new Result(-1, "", "无法创建工作目录 " + workDir + ": " + ex.getMessage(), false, 0);
        }

        List<String> argv = new ArrayList<>();
        if (isWindows()) {
            argv.add("cmd.exe");
            argv.add("/c");
            argv.add(command);
        } else {
            argv.add("/bin/sh");
            argv.add("-c");
            argv.add(command);
        }

        long started = System.currentTimeMillis();
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(argv);
            builder.directory(workDir.toFile());
            builder.environment().putAll(childEnvironment());
            builder.redirectErrorStream(false);
            process = builder.start();

            StreamPump out = new StreamPump(process.getInputStream());
            StreamPump err = new StreamPump(process.getErrorStream());
            Thread tOut = new Thread(out, "shell-stdout");
            Thread tErr = new Thread(err, "shell-stderr");
            tOut.setDaemon(true);
            tErr.setDaemon(true);
            tOut.start();
            tErr.start();

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                killTree(process);
                joinQuietly(tOut);
                joinQuietly(tErr);
                long elapsed = System.currentTimeMillis() - started;
                log.info("命令超时（{}s）被终止: {}", timeout, abbreviate(command));
                return new Result(
                        -1,
                        out.text(),
                        err.text() + "\n（命令超过 " + timeout + " 秒被终止，可能卡在交互式输入或死循环）",
                        true,
                        elapsed);
            }
            joinQuietly(tOut);
            joinQuietly(tErr);
            long elapsed = System.currentTimeMillis() - started;
            return new Result(process.exitValue(), out.text(), err.text(), false, elapsed);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (process != null) {
                killTree(process);
            }
            return new Result(-1, "", "执行被中断", false, System.currentTimeMillis() - started);
        } catch (Exception ex) {
            if (process != null) {
                killTree(process);
            }
            return new Result(-1, "", "启动命令失败: " + ex.getMessage(), false, System.currentTimeMillis() - started);
        }
    }

    /** 子进程环境：保留 PATH/HOME 等，强制 UTF-8 输出。 */
    private static Map<String, String> childEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        ProcessBuilder probe = new ProcessBuilder();
        env.putAll(probe.environment());
        // Python 的 stdout 编码：不设的话 Windows 下中文直接乱码/报错
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONUNBUFFERED", "1");
        env.put("PYTHONUTF8", "1");
        if (!isWindows()) {
            env.put("LANG", env.getOrDefault("LANG", "C.UTF-8"));
            env.put("LC_ALL", env.getOrDefault("LC_ALL", "C.UTF-8"));
        }
        // 不让交互式工具等待输入
        env.put("TERM", "dumb");
        env.put("NO_COLOR", "1");
        return env;
    }

    /** 杀掉整棵进程树（python/node 常会 fork 子进程）。 */
    private static void killTree(Process process) {
        try {
            process.descendants().forEach(handle -> {
                try {
                    handle.destroyForcibly();
                } catch (Exception ignored) {
                    // 尽力而为
                }
            });
            process.destroyForcibly();
        } catch (Exception ex) {
            log.debug("终止进程树失败: {}", ex.getMessage());
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(2_000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }

    /** 读取子进程输出，按 UTF-8 优先、本地编码兜底解码，并限长。 */
    static final class StreamPump implements Runnable {

        private final InputStream in;
        private final StringBuilder buffer = new StringBuilder();

        StreamPump(InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            try (in) {
                byte[] chunk = new byte[4096];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    if (buffer.length() < MAX_OUTPUT_CHARS) {
                        buffer.append(decode(chunk, read));
                    }
                }
            } catch (Exception ex) {
                // 进程被杀时流会关闭，忽略
            }
        }

        synchronized String text() {
            String value = buffer.toString();
            if (value.length() > MAX_OUTPUT_CHARS) {
                return value.substring(0, MAX_OUTPUT_CHARS) + "\n…（输出过长已截断）";
            }
            return value;
        }

        private static String decode(byte[] bytes, int length) {
            String utf8 = new String(bytes, 0, length, StandardCharsets.UTF_8);
            // 出现替换字符说明不是 UTF-8（Windows GBK 场景），退回系统编码
            if (utf8.indexOf('\uFFFD') >= 0) {
                return new String(bytes, 0, length, Charset.defaultCharset());
            }
            return utf8;
        }
    }
}
