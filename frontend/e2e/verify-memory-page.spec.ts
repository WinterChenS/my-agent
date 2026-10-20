import { expect, test } from "@playwright/test";
import { API, apiToken } from "./helpers/api";

/**
 * 记忆可视化页面（`/memory`）：能看到记忆清单、能编辑并保存、保存后**真的落到存储**。
 *
 * <p>为了不碰用户真实记忆，用例自己用假日期（`2020-01-0x`）造一份台账，测完删掉。
 * 这个日期框架不会注入到上下文（只注入最近若干天），所以顺手也说明“老台账不影响对话”。
 */
const TEST_FILE = "2020-01-02.md";
const MARK = `MEM-E2E-${Date.now()}`;

test("记忆页面：清单 → 编辑 → 保存 → 存储里真的变了", async ({ page, request }) => {
  const token = await apiToken(request);
  const headers = { Authorization: `Bearer ${token}`, "Content-Type": "application/json" };

  // 先通过接口造一份初始台账（模拟“已经记住的东西”）
  const seed = await request.put(`${API}/api/memory/${TEST_FILE}`, {
    headers,
    data: { content: `# 2020-01-02\n- 初始内容 ${MARK}\n` },
  });
  expect(seed.ok(), await seed.text()).toBeTruthy();

  try {
    // 登录（记忆页在应用内，需要登录态）
    await page.goto("/login");
    await page.getByLabel("密码").fill("Admin@123");
    await page.getByRole("button", { name: "登录" }).click();
    await page.waitForURL(/\/chat/, { timeout: 20_000 });

    // 侧边栏进入「记忆」
    await page.getByRole("link", { name: "记忆" }).click();
    await expect(page).toHaveURL(/\/memory/);
    const pageRoot = page.getByTestId("memory-page");
    await expect(pageRoot).toBeVisible({ timeout: 20_000 });

    // 清单里能看到 MEMORY.md 与我们造的台账
    await expect(page.getByTestId("memory-file-item").first()).toContainText("长期记忆");
    const item = page.getByTestId("memory-file-item").filter({ hasText: "2020-01-02" });
    await expect(item).toBeVisible({ timeout: 20_000 });
    await expect(page.getByTestId("memory-summary")).toContainText("份");
    console.log("[记忆页] 清单渲染 ✓");

    // 选中 → 预览里有内容
    await item.click();
    await expect(page.getByTestId("memory-current-name")).toHaveText(TEST_FILE);
    await expect(page.getByTestId("memory-preview")).toContainText(MARK);
    console.log("[记忆页] 预览渲染 markdown ✓");

    // 编辑 → 改动后出现「未保存」→ 保存
    await page.getByTestId("memory-mode-edit").click();
    const editor = page.getByTestId("memory-content");
    await expect(editor).toBeVisible();
    await editor.fill(`# 2020-01-02\n- 改过的内容 ${MARK}-EDITED\n`);
    await expect(page.getByTestId("memory-dirty")).toBeVisible();
    await page.getByTestId("memory-save").click();
    await expect(page.getByTestId("memory-dirty")).toHaveCount(0, { timeout: 15_000 });
    console.log("[记忆页] 编辑 → 保存 ✓");

    // 关键：存储里真的变了（不是只改了界面）
    const stored = await request.get(`${API}/api/memory/${TEST_FILE}`, { headers });
    const body = await stored.json();
    expect(body.data.content).toContain(`${MARK}-EDITED`);
    console.log("[记忆页] 保存后存储内容已更新 ✓");

    await page.screenshot({ path: "../docs/acceptance/task-11.4-memory-page.png", fullPage: true });

    // 删除：回到预览，删除这份台账，清单里消失
    await page.getByTestId("memory-mode-preview").click();
    page.once("dialog", (dialog) => void dialog.accept());
    await page.getByTestId("memory-delete").click();
    await expect(page.getByTestId("memory-file-item").filter({ hasText: "2020-01-02" })).toHaveCount(0, {
      timeout: 15_000,
    });
    console.log("[记忆页] 删除台账 ✓");
  } finally {
    await request.delete(`${API}/api/memory/${TEST_FILE}`, { headers });
  }
});
