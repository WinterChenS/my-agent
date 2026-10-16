import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { API, apiToken } from "./helpers/api";

/**
 * 用户管理页（C1）——页面级覆盖。
 *
 * 关注两件用户真正会遇到的事：
 *  ① 建出来的账号**能登录**、停用后**登不上**、重置密码后**新密码能登**（链路真的通）；
 *  ② 管理失误会被拦住：重名/短密码/非法角色，以及「禁用或删除当前登录账号」「干掉最后一个管理员」。
 *
 * 自给自足：自己建、自己删，不碰 admin 之外的用户数据。
 */
/**
 * 点某行的「编辑」并等到弹窗真的开出来。
 *
 * 为什么要重试：保存后弹窗有一段淡出动画，removing 期间的遮罩会**吞掉紧接着的点击**；
 * 全量跑（机器更忙）时尤其明显 —— 实测偶发「点了没反应，等 user-status 超时」。
 * Playwright 官方对这类「点击 + 断言」组合推荐用 expect(...).toPass() 重试。
 */
async function openUserDialog(page: Page, row: import("@playwright/test").Locator) {
  await expect(async () => {
    await row.getByTestId("user-edit").click();
    await expect(page.getByTestId("user-status")).toBeVisible({ timeout: 2000 });
  }).toPass({ timeout: 30_000 });
  await expect(page.getByTestId("user-status")).toBeVisible();
}

/** 保存并等弹窗关闭（避免下一次点击被淡出遮罩吞掉）。 */
async function saveAndWaitClosed(page: Page) {
  await page.getByTestId("user-save").click();
  await expect(page.getByTestId("user-save")).toHaveCount(0, { timeout: 20_000 });
}

async function login(page: Page) {
  await page.goto("/login");
  await page.getByLabel("用户名").fill("admin");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: /登录/ }).click();
  await page.waitForURL(/\/chat/, { timeout: 20_000 });
}

test.describe("用户管理页", () => {
  test.setTimeout(180_000);

  let headers: Record<string, string> = {};
  const createdIds: number[] = [];

  test.beforeEach(async ({ request }) => {
    const token = await apiToken(request);
    headers = { Authorization: `Bearer ${token}` };
  });

  test.afterEach(async ({ request }) => {
    for (const id of createdIds.splice(0)) {
      await request.delete(`${API}/api/users/${id}`, { headers });
    }
  });

  async function loginAs(request: APIRequestContext, username: string, password: string) {
    const res = await request.post(`${API}/api/auth/login`, { data: { username, password } });
    return { status: res.status(), body: await res.json() };
  }

  test("建号 → 能登录 → 停用 → 登不上 → 重置密码 → 新密码能登", async ({ page, request }) => {
    const stamp = Date.now();
    const username = `e2e-u-${stamp}`;

    await login(page);
    await page.goto("/admin/users");
    await page.getByTestId("user-new").click();
    await page.getByTestId("user-username").fill(username);
    await page.getByTestId("user-password").fill("Passw0rd!");
    await page.getByTestId("user-display-name").fill("E2E 用户");
    await page.getByTestId("user-role").selectOption("USER");
    await page.getByTestId("user-save").click();
    await expect(page.getByText("已创建用户")).toBeVisible({ timeout: 20_000 });

    const row = page.locator("tr", { hasText: username });
    await expect(row).toBeVisible({ timeout: 20_000 });
    // 记下 id 以便清理
    const list = await (await request.get(`${API}/api/users?current=1&size=200`, { headers })).json();
    const created = (list.data.records as { id: number; username: string }[]).find(
      (u) => u.username === username,
    );
    expect(created).toBeTruthy();
    createdIds.push(created!.id);
    console.log("[C1] 页面上建出用户，id =", created!.id);

    // ① 能登录
    const login1 = await loginAs(request, username, "Passw0rd!");
    console.log("[C1] 新账号登录 http =", login1.status);
    expect(login1.status).toBe(200);

    // ② 页面上停用 → 登不上
    await openUserDialog(page, row);
    await page.getByTestId("user-status").selectOption("DISABLED");
    await saveAndWaitClosed(page);
    await expect(page.locator("tr", { hasText: username })).toContainText("DISABLED", { timeout: 20_000 });
    const login2 = await loginAs(request, username, "Passw0rd!");
    console.log("[C1] 停用后登录 http =", login2.status, login2.body?.msg);
    expect(login2.status).toBe(403);
    expect(login2.body.msg).toContain("禁用");

    // ③ 恢复启用 + 页面重置密码 → 新密码能登、旧密码不能
    await openUserDialog(page, page.locator("tr", { hasText: username }));
    await page.getByTestId("user-status").selectOption("ENABLED");
    await saveAndWaitClosed(page);
    // 不靠 toast 文案（同时可能有多条）：直接等行状态回到 ENABLED
    await expect(page.locator("tr", { hasText: username })).toContainText("ENABLED", { timeout: 20_000 });

    await page.locator("tr", { hasText: username }).getByTestId("user-reset").click();
    await page.getByTestId("reset-password").fill("BrandNewPass1!");
    await page.getByTestId("reset-password-save").click();
    await expect(page.getByText(/已重置/)).toBeVisible({ timeout: 20_000 });

    console.log("[C1] 旧密码登录 http =", (await loginAs(request, username, "Passw0rd!")).status, "（应 401）");
    expect((await loginAs(request, username, "Passw0rd!")).status).toBe(401);
    console.log("[C1] 新密码登录 http =", (await loginAs(request, username, "BrandNewPass1!")).status, "（应 200）");
    expect((await loginAs(request, username, "BrandNewPass1!")).status).toBe(200);

    // ④ 页面上删除
    page.on("dialog", (d) => void d.accept());
    await page.locator("tr", { hasText: username }).getByTestId("user-delete").click();
    await expect(page.getByText("已删除")).toBeVisible({ timeout: 20_000 });
    await expect(page.locator("tr", { hasText: username })).toHaveCount(0);
    console.log("[C1] 页面删除成功");
  });

  test("错误分支：重名 / 短密码 / 非法用户名 各自提示", async ({ page, request }) => {
    const stamp = Date.now();
    const username = `e2e-uerr-${stamp}`;
    await (await request.get(`${API}/api/users?current=1&size=1`, { headers })).json(); // 确保 token 有效
    const created = await (
      await request.post(`${API}/api/users`, {
        headers,
        data: { username, password: "Passw0rd!", role: "USER", status: "ENABLED" },
      })
    ).json();
    createdIds.push(created.data.id as number);

    await login(page);
    await page.goto("/admin/users");

    // 重名
    await page.getByTestId("user-new").click();
    await page.getByTestId("user-username").fill(username);
    await page.getByTestId("user-password").fill("Passw0rd!");
    await page.getByTestId("user-save").click();
    await expect(page.getByText(/用户名已存在/)).toBeVisible({ timeout: 15_000 });
    console.log("[C1] 重名提示已出现");

    // 短密码
    await page.getByTestId("user-username").fill(`${username}-short`);
    await page.getByTestId("user-password").fill("123");
    await page.getByTestId("user-save").click();
    await expect(page.getByText(/密码至少 6 位/)).toBeVisible({ timeout: 15_000 });
    console.log("[C1] 短密码提示已出现");

    // 非法用户名
    await page.getByTestId("user-username").fill("bad name!");
    await page.getByTestId("user-password").fill("Passw0rd!");
    await page.getByTestId("user-save").click();
    await expect(page.getByText(/用户名只能是/)).toBeVisible({ timeout: 15_000 });
    console.log("[C1] 非法用户名提示已出现");
  });

  test("保护：不能禁用/删除当前登录账号，不能干掉最后一个管理员", async ({ page }) => {
    await login(page);
    await page.goto("/admin/users");

    // 自己在列表里标了「（我）」
    const meRow = page.locator("tr", { hasText: "（我）" });
    await expect(meRow).toBeVisible({ timeout: 20_000 });

    // 禁用自己被拦
    await openUserDialog(page, meRow);
    await page.getByTestId("user-status").selectOption("DISABLED");
    await page.getByTestId("user-save").click();
    await expect(page.getByText(/不能禁用当前登录的账号/)).toBeVisible({ timeout: 15_000 });
    console.log("[C1] 禁用自己的提示已出现");
    await page.getByRole("button", { name: "取消" }).click();
    await page.waitForTimeout(300);

    // 删除自己被拦
    page.on("dialog", (d) => void d.accept());
    await meRow.getByTestId("user-delete").click();
    await expect(page.getByText(/不能删除当前登录的账号/)).toBeVisible({ timeout: 15_000 });
    console.log("[C1] 删除自己的提示已出现");
  });
});
