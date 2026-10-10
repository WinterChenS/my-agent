import { defineConfig, devices } from "@playwright/test";

/**
 * 浏览器级验收（Task 4.3 的视觉门禁）。
 *
 * 使用本机已安装的 Chrome（channel: "chrome"），不下载 Playwright 自带浏览器。
 * 后端、模型桩、Vite 由验收脚本预先启动（见 scripts/e2e-chat.sh）。
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 90_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  outputDir: "../docs/acceptance/playwright-report",
  use: {
    baseURL: "http://localhost:5173",
    channel: "chrome",
    headless: true,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    ...devices["Desktop Chrome"],
  },
});
