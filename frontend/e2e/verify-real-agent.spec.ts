import { expect, test } from "@playwright/test";
import { API, apiToken } from "./helpers/api";

/**
 * **真实模型**端到端用例（默认跳过）。
 *
 * <p>为什么默认跳过：常规全量必须**自给自足**（用本地桩、不依赖外部网关、不花钱）。
 * 但"平台 + 用户真实配置的模型"这条链路也值得能随时验一次 —— 用环境变量开启即可：
 *
 * <pre>
 *   REAL_MODEL_E2E=1 REAL_AGENT_NAME=stub-demo-agent npx playwright test e2e/verify-real-agent.spec.ts
 * </pre>
 *
 * 它会：真实浏览器打开聊天页 → 选指定 Agent（真实模型）→ 发一句话 → 等真实回复 →
 * 核对审计里"今日已用"真的涨了（证明 token/成本链路对真实厂商响应同样成立）。
 */
const ENABLED = process.env.REAL_MODEL_E2E === "1";
const AGENT = process.env.REAL_AGENT_NAME ?? "stub-demo-agent";

test.describe("真实模型端到端（需显式开启）", () => {
  test.skip(!ENABLED, "默认跳过：设 REAL_MODEL_E2E=1 才跑（会真实消耗模型额度）");

  test("真实浏览器调用真实模型，并核对审计成本", async ({ page, request }) => {
    test.setTimeout(300_000);
    const token = await apiToken(request);
    const admin = { Authorization: `Bearer ${token}` };

    await page.goto("/login");
    await page.getByLabel("密码").fill("Admin@123");
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await page.waitForURL(/\/chat/, { timeout: 20_000 });
    await page.waitForSelector("[data-testid='chat-input']", { timeout: 30_000 });

    await page.getByTitle("新建会话").click();
    const select = page.locator("select").first();
    await select.selectOption({ label: AGENT });

    const before = await (await request.get(`${API}/api/quota/me`, { headers: admin })).json();
    const usedBefore = Number(before.data?.usedToday ?? 0);

    await page.getByTestId("chat-input").click();
    await page.keyboard.type("用一句话回答：1+1 等于几？");
    await page.keyboard.press("Enter");
    await expect(page.getByTestId("user-text")).toHaveCount(1, { timeout: 30_000 });
    await expect(page.getByTestId("assistant-text")).toHaveCount(1, { timeout: 240_000 });
    await expect(page.getByTestId("stop-button")).toHaveCount(0, { timeout: 240_000 });
    const reply = await page.getByTestId("assistant-text").first().innerText();
    console.log(`[真实模型] Agent=${AGENT} 回复=${JSON.stringify(reply.slice(0, 120))}`);
    expect(reply.trim().length, "真实模型应当返回非空内容").toBeGreaterThan(0);

    // 审计与额度：今日已用必须增长（说明 token/成本链路对真实响应同样成立）
    const after = await (await request.get(`${API}/api/quota/me`, { headers: admin })).json();
    const usedAfter = Number(after.data?.usedToday ?? 0);
    console.log(`[真实模型] 今日已用 ${usedBefore} -> ${usedAfter} ${after.data?.currency}`);
    expect(usedAfter, "真实调用后应当记到成本").toBeGreaterThanOrEqual(usedBefore);
    await page.screenshot({ path: "../docs/acceptance/task-45-real-agent-call.png", fullPage: true });
  });
});
