package com.myagent.agui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Python 环境辅助逻辑（纯函数，不建 venv，跑得快）。 */
class PythonEnvTest {

    @Test
    void 从报错里解析缺失的模块名() {
        String stderr = "Traceback (most recent call last):\n"
                + "  File \"a.py\", line 1, in <module>\n"
                + "ModuleNotFoundError: No module named 'pandas'";
        assertThat(PythonEnv.missingModule(stderr)).contains("pandas");
    }

    @Test
    void 子模块只取顶层包() {
        assertThat(PythonEnv.missingModule("ModuleNotFoundError: No module named 'openpyxl.styles'"))
                .contains("openpyxl");
    }

    @Test
    void 没有缺包信息时返回空() {
        assertThat(PythonEnv.missingModule("")).isEmpty();
        assertThat(PythonEnv.missingModule("SyntaxError: invalid syntax")).isEmpty();
        assertThat(PythonEnv.missingModule(null)).isEmpty();
    }

    @Test
    void 模块名会做严格清洗_防止拼出别的命令() {
        assertThat(PythonEnv.sanitizeModule("pandas")).isEqualTo("pandas");
        assertThat(PythonEnv.sanitizeModule(" python-docx ")).isEqualTo("python-docx");
        assertThat(PythonEnv.sanitizeModule("pandas; rm -rf /")).isNull();
        assertThat(PythonEnv.sanitizeModule("a&&b")).isNull();
        assertThat(PythonEnv.sanitizeModule("")).isNull();
    }

    @Test
    void 关掉venv时不返回任何目录() {
        PythonEnv disabled = new PythonEnv(java.nio.file.Path.of("."), false);
        assertThat(disabled.binDir("1")).isEqualTo(Optional.empty());
    }
}
