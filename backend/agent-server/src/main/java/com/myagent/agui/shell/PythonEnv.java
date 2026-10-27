package com.myagent.agui.shell;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 每个用户工作区一份 Python 虚拟环境（{@code <workspace>/<userId>/.venv}）。
 *
 * <p>为什么要 venv：
 * <ul>
 *   <li><b>自动装包不污染系统环境</b>：模型缺什么就往自己的 venv 里装，不会把宿主 Python 搞乱；</li>
 *   <li><b>不用拼绝对路径</b>：把 venv 的 Scripts/bin 前置到 PATH，模型写 {@code python x.py}、
 *       {@code pip install pandas} 就直接命中 venv（带空格的路径在不同平台加引号是坑）；</li>
 *   <li><b>跨平台</b>：Windows 是 {@code .venv/Scripts/python.exe}，Linux/macOS 是 {@code .venv/bin/python}。</li>
 * </ul>
 *
 * <p>创建是**懒加载**的（第一次执行命令时，约 5~10 秒），之后复用。系统里找不到 python 时返回空，
 * 调用方退回宿主 PATH（此时模型仍可用 python，只是不能自动装包）。
 */
public class PythonEnv {

    private static final Logger log = LoggerFactory.getLogger(PythonEnv.class);

    /** 建 venv 的超时（秒）：慢机器上首次建可能十几秒。 */
    private static final int CREATE_TIMEOUT_SECONDS = 180;

    private final Path workspaceRoot;
    private final boolean enabled;
    private final ShellRunner runner = new ShellRunner();

    /** userId → venv 的 bin 目录（创建失败也缓存，避免每次重试都卡十秒）。 */
    private final Map<String, Optional<Path>> cache = new ConcurrentHashMap<>();

    public PythonEnv(Path workspaceRoot, boolean enabled) {
        this.workspaceRoot = workspaceRoot;
        this.enabled = enabled;
    }

    /** venv 的可执行目录（用于前置 PATH）；不可用时返回空。 */
    public Optional<Path> binDir(String userId) {
        if (!enabled || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return cache.computeIfAbsent(userId, this::createOrFind);
    }

    /**
     * 缺包时自动安装。
     *
     * @param module 报错里给的模块名（会做一次严格清洗）
     * @return 是否安装成功
     */
    public boolean pipInstall(String userId, Path cwd, String module) {
        Optional<Path> bin = binDir(userId);
        if (bin.isEmpty() || module == null) {
            return false;
        }
        String pkg = sanitizeModule(module);
        if (pkg == null) {
            return false;
        }
        // 包名与 import 名常不一致（PIL→pillow、yaml→pyyaml、dateutil→python-dateutil、bs4→beautifulsoup4）
        String target = MODULE_TO_PACKAGE.getOrDefault(pkg.toLowerCase(java.util.Locale.ROOT), pkg);
        ShellRunner.Result result = runner.run(
                "python -m pip install --disable-pip-version-check -q " + target,
                cwd,
                CREATE_TIMEOUT_SECONDS,
                List.of(bin.get()));
        if (result.ok()) {
            log.info("已自动安装 Python 包 userId={} pkg={}", userId, target);
            return true;
        }
        log.warn("自动安装 Python 包失败 userId={} pkg={}: {}",
                userId, target, abbreviate(result.stderr()));
        return false;
    }

    /** import 名 → 包名（只覆盖常见几个，其余按同名处理）。 */
    private static final Map<String, String> MODULE_TO_PACKAGE = Map.of(
            "pil", "pillow",
            "yaml", "pyyaml",
            "cv2", "opencv-python",
            "dateutil", "python-dateutil",
            "bs4", "beautifulsoup4",
            "sklearn", "scikit-learn",
            "docx", "python-docx",
            "pptx", "python-pptx",
            "fitz", "pymupdf");

    private Optional<Path> createOrFind(String userId) {
        try {
            Path userWorkspace = workspaceRoot.resolve(userId).normalize();
            Files.createDirectories(userWorkspace);
            Path venv = userWorkspace.resolve(".venv");
            Path bin = isWindows() ? venv.resolve("Scripts") : venv.resolve("bin");
            Path python = bin.resolve(isWindows() ? "python.exe" : "python");
            if (Files.exists(python)) {
                return Optional.of(bin);
            }
            long started = System.currentTimeMillis();
            ShellRunner.Result result = runner.run(
                    systemPython() + " -m venv .venv", userWorkspace, CREATE_TIMEOUT_SECONDS);
            if (result.ok() && Files.exists(python)) {
                log.info("已为 userId={} 创建 Python 虚拟环境（{}ms）: {}",
                        userId, System.currentTimeMillis() - started, venv);
                return Optional.of(bin);
            }
            log.warn("创建 Python 虚拟环境失败 userId={}: {}", userId, abbreviate(result.stderr()));
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("创建 Python 虚拟环境异常 userId={}: {}", userId, ex.toString());
            return Optional.empty();
        }
    }

    /** 系统 Python 的调用方式（多平台探测顺序不同）。 */
    private String systemPython() {
        List<String> candidates = new ArrayList<>();
        if (isWindows()) {
            candidates.add("python");
            candidates.add("py -3");
            candidates.add("python3");
        } else {
            candidates.add("python3");
            candidates.add("python");
        }
        for (String candidate : candidates) {
            ShellRunner.Result probe = runner.run(candidate + " -V", Path.of(".").toAbsolutePath(), 15);
            if (probe.ok() && probe.stdout().toLowerCase(java.util.Locale.ROOT).contains("python")) {
                return candidate;
            }
        }
        return candidates.get(0);
    }

    /** 模块名清洗：只允许字母数字下划线和点，防止把命令拼出别的东西。 */
    static String sanitizeModule(String module) {
        if (module == null) {
            return null;
        }
        String trimmed = module.trim();
        if (trimmed.isEmpty() || !trimmed.matches("[A-Za-z0-9_.\\-]{1,64}")) {
            return null;
        }
        return trimmed;
    }

    /** 从 ModuleNotFoundError 的错误输出里取模块名。 */
    public static Optional<String> missingModule(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return Optional.empty();
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("No module named '([A-Za-z0-9_.\\-]+)'").matcher(stderr);
        if (matcher.find()) {
            String name = matcher.group(1);
            // 'a.b.c' → 顶层包 'a'
            int dot = name.indexOf('.');
            return Optional.of(dot > 0 ? name.substring(0, dot) : name);
        }
        return Optional.empty();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 400 ? text : text.substring(0, 400) + "…";
    }
}
