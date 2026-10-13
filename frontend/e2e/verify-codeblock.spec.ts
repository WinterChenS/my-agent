import { expect, test } from "@playwright/test";

/**
 * 用户反馈：代码块几乎看不清（浅灰底 + 浅色字）。
 * 根因：只覆盖了 prose-pre 的背景色，没覆盖文字色 —— typography 默认给 pre 配的是深色底的浅色字。
 *
 * 这里不看"感觉"：直接读 **计算样式**，断言文字色与背景色的亮度差足够大。
 */
/** 把任意 CSS 颜色（oklch/hex/rgb…）在浏览器里转成 sRGB 并算 WCAG 对比度。 */
async function contrastRatio(
  page: import("@playwright/test").Page,
  fg: string,
  bg: string,
): Promise<number> {
  return page.evaluate(
    ([fgColor, bgColor]) => {
      const toRgb = (color: string) => {
        const c = document.createElement("canvas");
        c.width = 1;
        c.height = 1;
        const ctx = c.getContext("2d")!;
        ctx.fillStyle = "#000";
        ctx.fillStyle = color;
        ctx.fillRect(0, 0, 1, 1);
        const [r, g, b] = ctx.getImageData(0, 0, 1, 1).data;
        return [r, g, b];
      };
      const lum = ([r, g, b]: number[]) => {
        const f = (v: number) => {
          const s = v / 255;
          return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        };
        return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
      };
      const l1 = lum(toRgb(fgColor));
      const l2 = lum(toRgb(bgColor));
      const [hi, lo] = l1 > l2 ? [l1, l2] : [l2, l1];
      return (hi + 0.05) / (lo + 0.05);
    },
    [fg, bg] as const,
  );
}

test("代码块文字与背景对比度足够", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("用户名").fill("admin");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: /登录/ }).click();
  await page.waitForURL(/\/chat/);
  await page.waitForSelector("[data-testid='session-item']", { timeout: 30000 });

  // 依次开会话，找带代码块的那条
  const items = page.locator("[data-testid='session-item']");
  const count = Math.min(await items.count(), 10);
  let found = false;
  for (let i = 0; i < count && !found; i += 1) {
    await items.nth(i).click();
    await page.waitForTimeout(700);
    found = (await page.locator("[data-testid='assistant-text'] pre").count()) > 0;
  }
  expect(found, "没找到含代码块的会话").toBe(true);

  const pre = page.locator("[data-testid='assistant-text'] pre").first();
  await pre.scrollIntoViewIfNeeded();
  const styles = await pre.evaluate((el) => {
    const cs = getComputedStyle(el);
    const code = el.querySelector("code");
    const ccs = code ? getComputedStyle(code) : null;
    return {
      preColor: cs.color,
      preBg: cs.backgroundColor,
      codeColor: ccs?.color ?? null,
      codeBg: ccs?.backgroundColor ?? null,
    };
  });
  console.log("pre  文字/背景 =", styles.preColor, "/", styles.preBg);
  console.log("code 文字/背景 =", styles.codeColor, "/", styles.codeBg);

  // 代码块里的 code 背景是透明的，所以对比度按「文字色 vs pre 背景色」算
  const preContrast = await contrastRatio(page, styles.preColor, styles.preBg);
  console.log("pre 对比度 =", preContrast.toFixed(2), ": 1");
  expect(preContrast, "代码块文字与背景对比度过低").toBeGreaterThan(4.5);

  await pre.screenshot({ path: "../docs/acceptance/task-24-codeblock-fixed.png" });
});
