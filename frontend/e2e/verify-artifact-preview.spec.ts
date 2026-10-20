import { deflateRawSync } from "node:zlib";
import * as XLSX from "xlsx";
import { expect, test, type Locator, type Page } from "@playwright/test";
import { API, createStubAgent, deleteStubAgent, loginAndSelectAgent } from "./helpers/api";

/**
 * 产物预览重构（Task 10.7）：**按类型渲染 + 统一右侧面板**。
 *
 * <p>覆盖点：
 * <ul>
 *   <li>html / pdf / markdown / 纯文本 / Excel / Word 各走各的渲染；</li>
 *   <li>多个产物 → 上方 tab 切换、逐个关闭、关完最后一个面板消失；</li>
 *   <li>面板是**内联挤压**（三栏并排，不是遮罩盖住聊天区）；</li>
 *   <li>本会话出现可预览产物时**自动打开**（不需要点任何按钮）。</li>
 * </ul>
 *
 * <p>样本自给自足（仓库里不放二进制 fixture）：pdf 手写字节；xlsx 用 SheetJS 现造；
 * docx 现拼 zip（用 deflate，顺带验证浏览器里 `DecompressionStream("deflate-raw")` 那条路）。
 * 附件是「发送时才上传」（D24），所以选完文件必须真发一条消息才会出现在右侧栏。
 */

/* ---------------------------------------------------------------- 样本生成 */

/**
 * 现拼一个最小 zip。
 *
 * @param deflate true = method 8（deflate，与真实 .docx 一致）；false = stored
 */
function makeZip(
  entries: { name: string; data: Uint8Array; deflate: boolean }[],
): Buffer {
  const encoder = new TextEncoder();
  const locals: Buffer[] = [];
  const centrals: Buffer[] = [];
  let offset = 0;

  for (const entry of entries) {
    const nameBytes = encoder.encode(entry.name);
    const payload = entry.deflate ? new Uint8Array(deflateRawSync(entry.data)) : entry.data;
    const method = entry.deflate ? 8 : 0;

    const local = Buffer.alloc(30 + nameBytes.length + payload.length);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(method, 8);
    local.writeUInt32LE(payload.length, 18);
    local.writeUInt32LE(entry.data.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    local.set(nameBytes, 30);
    local.set(payload, 30 + nameBytes.length);
    locals.push(local);

    const central = Buffer.alloc(46 + nameBytes.length);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(method, 10);
    central.writeUInt32LE(payload.length, 20);
    central.writeUInt32LE(entry.data.length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt32LE(offset, 42);
    central.set(nameBytes, 46);
    centrals.push(central);

    offset += local.length;
  }

  const centralSize = centrals.reduce((sum, item) => sum + item.length, 0);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(centralSize, 12);
  eocd.writeUInt32LE(offset, 16);

  return Buffer.concat([...locals, ...centrals, eocd]);
}

/** 一个「Word 能认」的最小 .docx：只求能抽出文字（deflate 压缩，跟真实文件一致）。 */
function makeDocx(paragraphs: string[]): Buffer {
  const xml = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
${paragraphs.map((line) => `    <w:p><w:r><w:t>${line}</w:t></w:r></w:p>`).join("\n")}
  </w:body>
</w:document>`;
  const encoder = new TextEncoder();
  return makeZip([
    { name: "[Content_Types].xml", data: encoder.encode("<Types/>"), deflate: true },
    { name: "_rels/.rels", data: encoder.encode("<Relationships/>"), deflate: true },
    { name: "word/document.xml", data: encoder.encode(xml), deflate: true },
  ]);
}

/** 手写一个**结构完整**（含 xref）的最小 PDF；Chrome 内置阅读器能打开它。 */
function makePdf(label: string): Buffer {
  const content = `BT /Helvetica 18 Tf 20 120 Td (${label}) Tj ET`;
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
    `<< /Length ${content.length} >>\nstream\n${content}\nendstream`,
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
  ];
  let pdf = "%PDF-1.4\n";
  const offsets: number[] = [];
  objects.forEach((body, index) => {
    offsets.push(pdf.length);
    pdf += `${index + 1} 0 obj\n${body}\nendobj\n`;
  });
  const xrefStart = pdf.length;
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const item of offsets) {
    pdf += `${String(item).padStart(10, "0")} 00000 n \n`;
  }
  pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xrefStart}\n%%EOF\n`;
  // latin1：保证「字符数 == 字节数」，否则 xref 里的偏移全部错位
  return Buffer.from(pdf, "latin1");
}

/** 两页工作簿（第二页用来验证多 sheet tab）。 */
function makeXlsx(): Buffer {
  const book = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(
    book,
    XLSX.utils.aoa_to_sheet([
      ["名称", "数量"],
      ["苹果", "3"],
    ]),
    "清单",
  );
  XLSX.utils.book_append_sheet(book, XLSX.utils.aoa_to_sheet([["第二页内容"]]), "第二页");
  return XLSX.write(book, { type: "buffer", bookType: "xlsx" }) as Buffer;
}

const HTML_BODY = `<!doctype html>
<meta charset="utf-8">
<h1 id="t">html-静态</h1>
<script>document.getElementById("t").textContent = "html-script-ok";</script>`;

const MD_BODY = "## 标题二\n\n| 列 | 值 |\n|---|---|\n| a | 1 |\n";
const TXT_BODY = "纯文本内容\n第二行\n";
const DOCX_TEXT = "Word 正文第一段";

/* ---------------------------------------------------------------- 用例 */

/** 打开「来源文件与产物」清单弹窗（入口：页头按钮）。 */
async function openBrowser(page: Page): Promise<Locator> {
  await page.getByTestId("open-artifact-browser").click();
  const browser = page.getByTestId("artifact-browser");
  await expect(browser).toBeVisible();
  return browser;
}

/** 在清单弹窗里按文件名点「预览」，返回右侧预览面板的 locator。 */
async function openPreview(page: Page, fileName: string): Promise<Locator> {
  const browser = await openBrowser(page);
  const row = browser.locator('[data-testid="panel-sources-item"]', { hasText: fileName });
  await expect(row).toBeVisible({ timeout: 30_000 });
  await row.getByTestId("artifact-preview-button").click();
  // 选完预览 → 弹窗自动关掉（右侧面板已经是完整视图）
  await expect(browser).toHaveCount(0);
  const panel = page.getByTestId("preview-panel");
  await expect(panel).toBeVisible({ timeout: 20_000 });
  // 面板里当前选中的就是刚点的那个产物
  await expect(panel.getByTestId("preview-tab").filter({ hasText: fileName }).first()).toBeVisible();
  return panel;
}

async function cleanup(
  request: Parameters<typeof deleteStubAgent>[0],
  headers: Record<string, string>,
  agentDbId: number,
) {
  const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
  for (const s of sessions.data as { id: number; agentId: number | null }[]) {
    if (s.agentId === agentDbId) await request.delete(`${API}/api/sessions/${s.id}`, { headers });
  }
  await deleteStubAgent(request, headers, agentDbId);
}

test("按类型渲染：html/pdf/markdown/纯文本/Excel/Word 各走各的，多产物用 tab", async ({
  page,
  request,
}) => {
  const stamp = Date.now();
  const { agentName, agentDbId, headers } = await createStubAgent(request, stamp);
  try {
    const input = await loginAndSelectAgent(page, agentName);
    const names = {
      html: `e2e-view-${stamp}.html`,
      md: `e2e-view-${stamp}.md`,
      txt: `e2e-view-${stamp}.txt`,
      pdf: `e2e-view-${stamp}.pdf`,
      xlsx: `e2e-view-${stamp}.xlsx`,
      docx: `e2e-view-${stamp}.docx`,
    };

    // 一次选齐 6 个文件（附件在**发送时**才上传，D24）
    await page.setInputFiles('[data-testid="file-input"]', [
      { name: names.html, mimeType: "text/html", buffer: Buffer.from(HTML_BODY, "utf-8") },
      { name: names.md, mimeType: "text/markdown", buffer: Buffer.from(MD_BODY, "utf-8") },
      { name: names.txt, mimeType: "text/plain", buffer: Buffer.from(TXT_BODY, "utf-8") },
      { name: names.pdf, mimeType: "application/pdf", buffer: makePdf("pdf-preview-ok") },
      {
        name: names.xlsx,
        mimeType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        buffer: makeXlsx(),
      },
      {
        name: names.docx,
        mimeType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        buffer: makeDocx([DOCX_TEXT, "第二段"]),
      },
    ]);
    await expect(page.locator('[data-testid="attachment-chip"]')).toHaveCount(6);
    await input.fill("把这几个文件都预览一下");
    await input.press("Enter");

    // 清单弹窗里能看到 6 条来源文件；**产物栏不出现**（Agent 没写文件）
    const browser = await openBrowser(page);
    await expect(browser.locator('[data-testid="panel-sources-item"]')).toHaveCount(6, {
      timeout: 60_000,
    });
    await expect(browser.getByTestId("panel-artifacts")).toHaveCount(0);
    await page.keyboard.press("Escape");
    await expect(page.getByTestId("artifact-browser")).toHaveCount(0);

    // ① HTML：沙箱 iframe，**脚本能跑**（allow-scripts），内容来自 srcDoc
    let panel = await openPreview(page, names.html);
    const htmlFrame = page.frameLocator('[data-testid="preview-html"]');
    await expect(htmlFrame.locator("h1")).toHaveText("html-script-ok", { timeout: 20_000 });
    console.log("[预览] html → 沙箱 iframe 且脚本生效 ✓");

    // ①b iframe 要**占满面板可用高度**（以前写死 70vh：下方留白、内容还被截断）
    const panelBox0 = (await panel.boundingBox())!;
    const htmlBox = (await page.getByTestId("preview-html").boundingBox())!;
    const bottomGap = panelBox0.y + panelBox0.height - (htmlBox.y + htmlBox.height);
    console.log(
      `[预览] html 高度=${Math.round(htmlBox.height)} 面板高=${Math.round(panelBox0.height)} 底部余量=${Math.round(bottomGap)}`,
    );
    expect(bottomGap, "iframe 应填满面板（只留内边距）").toBeLessThan(24);
    expect(htmlBox.height).toBeGreaterThan(panelBox0.height * 0.8);
    await page.screenshot({ path: "../docs/acceptance/task-10.7-preview-height.png" });

    // ② PDF：交给浏览器内置阅读器
    panel = await openPreview(page, names.pdf);
    await expect(panel.getByTestId("preview-pdf")).toBeVisible({ timeout: 20_000 });
    console.log("[预览] pdf → iframe 打开 ✓");

    // ③ Markdown：渲染成标题/表格，而不是一段纯文本
    panel = await openPreview(page, names.md);
    const mdFull = panel.getByTestId("artifact-preview-full");
    await expect(mdFull.locator("h2")).toHaveText("标题二");
    await expect(mdFull.locator("table")).toBeVisible();
    console.log("[预览] markdown → 真渲染（h2 + table）✓");

    // ④ 纯文本：走 <pre>，保留原样
    panel = await openPreview(page, names.txt);
    await expect(panel.getByTestId("artifact-preview-full")).toContainText("纯文本内容");

    // ⑤ Excel：两页 → 两个 sheet tab；单元格文本进表格
    panel = await openPreview(page, names.xlsx);
    const sheetTable = panel.getByTestId("preview-sheet-table");
    await expect(sheetTable).toContainText("苹果", { timeout: 30_000 });
    const sheetTabs = panel.getByTestId("preview-sheet-tab");
    await expect(sheetTabs).toHaveCount(2);
    await sheetTabs.nth(1).click();
    await expect(sheetTable).toContainText("第二页内容");
    console.log("[预览] xlsx → 表格 + 多 sheet 切换 ✓");

    // ⑥ Word：零依赖解 zip 抽文字（deflate 条目，走 DecompressionStream）
    panel = await openPreview(page, names.docx);
    await expect(panel.getByTestId("artifact-preview-full")).toContainText(DOCX_TEXT, {
      timeout: 30_000,
    });
    console.log("[预览] docx → 抽到文字 ✓");

    // ⑦ 多产物：6 个 tab；点第一个能把内容切回去
    await expect(page.getByTestId("preview-tab")).toHaveCount(6);
    await page.getByTestId("preview-tab").filter({ hasText: names.html }).first().click();
    await expect(page.frameLocator('[data-testid="preview-html"]').locator("h1")).toHaveText(
      "html-script-ok",
      { timeout: 20_000 },
    );
    console.log("[预览] 多产物 → tab 切换 ✓");

    // ⑧ 两栏并排：面板在聊天区**右侧**（挤压而不是遮罩；来源/产物已收进弹窗，不再常驻一列）
    const chatBox = (await page.getByTestId("chat-column").boundingBox())!;
    const panelBox = (await page.getByTestId("preview-panel").boundingBox())!;
    expect(panelBox.x).toBeGreaterThanOrEqual(chatBox.x + chatBox.width - 2);
    console.log(
      `[预览] 两栏布局：聊天区右边缘=${Math.round(chatBox.x + chatBox.width)} 面板左边缘=${Math.round(panelBox.x)} ✓`,
    );

    // ⑨ 面板**左上角**的入口：弹窗紧贴**这个按钮的下方**弹出，且是竖长条（高 > 宽）
    const panelTriggerBox = (await page.getByTestId("open-artifact-browser-panel").boundingBox())!;
    await page.getByTestId("open-artifact-browser-panel").click();
    const popover = page.getByTestId("artifact-browser");
    await expect(popover).toBeVisible();
    const popoverBox = (await popover.boundingBox())!;
    console.log(
      `[预览] 清单弹窗：按钮底=${Math.round(panelTriggerBox.y + panelTriggerBox.height)} 弹窗顶=${Math.round(popoverBox.y)} 宽=${Math.round(popoverBox.width)} 高=${Math.round(popoverBox.height)}`,
    );
    expect(popoverBox.y, "弹窗应在按钮下方").toBeGreaterThanOrEqual(
      panelTriggerBox.y + panelTriggerBox.height - 1,
    );
    expect(popoverBox.height, "弹窗应该比宽高（竖长条）").toBeGreaterThan(popoverBox.width);
    await page.screenshot({ path: "../docs/acceptance/task-10.7-artifact-browser-popover.png" });
    await page.keyboard.press("Escape");
    await expect(popover).toHaveCount(0);
    console.log("[预览] 面板左上角入口 → 弹窗在按钮下方、竖长条 ✓");

    // ⑩ 拖左边界调整预览区 / 聊天区的大小（用户要求）
    const beforeWidth = (await page.getByTestId("preview-panel").boundingBox())!.width;
    const handleBox = (await page.getByTestId("preview-resize-handle").boundingBox())!;
    const grabX = handleBox.x + handleBox.width / 2;
    const grabY = handleBox.y + handleBox.height / 2;
    await page.mouse.move(grabX, grabY);
    await page.mouse.down();
    await page.mouse.move(grabX - 160, grabY, { steps: 12 });
    await page.mouse.up();
    const widerWidth = (await page.getByTestId("preview-panel").boundingBox())!.width;
    console.log(`[预览] 拖宽：${Math.round(beforeWidth)} → ${Math.round(widerWidth)}`);
    expect(widerWidth).toBeGreaterThan(beforeWidth + 100);

    // 双击分隔线 → 回到默认宽度
    await page.getByTestId("preview-resize-handle").dblclick();
    const resetWidth = (await page.getByTestId("preview-panel").boundingBox())!.width;
    expect(Math.round(resetWidth)).toBe(448);
    console.log("[预览] 拖拽调宽 + 双击回默认 ✓");

    await page.screenshot({ path: "../docs/acceptance/task-10.7-artifact-preview.png", fullPage: true });

    // ⑪ 逐个关 tab，关完最后一个 → 面板消失
    for (let guard = 0; guard < 10; guard += 1) {
      const closeButtons = page.getByTestId("preview-tab-close");
      if ((await closeButtons.count()) === 0) break;
      await closeButtons.first().click();
    }
    await expect(page.getByTestId("preview-panel")).toHaveCount(0);
    console.log("[预览] 关掉全部 tab → 面板消失 ✓");
  } finally {
    await cleanup(request, headers, agentDbId);
  }
});

test("自动打开：Agent 交付产物后不用点任何按钮，面板自己出现", async ({ page, request }) => {
  const stamp = Date.now();
  // 模型名含 script- → 桩走「write_file + deliver_artifact」两轮脚本，产出 报告-<数字>.md
  const { agentName, agentDbId, headers } = await createStubAgent(request, stamp, {
    modelId: `stub-script-${stamp}`,
  });
  try {
    const input = await loginAndSelectAgent(page, agentName);
    await input.fill("做个季度报告");
    await input.press("Enter");

    // 关键：全程没有点过预览按钮 —— 面板是被「出现可预览产物」自动打开的
    const panel = page.getByTestId("preview-panel");
    await expect(panel).toBeVisible({ timeout: 120_000 });
    await expect(panel.getByTestId("preview-tab")).toContainText("报告-");
    // .md 产物走 markdown 渲染，标题在 h1 里
    await expect(panel.getByTestId("artifact-preview-full").locator("h1")).toContainText(
      "季度报告",
      { timeout: 30_000 },
    );
    console.log("[预览] 产物出现 → 面板自动打开且渲染 markdown ✓");

    // 关掉后不再自己弹回来（同一批产物不重复抢焦点）
    await page.getByTestId("preview-panel-close").click();
    await expect(page.getByTestId("preview-panel")).toHaveCount(0);
    await page.waitForTimeout(1500);
    await expect(page.getByTestId("preview-panel")).toHaveCount(0);
    console.log("[预览] 手动关掉后不自动弹回 ✓");
  } finally {
    await cleanup(request, headers, agentDbId);
  }
});
