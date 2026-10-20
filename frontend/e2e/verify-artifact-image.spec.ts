import { expect, test } from "@playwright/test";
import { API, createStubAgent, deleteStubAgent, loginAndSelectAgent } from "./helpers/api";

/**
 * 产物图片（Task 10.6）：右侧栏可预览 + 气泡内显示。
 *
 * <p>自给自足：图片由用例**内联**提供（1×1 真 PNG，base64 常量只此一处，不落地 fixture 文件）；
 * 让 Agent 产出图片则复用桩**已有**的环境变量开关（`STUB_TOOL_NAME` / `STUB_TOOL_ARGS`），
 * 不给桩加任何代码。注意附件是"发送时才上传"（D24），所以选完文件必须真发一条。
 */
// 1×1 真 PNG：已校验签名 / IHDR(1×1) / IEND / CRC（70 字节）
const TINY_PNG_BASE64 =
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==";

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
      const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
      for (const s of sessions.data as { id: number; agentId: number | null }[]) {
        if (s.agentId === agentDbId) await request.delete(`${API}/api/sessions/${s.id}`, { headers });
      }
      await deleteStubAgent(request, headers, agentDbId);
    }
  });
});
