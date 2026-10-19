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
  await page.locator("#audit-user").selectOption("1");
  await expect(page.getByTestId("quota-usage")).toContainText("今日已用", { timeout: 15000 });
  await expect(page.getByTestId("quota-usage")).toContainText("本月已用");
  console.log("额度显示:", (await page.getByTestId("quota-usage").innerText()).replace(/\s+/g, " "));

  // 设一个很小额度 → 保存 → 出现「已超出」提示
  await page.getByTestId("quota-daily").fill("0.000001");
  await page.getByTestId("quota-mode").selectOption("BLOCK");
  await page.getByTestId("quota-save").click();
  await expect(page.getByTestId("quota-notice")).toContainText("已保存", { timeout: 15000 });
  // 诊断（失败时一次问清）：接口原始返回到底是什么
  if (process.env.QUOTA_DEBUG === "1") {
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
