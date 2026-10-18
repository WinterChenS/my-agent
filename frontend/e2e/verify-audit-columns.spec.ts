import { expect, test } from "@playwright/test";

/**
 * 回归（用户实测）：审计「按模型分布」表的列曾**错位**——
 * 表头顺序是「输入 / 缓存命中」，单元格却按「缓存命中 / 输入」渲染，
 * 于是「缓存命中」列显示的其实是输入 tokens（几百万），而顶部汇总的缓存命中是 0，
 * 看起来就是"数据对不上"。
 *
 * 这里钉住两件事：①缓存命中列紧跟输入列；②「缓存命中 ≤ 输入」这个硬不变量。
 */
test.describe("审计看板：列与口径一致", () => {
  test("按模型分布表：表头与单元格对齐，缓存命中 ≤ 输入", async ({ page }) => {
    await page.goto("/login");
    await page.getByLabel("密码").fill("Admin@123");
    await page.getByRole("button", { name: "登录", exact: true }).click();
    await page.waitForURL(/\/chat/, { timeout: 20_000 });
    await page.goto("/admin/audit");
    await expect(page.getByText("按模型分布")).toBeVisible({ timeout: 15_000 });

    const table = page.locator("table").first();
    const headers = await table.locator("thead th").allInnerTexts();
    const firstRow = await table.locator("tbody tr").first().locator("td").allInnerTexts();
    console.log(`[审计列] 表头 = ${JSON.stringify(headers)}`);
    console.log(`[审计列] 首行 = ${JSON.stringify(firstRow)}`);

    const idxInput = headers.findIndex((h) => h.includes("输入"));
    const idxCache = headers.findIndex((h) => h.includes("缓存命中"));
    expect(idxInput, "应有『输入』列").toBeGreaterThanOrEqual(0);
    expect(idxCache, "『缓存命中』应紧跟『输入』").toBe(idxInput + 1);

    const num = (text: string | undefined) => Number((text ?? "0").replace(/[^\d]/g, "")) || 0;
    const input = num(firstRow[idxInput]);
    const cache = num(firstRow[idxCache]);
    console.log(`[审计列] 输入=${input} 缓存命中=${cache}`);
    expect(cache, "缓存命中 token 不可能超过输入 token").toBeLessThanOrEqual(input);

    // KPI 卡的缓存命中与列同口径（此处断言卡片存在且含命中率文案）
    await expect(page.getByTestId("kpi-cache")).toContainText("缓存命中");
  });
});
