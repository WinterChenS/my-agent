import { spawn, type ChildProcess } from "node:child_process";
import { existsSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { createConnection } from "node:net";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/** ESM 下没有 __dirname，从 import.meta.url 推出来 */
const HERE = dirname(fileURLToPath(import.meta.url));

/**
 * 全局前置：**保证模型桩（:8099）在跑**。
 *
 * <p>为什么必须有这一步：大量用例（聊天 / 附件 / 产物 / 卡片 / 技能 / 额度 / 接续…）都靠本地桩
 * 充当模型，但桩原先是由外部脚本"预先启动"的 —— 一旦桩没跑，那些用例会以各种奇怪的方式失败
 * （实测：额度用例报"种数据没落 token_usage"，看着像 flaky，其实是桩根本没起来）。
 * 测试应当自给自足，所以这里由 Playwright 自己检查并**按需拉起**。
 *
 * <p>只在我们自己拉起时才在 teardown 关掉（别人已经跑着的桩不去动）。
 */
const STUB_PORT = 8099;
const STUB_SCRIPT = resolve(HERE, "../../tools/llm-stub/stub.py");
const MARKER = resolve(HERE, ".stub-started-by-tests");

function portOpen(port: number, timeoutMs = 800): Promise<boolean> {
  return new Promise((done) => {
    const socket = createConnection({ host: "127.0.0.1", port });
    const finish = (ok: boolean) => {
      socket.destroy();
      done(ok);
    };
    socket.setTimeout(timeoutMs);
    socket.once("connect", () => finish(true));
    socket.once("timeout", () => finish(false));
    socket.once("error", () => finish(false));
  });
}

/** 找可用的 python：环境变量优先，其次 PATH，最后本机 Hermes 运行时里的解释器。 */
function pythonCandidates(): ChildProcess["spawnfile"][] {
  const list: string[] = [];
  if (process.env.STUB_PYTHON) {
    list.push(process.env.STUB_PYTHON);
  }
  list.push("python", "python3");
  const hermes = process.env.LOCALAPPDATA
    ? resolve(
        process.env.LOCALAPPDATA,
        "hermes/hermes-agent/.hermes-runtime/python",
      )
    : "";
  if (hermes && existsSync(hermes)) {
    // 目录下是形如 generation-*/cpython-*/python.exe 的结构，扫一层即可
    try {
      // 不能用 require（前端是 ESM）：顶部 import 进来用
      for (const gen of readdirSync(hermes)) {
        const base = resolve(hermes, gen);
        for (const arch of readdirSync(base)) {
          const exe = resolve(base, arch, "python.exe");
          if (existsSync(exe)) {
            list.push(exe);
          }
        }
      }
    } catch {
      /* 扫不到就算了，后面还有别的候选 */
    }
  }
  return list;
}

export default async function globalSetup(): Promise<void> {
  if (await portOpen(STUB_PORT)) {
    console.log(`[global-setup] 模型桩已在 :${STUB_PORT} 运行，沿用`);
    rmSync(MARKER, { force: true });
    return;
  }
  for (const python of pythonCandidates()) {
    const child = spawn(python as string, [STUB_SCRIPT], {
      detached: true,
      stdio: "ignore",
      cwd: resolve(HERE, "../.."),
    });
    child.unref();
    for (let i = 0; i < 40; i += 1) {
      await new Promise((r) => setTimeout(r, 250));
      if (await portOpen(STUB_PORT)) {
        writeFileSync(MARKER, String(child.pid ?? ""), "utf-8");
        console.log(`[global-setup] 已用 ${python} 拉起模型桩 :${STUB_PORT}（pid=${child.pid}）`);
        return;
      }
      if (child.exitCode != null) {
        break;
      }
    }
    child.kill();
  }
  throw new Error(
    `[global-setup] 无法在 :${STUB_PORT} 启动模型桩（试过 ${pythonCandidates().join(", ")}）。` +
      `请手动运行：python tools/llm-stub/stub.py`,
  );
}
