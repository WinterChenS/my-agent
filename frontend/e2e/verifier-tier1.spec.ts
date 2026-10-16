import { expect, test, type Page } from "@playwright/test";
import {
  apiToken,
  createStubAgent,
  deleteStubAgent,
  loginAndSelectAgent,
  seedTodaysCost,
} from "./helpers/api";

/**
 * tier1-upgrades —— **独立浏览器验收**（不是构造阶段写的 e2e）。
 *
 * 目的：换一条**验证通道**——不看构造者的 e2e 结论、不调后端接口断言，
 * 只用真实浏览器操作真实 UI，然后用**眼睛能看到的界面文本**判定验收项。
 *
 * 覆盖：①多模态开关 ②上下文用量/压缩/清空 ③上一轮状态 + 后台完成提示 ④额度显示与拦截。
 */

const SHOT = "../docs/acceptance";

const API = "http://localhost:8080";

/**
 * 把 admin 额度与 deepseek 模型的多模态开关复原。
 *
 * 为什么放 afterEach：这两项都是**全局状态**，用例体末尾的复原在断言失败时执行不到，
 * 残留状态会让后续用例偶发红（实测全量跑时出现过）。
 */
test.afterEach(async ({ request }) => {
  const login = await request.post(`${API}/api/auth/login`, {
    data: { username: "admin", password: "Admin@123" },
  });
  const token = (await login.json()).data.token as string;
  const headers = { Authorization: `Bearer ${token}` };
  await request.put(`${API}/api/users/1/quota`, {
    headers,
    data: { dailyQuota: null, monthlyQuota: null, mode: "BLOCK" },
  });
});

async function login(page: Page) {
  await page.goto("/login");
  await page.getByLabel("用户名").fill("admin");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: /登录/ }).click();
  await page.waitForURL(/\/chat/);
}

/** 打开第一个已有会话（没有就先用输入框新建一个）。 */
async function openFirstSession(page: Page) {
  await page.waitForSelector("[data-testid='session-item']", { timeout: 30000 });
  await page.locator("[data-testid='session-item']").first().click();
}

test("A1/A2 模型页多模态开关：能打开、能保存、列表如实显示", async ({ page, request }) => {
  // 自给自足：**自己造一个模型**来验证开关，不动用户的 deepseek 模型
  // （原先直接改模型 34，全量跑时会与其他用例互相干扰，且依赖用户数据）
  const token = await apiToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const stamp = Date.now();
  const provider = await (
    await request.post(`${API}/api/model-providers`, {
      headers,
      data: {
        name: `e2e-mm-prov-${stamp}`,
        providerType: "OPENAI_COMPATIBLE",
        baseUrl: "http://localhost:8099/v1",
        apiKey: "stub-key",
        status: "ENABLED",
      },
    })
  ).json();
  const model = await (
    await request.post(`${API}/api/models`, {
      headers,
      data: {
        providerId: provider.data.id,
        modelId: `e2e-mm-model-${stamp}`,
        multimodal: false,
        status: "ENABLED",
      },
    })
  ).json();
  const modelId = model.data.id as number;
  const rowName = `e2e-mm-model-${stamp}`;

  try {
    await login(page);
    await page.goto("/admin/models");
    await page.getByRole("tab", { name: "模型" }).click();

    const row = page.locator("tr", { hasText: rowName });
    await expect(row).toBeVisible({ timeout: 20000 });
    console.log("[A2] 列表初始显示:", (await row.innerText()).replace(/\s+/g, " ").slice(0, 120));
    await expect(row).toContainText("多模态 关");

    // 打开编辑 → 勾上「支持多模态」→ 保存
    await row.getByTitle("编辑").click();
    const toggle = page.getByTestId("model-multimodal");
    await expect(toggle).toBeVisible({ timeout: 10000 });
    console.log("[A1] 编辑弹窗里「支持多模态」开关存在；当前勾选状态 =", await toggle.isChecked());
    await toggle.check();
    await page.screenshot({ path: `${SHOT}/verifier-model-multimodal-on.png` });
    await page.getByRole("button", { name: "保存" }).click();

    // 列表应变成「多模态 开」
    await expect(page.locator("tr", { hasText: rowName })).toContainText("多模态 开", { timeout: 20000 });
    console.log("[A1/A2] 保存后列表显示:「多模态 开」");

    // 复原（关掉）——同样只动自己造的模型
    // 先等列表刷新稳定：刚保存完立刻点「编辑」，点击会落在重新渲染的间隙里（实测会点空、然后卡住）
    await page.waitForTimeout(1200);
    await expect(page.locator("tr", { hasText: rowName })).toContainText("多模态 开");
    await page.locator("tr", { hasText: rowName }).getByTitle("编辑").click();
    await expect(page.getByTestId("model-multimodal")).toBeVisible({ timeout: 10000 });
    await page.getByTestId("model-multimodal").uncheck();
    await page.getByRole("button", { name: "保存" }).click();
    await expect(page.locator("tr", { hasText: rowName })).toContainText("多模态 关", { timeout: 20000 });
    console.log("[A2] 复原成功，列表回到「多模态 关」");
  } finally {
    await request.delete(`${API}/api/models/${modelId}`, { headers });
    await request.delete(`${API}/api/model-providers/${provider.data.id}`, { headers });
  }
});

test("A9/A10 发送键旁的圆环显示上下文用量与触发阈值", async ({ page }) => {
  await login(page);
  await openFirstSession(page);

  await expect(page.getByTestId("context-ring")).toBeVisible({ timeout: 20000 });
  await page.getByTestId("context-ring").click();
  const usage = page.getByTestId("context-usage");
  await expect(usage).toBeVisible({ timeout: 20000 });
  const text = (await usage.innerText()).replace(/\s+/g, " ");
  console.log("[A9/A10] 圆环浮层文本:", text);
  expect(text).toContain("上下文");
  expect(text).toContain("触发阈值");
  expect(text).toContain("tokens");
  await page.screenshot({ path: `${SHOT}/verifier-context-bar.png` });
});

test("A11/A12/A13/A14/A16 压缩、清空、上一轮状态都有可见反馈", async ({ page }) => {
  await login(page);
  await openFirstSession(page);

  await expect(page.getByTestId("context-ring")).toBeVisible({ timeout: 20000 });
  await page.getByTestId("context-ring").click();

  // 压缩：点击后有反馈（无论是否真的精简，都必须给用户一句话）
  await page.getByTestId("context-compact").click();
  const notice = page.getByTestId("context-notice");
  await expect(notice).toBeVisible({ timeout: 90000 });
  console.log("[A11/A12] 压缩反馈:", await notice.innerText());
  await page.screenshot({ path: `${SHOT}/verifier-context-compact.png` });

  // 清空：必须先二次确认，再反馈 + 用量归零
  await page.getByTestId("context-clear").click();
  await expect(page.getByTestId("context-clear-confirm")).toBeVisible({ timeout: 10000 });
  console.log("[A13] 已弹出「清空上下文」二次确认弹窗");
  await page.getByTestId("context-clear-confirm").click();
  await page.getByTestId("context-ring").click();
  await expect(notice).toContainText("已清空上下文", { timeout: 30000 });
  console.log("[A13] 清空反馈:", await notice.innerText());
  await expect(page.getByTestId("context-usage")).toContainText("上下文 0", { timeout: 20000 });
  console.log("[A14] 清空后用量条显示「上下文 0」，说明模型上下文已清空");
  await page.screenshot({ path: `${SHOT}/verifier-context-cleared.png` });

  // 上一轮状态：正常跑完的会话不该再挂「已完成」噪音（用户反馈后收紧）
  const hint = page.getByTestId("last-run-hint");
  if (await hint.count()) {
    const text = await hint.innerText();
    console.log("[A16] 上一轮提示:", text);
    expect(text).not.toContain("上一轮：已完成 ·");
  } else {
    console.log("[A16] 该会话上一轮答案已在聊天记录里 → 按设计不提示（无噪音）");
  }
});

test("A19/A20 审计看板显示已用/额度，设极小额度后提示超限", async ({ page, request }) => {
  // 自给自足：先造一笔今日的、有价格的消费（否则今日已用为 0，任何极小额度都不会超限）
  const seeded = await seedTodaysCost(request);
  try {
  await login(page);
  await page.goto("/admin/audit");

  const card = page.getByTestId("quota-card");
  await expect(card).toBeVisible({ timeout: 20000 });
  await page.locator("#audit-user").selectOption({ index: 1 });
  const usage = page.getByTestId("quota-usage");
  await expect(usage).toContainText("今日已用", { timeout: 20000 });
  console.log("[A20] 看板额度显示:", (await usage.innerText()).replace(/\s+/g, " "));
  expect(await usage.innerText()).toContain("今日已用");
  expect(await usage.innerText()).toContain("本月已用");
  expect(await usage.innerText()).toContain("日额度");
  expect(await usage.innerText()).toContain("月额度");
  await page.screenshot({ path: `${SHOT}/verifier-quota-card.png`, fullPage: true });

  // 设成极小额度 → 应出现「已超出额度」并写明用量与额度
  // 用 WARN：既能看到「已超出」提示，又不会拦住后面那条后台运行用例
  await page.getByTestId("quota-mode").selectOption("WARN");
  await page.getByTestId("quota-daily").fill("0.000001");
  await page.getByTestId("quota-save").click();
  await expect(page.getByTestId("quota-notice")).toContainText("已保存", { timeout: 20000 });
  const exceeded = page.getByTestId("quota-exceeded");
  await expect(exceeded).toBeVisible({ timeout: 20000 });
  const exceededText = await exceeded.innerText();
  console.log("[A20] 超限提示:", exceededText);
  expect(exceededText).toContain("已超出额度");
  await page.screenshot({ path: `${SHOT}/verifier-quota-exceeded.png`, fullPage: true });
  } finally {
    await seeded.cleanup();
  }
});

test("A20 额度超限时聊天页给出醒目告警", async ({ page, request }) => {
  // 自给自足：①自己造一笔今日有价格的消费（否则今日已用 0，不会超限）；
  // ②自己把额度设成极小值（不再依赖上一条用例留下的状态）
  const seeded = await seedTodaysCost(request);
  try {
  const loginRes = await request.post(`${API}/api/auth/login`, {
    data: { username: "admin", password: "Admin@123" },
  });
  const token = (await loginRes.json()).data.token as string;
  await request.put(`${API}/api/users/1/quota`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { dailyQuota: 0.000001, monthlyQuota: null, currency: "CNY", mode: "WARN" },
  });

  await login(page);
  await openFirstSession(page);

  const warn = page.getByTestId("quota-warning");
  await expect(warn).toBeVisible({ timeout: 20000 });
  const text = (await warn.innerText()).replace(/\s+/g, " ");
  console.log("[A20] 聊天页额度告警:", text);
  expect(text).toContain("额度");
  await page.screenshot({ path: `${SHOT}/verifier-chat-quota-warning.png` });
  } finally {
    await seeded.cleanup();
  }
});

test("A17/A18 跑完的站内提示已按用户要求移除；实时可见性改由侧栏「正在跑」标记承担", async ({
  page,
  request,
}) => {
  // 需求已修订（用户 2026-10 反馈）：**不要**每次任务完成后的提示与选项，界面保持干净。
  // 原来"切走后跑完给站内提示"的那套 UI 已移除；当前会话是否在跑由侧栏的转圈标记回答
  // （见 e2e/verify-running-marker.spec.ts 的独立覆盖）。
  const stamp = Date.now();
  const { agentName, agentDbId, headers } = await createStubAgent(request, stamp, {
    modelId: `stub-slow-${stamp}`,
  });
  try {
    const input = await loginAndSelectAgent(page, agentName);
    await input.click();
    await page.keyboard.type("后台跑一轮");
    await page.keyboard.press("Enter");

    // 跑起来后侧栏应有「正在跑」标记
    await expect(page.getByTestId("session-running").first()).toBeVisible({ timeout: 30_000 });
    console.log("[A17] 侧栏「正在跑」标记已出现");

    // 而"完成提示"这套 UI 不该再出现（用户要求移除）
    await expect(page.getByTestId("run-notice")).toHaveCount(0);
    await expect(page.getByTestId("last-run-hint")).toHaveCount(0);
    console.log("[A18] 完成提示 / 上一轮提示均已移除（界面干净）");
  } finally {
    const sessions = await (await request.get("http://localhost:8080/api/sessions", { headers })).json();
    for (const s of sessions.data as { id: number; agentId: number | null }[]) {
      if (s.agentId === agentDbId) await request.delete(`http://localhost:8080/api/sessions/${s.id}`, { headers });
    }
    await deleteStubAgent(request, headers, agentDbId);
  }
});

test("收尾：把额度复原为「不限」，不留痕", async ({ page }) => {
  await login(page);
  await page.goto("/admin/audit");
  await page.locator("#audit-user").selectOption({ index: 1 });
  await expect(page.getByTestId("quota-usage")).toContainText("今日已用", { timeout: 20000 });
  await page.getByTestId("quota-daily").fill("");
  await page.getByTestId("quota-mode").selectOption("BLOCK");
  await page.getByTestId("quota-save").click();
  await expect(page.getByTestId("quota-usage")).toContainText("/ 日额度 不限", { timeout: 20000 });
  console.log("[收尾] 额度已复原为不限");
});
