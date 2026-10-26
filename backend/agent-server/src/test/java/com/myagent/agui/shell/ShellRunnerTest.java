package com.myagent.agui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 跨平台命令执行：同一套断言要在 Windows / Linux / macOS 都成立。
 *
 * <p>所以这里只选三个平台都有的行为：echo、退出码、工作目录；sleep 与输出长度用分支命令。
 */
class ShellRunnerTest {

    private final ShellRunner runner = new ShellRunner();

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @Test
    void echo能执行并带退出码(@TempDir Path dir) {
        ShellRunner.Result result = runner.run("echo hello-myagent", dir, 10);

        assertThat(result.ok()).isTrue();
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("hello-myagent");
    }

    @Test
    void 非零退出码会带回(@TempDir Path dir) {
        ShellRunner.Result result = runner.run("exit 3", dir, 10);

        assertThat(result.ok()).isFalse();
        assertThat(result.exitCode()).isEqualTo(3);
    }

    @Test
    void 工作目录就是传入的目录(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("marker.txt"), "x");
        String command = isWindows() ? "dir /b marker.txt" : "ls marker.txt";

        ShellRunner.Result result = runner.run(command, dir, 10);

        assertThat(result.ok()).isTrue();
        assertThat(result.stdout()).contains("marker.txt");
    }

    @Test
    void 超时会杀掉进程(@TempDir Path dir) {
        // 两个平台都没有统一的 sleep，按平台选
        String sleeper = isWindows() ? "ping -n 6 127.0.0.1 >nul" : "sleep 5";

        ShellRunner.Result result = runner.run(sleeper, dir, 1);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.stderr()).contains("超过 1 秒被终止");
    }

    @Test
    void 空命令直接返回错误(@TempDir Path dir) {
        ShellRunner.Result result = runner.run("  ", dir, 5);

        assertThat(result.ok()).isFalse();
        assertThat(result.stderr()).contains("命令为空");
    }

    @Test
    void 平台提示会指明解释器习惯() {
        String hint = ShellRunner.platformHint();

        assertThat(hint).isNotBlank();
        if (isWindows()) {
            assertThat(hint).contains("cmd.exe").contains("python");
        } else {
            assertThat(hint).contains("/bin/sh").contains("python3");
        }
    }
}
