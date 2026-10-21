import { expect, test, type Page } from "@playwright/test";
import { API, apiToken } from "./helpers/api";

/**
 * 「记忆」的权限：**超管能看别人的，普通用户只能看自己的**。
 *
 * <p>为什么要有这条用例：`/api/memory/**` 不在 `SecurityConfig` 的 `CONFIG_PATHS` 里
 * （那条只管配置类接口的写操作），所以越权是**安全配置兜不住**的，只能靠控制器里的判定。
 * 这类漏洞不会自己报错 —— 普通用户传个 `?userId=` 就能读到别人的记忆，页面上一切正常。
 *
 * <p>自给自足：自己建一个临时 USER、给它塞一份台账，测完删台账 + 删用户。
 */
const TEST_FILE = "2020-01-02.md";
const MARK = `MEM-XUSER-${Date.now()}`;
const PASSWORD = "Passw0rd!";

async function login(page: Page, username: string, password: string) {
  await page.goto("/login");
  await page.getByLabel("用户名").fill(username);
  await page.getByLabel("密码").fill(password);
  await page.getByRole("button", { name: /登录/ }).click();
  await page.waitForURL(/\/chat/, { timeout: 20_000 });
}

test("记忆权限：超管可看别人，普通用户只能看自己", async ({ page, request }) => {
  test.setTimeout(180_000);

  const token = await apiToken(request);
  const adminHeaders = { Authorization: `Bearer ${token}` };
  const jsonHeaders = { ...adminHeaders, "Content-Type": "application/json" };

  const me = await (await request.get(`${API}/api/auth/me`, { headers: adminHeaders })).json();
  const adminId = (me.data?.id ?? me.data?.userId) as number;
  expect(adminId).toBeTruthy();

  // 1) 造一个临时普通用户（跨用户读写需要“另一个人”）
  const username = `e2e-mem-${Date.now()}`;
  const created = await request.post(`${API}/api/users`, {
    headers: jsonHeaders,
    data: { username, password: PASSWORD, displayName: "E2E 记忆用户", role: "USER" },
  });
  expect(created.ok(), await created.text()).toBeTruthy();
  const tempUserId = (await created.json()).data.id as number;

  let userToken = "";
  try {
    // 2) 超管往**别人**的记忆里写一份台账（这正是“超管可检索其他用户”的写侧能力）
    const seed = await request.put(`${API}/api/memory/${TEST_FILE}?userId=${tempUserId}`, {
      headers: jsonHeaders,
      data: { content: `# 2020-01-02\n- 临时用户的记忆 ${MARK}\n` },
    });
    expect(seed.ok(), await seed.text()).toBeTruthy();

    // 3) 权限：普通用户读别人的记忆必须被拒
    userToken = (
      await (
        await request.post(`${API}/api/auth/login`, { data: { username, password: PASSWORD } })
      ).json()
    ).data.token as string;
    const userHeaders = { Authorization: `Bearer ${userToken}` };

    const forbidden = await request.get(`${API}/api/memory?userId=${adminId}`, {
      headers: userHeaders,
    });
    expect(forbidden.status(), "普通用户不该能读超管的记忆").toBe(403);

    // 自己的（不传 userId）= 200，而且确实是自己那份
    const own = await request.get(`${API}/api/memory`, { headers: userHeaders });
    expect(own.ok()).toBeTruthy();
    const ownNames = ((await own.json()).data as { name: string }[]).map((d) => d.name);
    expect(ownNames).toContain(TEST_FILE);

    // 超管传别人的 id = 200
    const asAdmin = await request.get(`${API}/api/memory?userId=${tempUserId}`, {
      headers: adminHeaders,
    });
    expect(asAdmin.ok()).toBeTruthy();
    console.log("[记忆权限] 接口侧：普通用户越权 403 ✓ / 超管跨用户 200 ✓");

    // 4) 页面：超管能看到「用户」下拉框，切过去看到别人的记忆
    await login(page, "admin", "Admin@123");
    await page.getByRole("link", { name: "记忆" }).click();
    await expect(page.getByTestId("memory-page")).toBeVisible({ timeout: 20_000 });

    const select = page.getByTestId("memory-user-select");
    await expect(select).toBeVisible();
    // 默认是自己：不该出现“正在查看别人”的提示
    await expect(page.getByTestId("memory-viewing-other")).toHaveCount(0);

    await select.selectOption(String(tempUserId));
    await expect(page.getByTestId("memory-viewing-other")).toBeVisible({ timeout: 20_000 });
    const item = page.getByTestId("memory-file-item").filter({ hasText: "2020-01-02" });
    await expect(item).toBeVisible({ timeout: 20_000 });
    await item.click();
    await expect(page.getByTestId("memory-current-name")).toHaveText(TEST_FILE);
    await expect(page.getByTestId("memory-preview")).toContainText(MARK);
    await page.screenshot({
      path: "../docs/acceptance/task-11.5-memory-admin-cross-user.png",
      fullPage: true,
    });
    console.log("[记忆权限] 页面侧：下拉框切到别人 → 看到对方的记忆 ✓");

    // 切回自己：提示消失，清单回到自己的
    await select.selectOption("");
    await expect(page.getByTestId("memory-viewing-other")).toHaveCount(0);
    await expect(item).toHaveCount(0, { timeout: 20_000 });
    console.log("[记忆权限] 切回自己 ✓");
  } finally {
    // 5) 收尾：删掉临时用户的台账（台账是能删干净的，不留垃圾文件）与用户本身
    await request.delete(`${API}/api/memory/${TEST_FILE}?userId=${tempUserId}`, {
      headers: adminHeaders,
    });
    await request.delete(`${API}/api/users/${tempUserId}`, { headers: adminHeaders });
  }
});
