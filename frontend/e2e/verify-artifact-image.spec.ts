import { spawn, type ChildProcess } from "node:child_process";
import path from "node:path";
import { expect, test } from "@playwright/test";
import { API, createStubAgent, deleteStubAgent, loginAndSelectAgent } from "./helpers/api";

/**
 * 产物图片（Task 10.6）：右侧栏可预览 + 气泡内显示 + 正文 Markdown 图片。
 *
 * <p>自给自足：图片由用例**内联**提供（1×1 真 PNG，base64 常量只此一处，不落地 fixture 文件）；
 * 让 Agent 产出图片则复用桩**已有**的环境变量开关（`STUB_TOOL_NAME` / `STUB_TOOL_ARGS` /
 * `STUB_REPLY`），不给桩加任何代码。注意附件是"发送时才上传"（D24），所以选完文件必须真发一条。
 */
// 1×1 真 PNG：已校验签名 / IHDR(1×1) / IEND / CRC（70 字节）
const TINY_PNG_BASE64 =
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==";

/*
 * 第二类图片：**Agent 写出来的**（走工作区扫描那条路，不是显式交付）。
 * 桩只能写文本，所以用一个真能渲染的文本型图片格式 SVG —— 它同时验证了
 * "svg 也要被当成图片"（后端两份 MIME 表合并前，这条会变成 octet-stream）。
 * 回复里再引用它（`![图](文件名)`）与一个不存在的名字，一并验证正文图片的两条分支。
 */
const IMAGE_STUB_PORT = 8096;
const IMAGE_FILE = `e2e-bubble-image-${Date.now()}.svg`;
const MISSING_FILE = `e2e-not-exist-${Date.now()}.png`;
const SVG =
  '<svg xmlns="http://www.w3.org/2000/svg" width="48" height="24"><rect width="48" height="24" fill="#3b82f6"/></svg>';
let imageStub: ChildProcess | null = null;

test.beforeAll(() => {
  const repoRoot = path.resolve(process.cwd(), "..");
  imageStub = spawn("python", [path.join(repoRoot, "tools", "llm-stub", "stub.py")], {
    cwd: repoRoot,
    env: {
      ...process.env,
      PORT: String(IMAGE_STUB_PORT),
      STUB_TOOL_NAME: "write_file",
      STUB_TOOL_ARGS: JSON.stringify({ path: IMAGE_FILE, content: SVG }),
      // 正文引用本会话已有的图片 + 一个不存在的名字（两条分支一起验证）
      // 空字符串才真的关闭（这个开关是字符串，"0" 在 Python 里是真值）
      STUB_REPLY_FROM_MODEL: "",
      STUB_REPLY: `图在这里：![图](${IMAGE_FILE}) 还有一个找不到的：![缺失](${MISSING_FILE})`,
    },
    stdio: "ignore",
  });
});

test.afterAll(() => {
  imageStub?.kill();
});

/** 只删本用例造的会话与 agent/provider/model。 */
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

test.describe("产物图片：右侧栏可预览", () => {
  test("上传的图片在「来源文件」里有预览按钮，点开是图片", async ({ page, request }) => {
    const stamp = Date.now();
    const { agentName, agentDbId, headers } = await createStubAgent(request, stamp);
    try {
      const input = await loginAndSelectAgent(page, agentName);
      const pngName = `e2e-shot-${stamp}.png`;
      await page.setInputFiles('[data-testid="file-input"]', {
        name: pngName,
        mimeType: "image/png",
        buffer: Buffer.from(TINY_PNG_BASE64, "base64"),
      });
      // 附件"发送时才上传"（D24）：只选文件不会进右侧栏
      await input.fill("带张图发一条");
      await input.press("Enter");

      const row = page.locator('[data-testid="panel-sources-item"]', { hasText: pngName });
      await expect(row).toBeVisible({ timeout: 30_000 });

      // 关键断言：图片也有预览按钮（修复前只有文本类才有）
      await row.hover();
      const preview = row.getByTestId("artifact-preview-button");
      await expect(preview).toBeVisible({ timeout: 10_000 });
      await preview.click();

      const img = page.locator('[data-testid="artifact-preview-image"]');
      await expect(img).toBeVisible({ timeout: 20_000 });
      expect(await img.evaluate((el: HTMLImageElement) => el.naturalWidth)).toBeGreaterThan(0);
      console.log("[图片] 右侧栏图片预览：naturalWidth > 0 ✓");
    } finally {
      await cleanup(request, headers, agentDbId);
    }
  });
});

test.describe("产物图片：气泡内显示", () => {
  test("Agent 写出的图片挂到本轮气泡内，产物带 runId；正文图片按文件名渲染", async ({
    page,
    request,
  }) => {
    const stamp = Date.now();
    // 指向专属端口的桩：write_file 写一个 .svg（走"扫描"路径），回复里引用它
    const { agentName, agentDbId, headers } = await createStubAgent(request, stamp, {
      baseUrl: `http://localhost:${IMAGE_STUB_PORT}/v1`,
    });
    try {
      const input = await loginAndSelectAgent(page, agentName);
      await input.fill("画个图");
      await input.press("Enter");
      // 先证明这一轮真的发出去了（否则后面的"没产物"是假阴性）
      await expect(page.getByTestId("user-text")).toHaveCount(1, { timeout: 30_000 });
      await expect(page.getByTestId("stop-button")).toHaveCount(0, { timeout: 120_000 });

      // ① 归属：扫描出来的产物必须带本轮 runId（修复前是 null，于是只在右侧栏出现）
      const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
      const session = (sessions.data as { id: number; agentId: number | null }[]).find(
        (s) => s.agentId === agentDbId,
      )!;
      const arts = await (
        await request.get(`${API}/api/artifacts?sessionId=${session.id}`, { headers })
      ).json();
      const image = (arts.data as Record<string, unknown>[]).find((a) => a.fileName === IMAGE_FILE)!;
      expect(image.mime, "svg 必须被当成图片（否则不会内联）").toBe("image/svg+xml");
      expect(image.runId, "扫描登记的产物必须带本轮 runId").toBeTruthy();

      // ② 气泡内真的渲染出图片（产物图）
      const artifactImg = page.locator('[data-testid="bubble-images"] img').first();
      await expect(artifactImg).toBeVisible({ timeout: 30_000 });
      expect(await artifactImg.evaluate((el: HTMLImageElement) => el.naturalWidth)).toBeGreaterThan(0);
      console.log("[图片] 气泡内产物图：naturalWidth > 0 ✓");

      // ③ 正文里按文件名引用的图片也能渲染（同一个产物，走 markdown img 覆盖）
      const markdownImg = page.getByTestId("markdown-image").first();
      await expect(markdownImg).toBeVisible({ timeout: 30_000 });
      expect(await markdownImg.evaluate((el: HTMLImageElement) => el.naturalWidth)).toBeGreaterThan(0);
      console.log("[图片] 正文 Markdown 图：naturalWidth > 0 ✓");

      // ④ 找不到的名字给提示，不留破图
      await expect(page.getByTestId("markdown-image-missing")).toBeVisible({ timeout: 10_000 });
      console.log("[图片] 正文图片文件名不存在 → 给提示 ✓");

      // ⑤ 点击产物图 → 复用右侧抽屉看大图
      await page.getByTestId("bubble-image").first().click();
      await expect(page.getByTestId("artifact-drawer")).toBeVisible({ timeout: 20_000 });
      console.log("[图片] 气泡内点击 → 抽屉打开 ✓");
    } finally {
      await cleanup(request, headers, agentDbId);
    }
  });
});
