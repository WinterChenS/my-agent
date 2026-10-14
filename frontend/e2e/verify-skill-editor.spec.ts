import { expect, test } from "@playwright/test";

/**
 * 技能页验收：
 *  ① 列表分页
 *  ② 编辑器是「单框所见即所得」（类似 Notion）——边写边渲染，不再是左右分栏
 *  ③ 输入 "/" 插入 MCP / 本地工具名（不残留 "/query"）
 *  ④ 保存后重开，内容仍是纯 Markdown（渲染正确）
 */
test("技能页：分页 + 所见即所得编辑 + / 插工具 + 保存往返", async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 });
  const stamp = Date.now();
  const skillName = `mdx-verify-${stamp}`;

  await page.goto("/login");
  await page.getByLabel("用户名").fill("admin");
  await page.getByLabel("密码").fill("Admin@123");
  await page.getByRole("button", { name: /登录/ }).click();
  await page.waitForURL(/\/chat/);

  // ① 分页
  await page.goto("/admin/skills");
  await expect(page.getByTestId("skills-pagination")).toBeVisible({ timeout: 20000 });
  await expect(page.getByTestId("skills-page-size")).toBeVisible();
  console.log("[① 分页]", (await page.getByTestId("skills-pagination").innerText()).replace(/\s+/g, " "));

  // 新建一个技能，用所见即所得编辑器写内容
  await page.getByRole("button", { name: /新建/ }).click();
  await page.getByTestId("skill-wysiwyg").waitFor({ timeout: 20000 });
  const editable = page.locator('[contenteditable="true"]').first();

  // ② 单框：没有独立的预览面板
  console.log("[② 预览面板数（应为 0）]", await page.getByTestId("skill-content-preview").count());

  // 输入名字 + 正文，观察即时渲染
  await page.locator('input[placeholder="reply-with-ok"]').fill(skillName);
  await page.getByTestId("skill-description").fill("e2e 验证：所见即所得编辑与工具插入");
  await editable.click();
  await page.keyboard.type("# 订单助手");
  await page.waitForTimeout(400);
  console.log("[② 输入 '# 订单助手' → DOM 里 h1 数]", await page.locator('[contenteditable="true"] h1').count());
  console.log("[② DOM 里是否残留字面 '#' ]", (await editable.innerText()).includes("#"));
  expect(await page.locator('[contenteditable="true"] h1').count()).toBe(1);
  expect((await editable.innerText()).includes("#")).toBe(false);

  await page.keyboard.press("Enter");
  await page.keyboard.type("- 先查订单再回话");
  await page.waitForTimeout(300);
  expect(await page.locator('[contenteditable="true"] li').count()).toBeGreaterThan(0);

  // ③-a 只打「/」（不带关键字）：本地工具必须出现、排在最前、且能被选中
  //     —— 修复前这里会红：几百个 MCP 工具占满候选，本地工具被截断，根本看不到。
  await page.keyboard.press("Enter");
  await page.keyboard.type("/");
  await page.waitForTimeout(700);
  await expect(page.getByTestId("skill-tool-menu")).toBeVisible({ timeout: 10000 });
  const groups = await page.getByTestId("skill-tool-group").evaluateAll((els) =>
    els.map((el) => (el.textContent ?? "").replace(/\s+/g, " ").trim()),
  );
  const shown = await page.getByTestId("skill-tool-option").evaluateAll((els) =>
    els.map((el) => (el.querySelector("code")?.textContent ?? "").trim()),
  );
  console.log("[③-a 分组]", groups.slice(0, 3).join(" | "));
  console.log("[③-a 候选前 8]", shown.slice(0, 8).join(", "));
  expect(groups[0]).toContain("本地");                 // 第一组必须是本地工具
  expect(shown).toContain("read_file");                // 本地工具真的能看到
  const firstMcp = shown.findIndex((n) => n.includes("__"));
  expect(firstMcp).toBeGreaterThan(shown.indexOf("write_file")); // 本地全部排在 MCP 之前
  console.log("[③-a] 本地工具占据前", firstMcp, "项，第一个 MCP 候选在下标", firstMcp);

  // 键盘选中并插入（高亮可能被鼠标悬停改过，所以断言「插进的是本地工具之一」）
  await page.keyboard.press("Enter");
  await page.waitForTimeout(500);
  const afterFirstPick = await editable.innerText();
  const insertedLocal = shown.slice(0, 24).find((n) => afterFirstPick.includes(n));
  console.log("[③-a] 回车插入的本地工具:", insertedLocal, "| 菜单已关闭:", (await page.getByTestId("skill-tool-menu").count()) === 0);
  expect(insertedLocal, "回车应插入一个本地工具").toBeTruthy();

  // ③-b 输入 /read 过滤后插入
  await page.keyboard.press("End");
  await page.keyboard.type(" \n查订单请调用 ");
  await page.keyboard.type("/read");
  await page.waitForTimeout(700);
  await expect(page.getByTestId("skill-tool-menu")).toBeVisible({ timeout: 10000 });
  const options = await page.getByTestId("skill-tool-option").evaluateAll((els) =>
    els.map((el) => (el.querySelector("code")?.textContent ?? "").trim()),
  );
  console.log("[③-b /read 候选]", options.slice(0, 3).join(", "));
  expect(options).toContain("read_file");
  // 显式点中 read_file（走鼠标这条路径；键盘选中在 ③-a 已验过）
  await page
    .getByTestId("skill-tool-option")
    .filter({ has: page.locator("code", { hasText: /^read_file$/ }) })
    .first()
    .click();
  await page.waitForTimeout(500);
  const afterInsert = await editable.innerText();
  console.log("[③-b] 含 read_file:", afterInsert.includes("read_file"), "| 残留 '/read':", afterInsert.includes("/read"));
  expect(afterInsert).toContain("read_file");
  expect(afterInsert.includes("/read")).toBe(false);

  await page.screenshot({ path: "../docs/acceptance/task-27-skill-wysiwyg.png" });

  // 保存
  await page.getByRole("button", { name: "保存" }).click();
  await expect(page.getByText(/已创建 Skill|已保存/).first()).toBeVisible({ timeout: 15000 });

  // ④ 重新打开 → 内容仍是 Markdown 且渲染正确
  const row = page.locator("tr", { hasText: skillName });
  // 创建后列表会自动跳到新条目所在页（分页 + id 升序的必然结果），所以这里直接能找到
  await expect(row).toBeVisible({ timeout: 20000 });
  // 等列表刷新稳定再点编辑：刚创建完马上点，点击可能落在重新渲染的间隙里（实测会偶发点空）
  await page.waitForTimeout(1200);
  await expect(row).toBeVisible();
  await row.getByTitle("编辑").click();
  await page.getByTestId("skill-wysiwyg").waitFor({ timeout: 20000 });
  await page.waitForTimeout(1200);
  const reopened = page.locator('[contenteditable="true"]').first();
  const text = await reopened.innerText();
  console.log("[④ 重开后正文]", text.replace(/\s+/g, " ").slice(0, 100));
  expect(text).toContain("订单助手");
  expect(await page.locator('[contenteditable="true"] h1').count()).toBe(1);
  expect(text).toContain("read_file");
  expect(text.includes("#")).toBe(false);
  // 行内代码不应出现多余的反引号（早期 insertMarkdown 会插出 ``read_file``）
  expect(text.includes("``")).toBe(false);

  // 清理：删掉这个测试技能
  await page.getByRole("button", { name: "取消" }).click();
  await page.locator("tr", { hasText: skillName }).getByTitle("删除").click();
  page.on("dialog", (d) => void d.accept());
  await page.waitForTimeout(1000);
});
