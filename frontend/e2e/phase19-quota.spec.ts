import { expect, test, type APIRequestContext } from "@playwright/test";
import { seedTodaysCost } from "./helpers/api";

const API = "http://localhost:8080";

/**
 * 把 admin 的额度复原为「不限」。
 *
 * 为什么必须放在 afterEach：本用例会改**全局状态**（额度），若中途断言失败、
 * 复原代码在用例体末尾就执行不到 → 残留的极小额度会污染后续用例
 * （实测全量跑时 verifier-tier1 的额度用例因此偶发红）。
 */
async function resetQuota(request: APIRequestContext) {
  const login = await request.post(`${API}/api/auth/login`, {
    data: { username: "admin", password: "Admin@123" },
  });
  const token = (await login.json()).data.token as string;
  await request.put(`${API}/api/users/1/quota`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { dailyQuota: null, monthlyQuota: null, mode: "BLOCK" },
  });
}

test.afterEach(async ({ request }) => {
  await resetQuota(request);
});

/** tier1-upgrades ④：审计看板显示已用/额度，并可设置额度。 */
test("审计看板显示支出额度并可设置", async ({ page, request }) => {
  // 自给自足：先造一笔**今日的、有价格**的消费。
  // 否则今日已用为 0，无论额度设多小都不会超限（exceeds(0, 0.000001) 为假），用例会假红（实测踩过）
  const seeded = await seedTodaysCost(request);
  try {
  await page.goto("/login");
  await page.getByLabel("用户名").fill("admin");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: /登录/ }).click();
  await page.waitForURL(/\/chat/);

  await page.goto("/admin/audit");
  const card = page.getByTestId("quota-card");
  await expect(card).toBeVisible({ timeout: 20000 });

  // 选 admin 用户，额度卡片会载入
  // 按 value 选 admin（user id=1，与 resetQuota 一致）：
  // 用 index 选会在别的用例造了其它有消耗的用户时选错人，导致额度卡片读的不是 admin（实测全量跑偶发红）
  //
  // 并且**等这次额度请求真的回来**再往下走：loadQuota 是"选完用户后异步拉"的，
  // 若在它返回前就填输入框，晚到的响应会把填入的值重置掉，保存进去的其实是空值（=不限），
  // 于是「已超出」横幅永远不出现（实测全量跑必现，单独跑只是后端更快、恰好没撞上）。
  // 产品侧已加了"同一用户的后台重拉不覆盖输入框"的保护；这里再让用例本身不依赖时序。
  const quotaLoaded = page.waitForResponse(
    (r) => /\/api\/users\/\d+\/quota$/.test(new URL(r.url()).pathname) && r.request().method() === "GET",
  );
  await page.locator("#audit-user").selectOption("1");
  await quotaLoaded;
  await expect(page.getByTestId("quota-usage")).toContainText("今日已用", { timeout: 15000 });
  await expect(page.getByTestId("quota-usage")).toContainText("本月已用");
  console.log("额度显示:", (await page.getByTestId("quota-usage").innerText()).replace(/\s+/g, " "));

  // 设一个很小额度 → 保存 → 出现「已超出」提示
  await page.getByTestId("quota-daily").fill("0.000001");
  await page.getByTestId("quota-mode").selectOption("BLOCK");
  await page.getByTestId("quota-save").click();
  await expect(page.getByTestId("quota-notice")).toContainText("已保存", { timeout: 15000 });
  /*
   * 诊断：**始终打印**（不再用环境变量门控）。
   * 原因：上一轮全量里这条用例失败，但诊断被门控住没输出 → 白跑一次没法定位。
   * 三行日志成本可忽略，换来"失败时自己说明问题"。
   */
  {
    const token2 = (await (await request.post(`${API}/api/auth/login`, { data: { username: "admin", password: "Admin@123" } })).json()).data.token as string;
    const raw = await (
      await request.get(`${API}/api/users/1/quota`, { headers: { Authorization: `Bearer ${token2}` } })
    ).json();
    const recs = await (
      await request.get(`${API}/api/audit/token/records?current=1&size=3`, {
        headers: { Authorization: `Bearer ${token2}` },
      })
    ).json();
    console.log(`[诊断] users/1/quota = ${JSON.stringify(raw.data)}`);
    console.log(
      `[诊断] 最近用量行 = ${JSON.stringify(
        (recs.data.records as Record<string, unknown>[]).map((r) => ({
          session: r.sessionId,
          modelId: r.modelId,
          model: r.modelName,
          total: r.totalTokens,
          at: r.createdAt,
        })),
      )}`,
    );
    console.log(`[诊断] 页面额度文本 = ${JSON.stringify((await page.getByTestId("quota-usage").innerText()).replace(/\s+/g, " "))}`);
  }
  await expect(page.getByTestId("quota-exceeded")).toBeVisible({ timeout: 15000 });
  console.log("超限提示:", await page.getByTestId("quota-exceeded").innerText());

  // 清空额度（恢复不限）
  await page.getByTestId("quota-daily").fill("");
  await page.getByTestId("quota-save").click();
  await expect(page.getByTestId("quota-usage")).toContainText("/ 日额度 不限", { timeout: 15000 });
  } finally {
    await seeded.cleanup();
  }
});

/**
 * 回归（本次修复）：**同一用户的后台重拉不得覆盖正在输入的额度**。
 *
 * <p>为什么：改筛选条件（日期）会让 `load()` 重跑，它顺带调一次 `loadQuota(同一用户)`。
 * 旧实现每次都把输入框重置成服务端值 —— 用户刚填好额度、还没点保存就被清空，
 * 点保存提交的其实是空值（=不限）。全量跑时的表现就是「设了极小额度却没有超限提示」：
 * phase19 主用例正是这样红的（单独跑只是后端更快、恰好没撞上）。
 *
 * <p>这里**确定性地**把那个时序造出来：先等额度到手，再填额度，再改日期触发一次重拉。
 * 把输入框重置的实现会立刻把填好的值清掉 → 必红。
 */
test("筛选重拉（同一用户）不会清空正在输入的额度", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("用户名").fill("admin");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: /登录/ }).click();
  await page.waitForURL(/\/chat/);

  await page.goto("/admin/audit");
  await expect(page.getByTestId("quota-card")).toBeVisible({ timeout: 20000 });

  const quotaGet = () =>
    page.waitForResponse(
      (r) => /\/api\/users\/\d+\/quota$/.test(new URL(r.url()).pathname) && r.request().method() === "GET",
    );

  const first = quotaGet();
  await page.locator("#audit-user").selectOption("1");
  await first;

  // 填入一个极小额度（先不保存）
  await page.getByTestId("quota-daily").fill("0.000001");

  // 改日期 → 触发一次「同一用户」的后台重拉
  const again = quotaGet();
  await page.locator("#audit-from").fill("2026-01-01");
  await again;

  // 重拉回来了，但用户填的值必须还在（否则保存进去的就是空值=不限）
  await expect(page.getByTestId("quota-daily")).toHaveValue("0.000001");
  console.log("[额度] 筛选重拉后输入框仍保留用户填的值 ✓");
});
