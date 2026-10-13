import { expect, test } from "@playwright/test";

/** 用户反馈：MCP 管理页无法滑动到下面（内容被裁掉）。 */
test("MCP 页能滚到底，底部内容可见", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 800 });
  await page.goto("/login");
  await page.getByLabel("用户名").fill("admin");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: /登录/ }).click();
  await page.waitForURL(/\/chat/);

  await page.goto("/admin/mcp");
  await expect(page.getByTestId("mcp-pagination")).toBeVisible({ timeout: 25000 });
  await expect(page.getByTestId("builtin-tools-card")).toBeVisible({ timeout: 25000 });

  // 找到真正在滚动的容器（scrollHeight > clientHeight）
  const scroller = await page.evaluate(() => {
    const els = Array.from(document.querySelectorAll("main, main *")) as HTMLElement[];
    const found = els.filter((el) => el.scrollHeight > el.clientHeight + 5);
    return found.map((el) => ({
      tag: el.tagName.toLowerCase(),
      cls: (el.className || "").toString().slice(0, 60),
      scrollHeight: el.scrollHeight,
      clientHeight: el.clientHeight,
    }));
  });
  console.log("[滚动容器]", JSON.stringify(scroller));
  expect(scroller.length, "没有任何可滚动容器 → 内容会被裁掉").toBeGreaterThan(0);

  // 滚到底，确认底部内容进入视口
  const cards = await page.getByTestId("builtin-tools-card").boundingBox();
  console.log("[滚动前] 内置工具卡片 y =", cards ? Math.round(cards.y) : "未取到", "（视口高 800）");

  await page.evaluate(() => {
    const main = document.querySelector("main") as HTMLElement;
    const target = Array.from(main.querySelectorAll<HTMLElement>("*")).find(
      (el) => el.scrollHeight > el.clientHeight + 5,
    );
    (target ?? main).scrollTo(0, 999999);
  });
  await page.waitForTimeout(600);

  const after = await page.getByTestId("builtin-tools-card").boundingBox();
  console.log("[滚到底后] 内置工具卡片 y =", after ? Math.round(after.y) : "未取到");
  expect(after!.y).toBeLessThan(800); // 卡片进入视口
  await expect(page.getByTestId("builtin-tool-row").last()).toBeVisible();

  await page.screenshot({ path: "../docs/acceptance/task-30-mcp-scroll-bottom.png" });
});
