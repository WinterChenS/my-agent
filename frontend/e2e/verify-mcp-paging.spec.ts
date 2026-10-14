import { expect, test, type APIRequestContext } from "@playwright/test";
import { API, apiToken } from "./helpers/api";

/**
 * 自给自足：本用例需要**若干个** MCP 服务器才能验证分页与筛选，
 * 所以自己造 12 个（名字带 `e2e-page-` 前缀、传输分两种），跑完删掉。
 * 之前直接断言"共 45 条 / 有 STDIO 服务器" —— 一旦清理测试数据就必然红（实测踩过）。
 */
const CREATED: number[] = [];

async function seedServers(request: APIRequestContext, stamp: number, count: number) {
  const token = await apiToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  for (let i = 0; i < count; i += 1) {
    const transport = i % 2 === 0 ? "STDIO" : "SSE";
    const res = await request.post(`${API}/api/mcp-servers`, {
      headers,
      data: {
        name: i < 3 ? `e2e-page-everything-${stamp}-${i}` : `e2e-page-${stamp}-${i}`,
        transport,
        command: transport === "STDIO" ? "cmd" : null,
        endpointUrl: transport === "STDIO" ? null : "http://localhost:9/sse",
        status: "ENABLED",
      },
    });
    const body = await res.json();
    if (body?.data?.id) CREATED.push(body.data.id as number);
  }
}

test.afterEach(async ({ request }) => {
  const token = await apiToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  while (CREATED.length > 0) {
    const id = CREATED.pop()!;
    await request.delete(`${API}/api/mcp-servers/${id}`, { headers });
  }
});

/** 用户需求：MCP 页加分页 + 筛选条件。 */
test("MCP 页：分页可翻、筛选生效、可与内置工具共存", async ({ page, request }) => {
  test.setTimeout(180_000);
  await seedServers(request, Date.now(), 12);
  await page.setViewportSize({ width: 1600, height: 900 });
  await page.goto("/login");
  await page.getByLabel("用户名").fill("admin");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: /登录/ }).click();
  await page.waitForURL(/\/chat/);

  await page.goto("/admin/mcp");
  await expect(page.getByTestId("mcp-pagination")).toBeVisible({ timeout: 25000 });
  const sum = () => page.getByTestId("mcp-pagination").innerText();
  console.log("[① 分页]", (await sum()).replace(/\s+/g, " "));

  const rows = () => page.locator("table").first().locator("tbody tr");
  const firstPageNames = await rows().evaluateAll((els) =>
    els.map((el) => el.querySelector("td")?.textContent?.trim() ?? ""),
  );
  console.log("[① 第1页前3]", firstPageNames.slice(0, 3).join(", "));

  // 翻到第 2 页：内容必须变（验证「翻页真的重新请求」）
  const nextBtn = page.getByTestId("mcp-next");
  if (!(await nextBtn.isDisabled())) {
    await nextBtn.click();
    await page.waitForTimeout(1200);
    const secondPageNames = await rows().evaluateAll((els) =>
      els.map((el) => el.querySelector("td")?.textContent?.trim() ?? ""),
    );
    console.log("[① 第2页前3]", secondPageNames.slice(0, 3).join(", "));
    console.log("[① 第2页]", (await sum()).replace(/\s+/g, " "));
    expect(secondPageNames.join()).not.toBe(firstPageNames.join());
    await page.getByTestId("mcp-prev").click();
    await page.waitForTimeout(1000);
  }

  // 每页条数
  await page.getByTestId("mcp-page-size").selectOption("20");
  await page.waitForTimeout(1200);
  console.log("[① 每页20]", (await sum()).replace(/\s+/g, " "));
  expect(await rows().count()).toBeLessThanOrEqual(20);

  // ② 关键词筛选
  await page.getByTestId("mcp-page-size").selectOption("50");
  await page.waitForTimeout(1000);
  await page.getByTestId("mcp-filter-keyword").fill("everything");
  await page.getByTestId("mcp-filter-apply").click();
  await page.waitForTimeout(1200);
  const kwNames = await rows().evaluateAll((els) =>
    els.map((el) => el.querySelector("td")?.textContent?.trim() ?? ""),
  );
  console.log("[② 关键词 everything] 条数 =", kwNames.length, "|", (await sum()).replace(/\s+/g, " "));
  expect(kwNames.length).toBeGreaterThan(0);
  expect(kwNames.every((n) => n.toLowerCase().includes("everything"))).toBe(true);

  // ③ 传输筛选
  await page.getByTestId("mcp-filter-reset").click();
  await page.waitForTimeout(1000);
  await page.getByTestId("mcp-filter-transport").selectOption("STDIO");
  await page.getByTestId("mcp-filter-apply").click();
  await page.waitForTimeout(1200);
  const stdioRows = await rows().evaluateAll((els) =>
    els.map((el) => Array.from(el.querySelectorAll("td")).map((td) => td.textContent ?? "").join(" ")),
  );
  console.log("[③ 传输 STDIO] 条数 =", stdioRows.length, "|", (await sum()).replace(/\s+/g, " "));
  expect(stdioRows.every((r) => r.includes("STDIO"))).toBe(true);

  // ④ 重置后回到全量，且内置工具卡片仍在
  await page.getByTestId("mcp-filter-reset").click();
  await page.waitForTimeout(1200);
  console.log("[④ 重置后]", (await sum()).replace(/\s+/g, " "));
  await expect(page.getByTestId("builtin-tools-card")).toBeVisible();

  await page.screenshot({ path: "../docs/acceptance/task-29-mcp-paging.png", fullPage: true });
});
