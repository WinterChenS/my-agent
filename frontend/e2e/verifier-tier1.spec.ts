import { expect, test, type Page } from "@playwright/test";

/**
 * tier1-upgrades —— **独立浏览器验收**（不是构造阶段写的 e2e）。
 *
 * 目的：换一条**验证通道**——不看构造者的 e2e 结论、不调后端接口断言，
 * 只用真实浏览器操作真实 UI，然后用**眼睛能看到的界面文本**判定验收项。
 *
 * 覆盖：①多模态开关 ②上下文用量/压缩/清空 ③上一轮状态 + 后台完成提示 ④额度显示与拦截。
 */

const SHOT = "../docs/acceptance";

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

test("A1/A2 模型页多模态开关：能打开、能保存、列表如实显示", async ({ page }) => {
  await login(page);
  await page.goto("/admin/models");
  // 模型在「模型」页签里（默认是「供应商」页签）
  await page.getByRole("tab", { name: "模型" }).click();

  const row = page.locator("tr", { hasText: "deepseek/deepseek-flash" });
  await expect(row).toBeVisible({ timeout: 20000 });
  console.log("[A2] 列表初始显示:", (await row.innerText()).replace(/\s+/g, " ").slice(0, 120));
  await page.screenshot({ path: `${SHOT}/verifier-model-list-before.png`, fullPage: true });

  // 打开编辑 → 勾上「支持多模态」→ 保存
  await row.getByTitle("编辑").click();
  const toggle = page.getByTestId("model-multimodal");
  await expect(toggle).toBeVisible({ timeout: 10000 });
  console.log("[A1] 编辑弹窗里「支持多模态」开关存在；当前勾选状态 =", await toggle.isChecked());
  await toggle.check();
  await page.screenshot({ path: `${SHOT}/verifier-model-multimodal-on.png` });
  await page.getByRole("button", { name: "保存" }).click();

  // 列表应变成「多模态 开」
  await expect(page.locator("tr", { hasText: "deepseek/deepseek-flash" })).toContainText("多模态 开", {
    timeout: 20000,
  });
  console.log("[A1/A2] 保存后列表显示:「多模态 开」");
  await page.screenshot({ path: `${SHOT}/verifier-model-multimodal-open.png`, fullPage: true });

  // 复原（关掉），保持用户环境原状
  await page.locator("tr", { hasText: "deepseek/deepseek-flash" }).getByTitle("编辑").click();
  await page.getByTestId("model-multimodal").uncheck();
  await page.getByRole("button", { name: "保存" }).click();
  await expect(page.locator("tr", { hasText: "deepseek/deepseek-flash" })).toContainText("多模态 关", {
    timeout: 20000,
  });
  console.log("[A2] 复原成功，列表回到「多模态 关」");
});

test("A9/A10 输入框上方显示上下文用量与触发阈值", async ({ page }) => {
  await login(page);
  await openFirstSession(page);

  const usage = page.getByTestId("context-usage");
  await expect(usage).toBeVisible({ timeout: 20000 });
  const text = (await usage.innerText()).replace(/\s+/g, " ");
  console.log("[A9/A10] 用量条文本:", text);
  expect(text).toContain("上下文");
  expect(text).toContain("触发阈值");
  expect(text).toContain("tokens");
  await page.screenshot({ path: `${SHOT}/verifier-context-bar.png` });
});

test("A11/A12/A13/A14/A16 压缩、清空、上一轮状态都有可见反馈", async ({ page }) => {
  await login(page);
  await openFirstSession(page);

  // 压缩：点击后有反馈（无论是否真的精简，都必须给用户一句话）
  await page.getByTestId("context-compact").click();
  const notice = page.getByTestId("context-notice");
  await expect(notice).toBeVisible({ timeout: 90000 });
  console.log("[A11/A12] 压缩反馈:", await notice.innerText());
  await page.screenshot({ path: `${SHOT}/verifier-context-compact.png` });

  // 清空：反馈 + 用量归零
  await page.getByTestId("context-clear").click();
  await expect(notice).toContainText("已清空上下文", { timeout: 30000 });
  console.log("[A13] 清空反馈:", await notice.innerText());
  await expect(page.getByTestId("context-usage")).toContainText("上下文 0", { timeout: 20000 });
  console.log("[A14] 清空后用量条显示「上下文 0」，说明模型上下文已清空");
  await page.screenshot({ path: `${SHOT}/verifier-context-cleared.png` });

  // 上一轮状态（若该会话有落库记录）
  const hint = page.getByTestId("last-run-hint");
  if (await hint.count()) {
    console.log("[A16] 上一轮提示:", await hint.innerText());
  } else {
    console.log("[A16] 该会话暂无历史 run，跳过（其他会话可见，见 phase18 用例）");
  }
});

test("A19/A20 审计看板显示已用/额度，设极小额度后提示超限", async ({ page }) => {
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
});

test("A20 额度超限时聊天页给出醒目告警", async ({ page }) => {
  await login(page);
  await openFirstSession(page);

  const warn = page.getByTestId("quota-warning");
  await expect(warn).toBeVisible({ timeout: 20000 });
  const text = (await warn.innerText()).replace(/\s+/g, " ");
  console.log("[A20] 聊天页额度告警:", text);
  expect(text).toContain("额度");
  await page.screenshot({ path: `${SHOT}/verifier-chat-quota-warning.png` });
});

test("A17/A18 切走后原会话跑完会出现站内提示", async ({ page }) => {
  await login(page);

  // 在当前会话发一轮
  const input = page.getByPlaceholder("输入消息，回车发送");
  await input.fill("后台跑一轮");
  await input.press("Enter");

  // 马上切到另一个会话
  await page.waitForSelector("[data-testid='session-item']", { timeout: 30000 });
  const items = page.locator("[data-testid='session-item']");
  const count = await items.count();
  for (let i = 0; i < count; i += 1) {
    if (!(await items.nth(i).innerText()).includes("后台跑一轮")) {
      await items.nth(i).click();
      break;
    }
  }

  const notice = page.getByTestId("run-notice");
  await expect(notice).toBeVisible({ timeout: 90000 });
  console.log("[A17/A18] 站内提示:", await notice.innerText());
  await expect(page.getByTestId("run-notice-jump")).toBeVisible();
  await page.screenshot({ path: `${SHOT}/verifier-run-notice.png` });
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
