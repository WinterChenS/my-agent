import { spawn } from "node:child_process";
import { existsSync, readdirSync } from "node:fs";
import { createConnection } from "node:net";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * 按需拉起一个**「会调某个工具」的模型桩**（独立端口，用完就关）。
 *
 * <p>为什么需要：有些用例要验证的是“真实链路真的产出了数据”，比如“另一个用户聊一轮后
 * 记忆里真的有东西”。默认那个桩（:8099）永远只回一段文字、不调任何工具 ——
 * 拿它测记忆只能靠调接口硬塞数据，那就不是“用户自己产生的数据”了。
 *
 * <p>为什么不改全局 `STUB_TOOL_NAME`：那个环境变量是**整进程共享**的，
 * 改了会让同一次运行里的其它用例（产物、卡片、MCP）全部收到意料之外的工具调用
 * —— 项目里已经吃过“改全局桩配置污染整轮 e2e”的亏。所以这里单独开端口、单独配环境。
 */
const HERE = dirname(fileURLToPath(import.meta.url));

export const STUB_SCRIPT = resolve(HERE, "../../../tools/llm-stub/stub.py");

function sleep(ms: number): Promise<void> {
  return new Promise((done) => setTimeout(done, ms));
}

export function portOpen(port: number, timeoutMs = 800): Promise<boolean> {
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
function pythonCandidates(): string[] {
  const list: string[] = [];
  if (process.env.STUB_PYTHON) {
    list.push(process.env.STUB_PYTHON);
  }
  list.push("python", "python3");
  const hermes = process.env.LOCALAPPDATA
    ? resolve(process.env.LOCALAPPDATA, "hermes/hermes-agent/.hermes-runtime/python")
    : "";
  if (hermes && existsSync(hermes)) {
    try {
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

export interface StubHandle {
  /** 实际用的端口（可能与建议值不同：被占用时自己找个空的） */
  port: number;
  stop: () => Promise<void>;
}

/**
 * 起一个桩，并把它配置成“第一次请求就调 `toolName`、参数为 `toolArgs`”。
 *
 * <p>**绝不复用已经在跑的端口**：上一轮如果崩在收尾之前，那个“僵尸桩”会带着**旧的**
 * `STUB_TOOL_ARGS` 继续监听，测试就会拿到上一轮的数据并以“内容不对超时”的形式莫名其妙地红
 * —— 这个坑本项目实测踩过。所以端口被占就往后找一个空的（`preferredPort`, +1, +2 …），
 * 并把真正用的端口告诉调用方。
 *
 * @param preferredPort 建议端口（别用 8099，那是全局桩）
 * @param toolName 工具名片段（大小写不敏感），例如 `memory_save`
 * @param toolArgs 工具参数（JSON 字符串）
 */
export async function startToolStub(
  preferredPort: number,
  toolName: string,
  toolArgs: string,
): Promise<StubHandle> {
  let port = preferredPort;
  for (let i = 0; i < 20 && (await portOpen(port)); i += 1) {
    console.log(`[tool-stub] :${port} 已被占用（可能是上一轮的僵尸桩），换下一个端口`);
    port += 1;
  }
  if (await portOpen(port)) {
    throw new Error(`[tool-stub] 附近端口全被占用（从 :${preferredPort} 起试了 20 个）`);
  }

  const tried: string[] = [];
  for (const python of pythonCandidates()) {
    tried.push(python);
    const child = spawn(python, [STUB_SCRIPT], {
      detached: true,
      stdio: "ignore",
      cwd: resolve(HERE, "../../.."),
      env: {
        ...process.env,
        PORT: String(port),
        STUB_TOOL_NAME: toolName,
        STUB_TOOL_ARGS: toolArgs,
        STUB_TOOL_ROUNDS: "1",
      },
    });
    child.unref();
    for (let i = 0; i < 40; i += 1) {
      await sleep(250);
      if (await portOpen(port)) {
        console.log(`[tool-stub] 已用 ${python} 拉起 :${port}（工具=${toolName}，pid=${child.pid}）`);
        return {
          port,
          stop: async () => {
            if (child.pid != null) {
              try {
                process.kill(child.pid);
                console.log(`[tool-stub] 已关闭 :${port}（pid=${child.pid}）`);
              } catch {
                /* 已经退了 */
              }
            }
          },
        };
      }
      if (child.exitCode != null) {
        break;
      }
    }
    child.kill();
  }
  throw new Error(
    `[tool-stub] 无法在 :${port} 启动模型桩（试过 ${tried.join(", ")}）。` +
      `请手动运行：python tools/llm-stub/stub.py`,
  );
}
