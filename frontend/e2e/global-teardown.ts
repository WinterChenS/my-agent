import { readFileSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));

/**
 * 全局收尾：只关掉**由本次测试拉起**的模型桩（本来就在跑的不动）。
 */
export default async function globalTeardown(): Promise<void> {
  const marker = resolve(HERE, ".stub-started-by-tests");
  try {
    const pid = Number(readFileSync(marker, "utf-8").trim());
    if (pid > 0) {
      process.kill(pid);
      console.log(`[global-teardown] 已关闭本次测试拉起的模型桩（pid=${pid}）`);
    }
  } catch {
    /* 没拉起过（或已退出）：什么都不做 */
  } finally {
    rmSync(marker, { force: true });
  }
}
