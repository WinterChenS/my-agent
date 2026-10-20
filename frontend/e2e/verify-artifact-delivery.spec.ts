import { expect, test } from "@playwright/test";
import { API, createStubAgent, deleteStubAgent, loginAndSelectAgent } from "./helpers/api";

/**
 * 官方「Agent 主动交付产物」链路（AgentScope 2.0.3 自带，无需自研）：
 *   桩：write_file 写文件 → deliver_artifact 交付
 * 期望：产物落进 artifact 表（role=ARTIFACT）、**带上本轮 runId**，
 *       且本轮 assistant 消息的 agui_meta.runId 与之一致 —— 这是"挂到对应气泡下方"的依据。
 */
test.describe("产物交付（官方 deliver_artifact）", () => {
  test("交付落库并带上本轮 runId，与 assistant 消息的 meta 一致", async ({ page, request }) => {
    const stamp = Date.now();
    const { agentName, agentDbId, headers } = await createStubAgent(request, stamp, {
      modelId: `stub-script-${stamp}`,
    });
    try {
      const input = await loginAndSelectAgent(page, agentName);
      await input.fill("做个季度报告");
      await input.press("Enter");
      // 等这一轮跑完（助手气泡出现）
      await expect(page.locator('[data-testid="assistant-text"]')).toHaveCount(1, { timeout: 90_000 });

      const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
      const session = (sessions.data as { id: number; agentId: number | null }[]).find(
        (s) => s.agentId === agentDbId,
      )!;

      // ① 产物落库
      const arts = await (
        await request.get(`${API}/api/artifacts?sessionId=${session.id}`, { headers })
      ).json();
      const artifacts = (arts.data as Record<string, unknown>[]).filter((a) => a.role === "ARTIFACT");
      console.log(
        `[产物] 本次会话 ARTIFACT 条数=${artifacts.length} 明细=` +
          JSON.stringify(artifacts.map((a) => ({ f: a.fileName, run: a.runId, pv: a.previewable }))),
      );
      expect(artifacts.length).toBeGreaterThanOrEqual(1);
      const artifact = artifacts[0];
      expect(artifact.runId, "产物必须带上本轮 runId").toBeTruthy();
      expect(artifact.previewable, "markdown 产物应可预览").toBe(true);

      // ② assistant 消息的 agui_meta.runId 与产物一致（前端据此挂到气泡下方）
      // 注意：消息是在 run **收尾**时才落库的（产物则是在 run 中途交付时就落库），所以要等它出现
      let lastAssistant: { role: string; aguiMeta: Record<string, unknown> | null } | undefined;
      for (let i = 0; i < 40 && !lastAssistant; i += 1) {
        const msgs = await (
          await request.get(`${API}/api/sessions/${session.id}/messages`, { headers })
        ).json();
        const rows = (msgs.data ?? []) as { role: string; aguiMeta: Record<string, unknown> | null }[];
        lastAssistant = [...rows].reverse().find((m) => m.role === "ASSISTANT");
        if (lastAssistant?.aguiMeta?.runId) break;
        await page.waitForTimeout(500);
      }
      console.log(
        `[产物] assistant.aguiMeta.runId=${String(lastAssistant.aguiMeta?.runId)} 产物.runId=${String(artifact.runId)}`,
      );
      expect(lastAssistant.aguiMeta?.runId).toBe(artifact.runId);

      // ③ 界面上：产物框出现在**对应气泡下方**，且带预览
      const box = page.getByTestId("artifact-box");
      await expect(box).toBeVisible({ timeout: 30_000 });
      await expect(box).toContainText(String(artifact.fileName));
      const bubble = (await page.locator('[data-testid="assistant-text"]').last().boundingBox())!;
      const boxBox = (await box.boundingBox())!;
      console.log(
        `[产物] 气泡底=${Math.round(bubble.y + bubble.height)} 产物框顶=${Math.round(boxBox.y)}`,
      );
      expect(boxBox.y, "产物框应在气泡下方").toBeGreaterThanOrEqual(bubble.y + bubble.height - 2);

      // ④ 预览：文本可预览 → 点它在**右侧预览面板**里打开完整内容（内联挤压，无遮罩）
      const inline = page.getByTestId("artifact-preview-inline").first();
      await expect(inline).toBeVisible();
      console.log(`[产物] 行内预览文本 = ${JSON.stringify((await inline.innerText()).slice(0, 40))}`);
      await inline.click();
      const panel = page.getByTestId("preview-panel");
      await expect(panel).toBeVisible({ timeout: 15_000 });
      await expect(page.getByTestId("artifact-preview-full")).toContainText("季度报告");
      console.log("[产物] 点预览 → 右侧预览面板已打开且正文完整 ✓");
      await page.screenshot({ path: "../docs/acceptance/task-10.7-artifact-preview-panel.png" });
      await page.getByTestId("preview-panel-close").click();
      await expect(panel).toHaveCount(0);
      console.log("[产物] 关闭预览面板 ✓");
    } finally {
      const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
      for (const s of sessions.data as { id: number; agentId: number | null }[]) {
        if (s.agentId === agentDbId) await request.delete(`${API}/api/sessions/${s.id}`, { headers });
      }
      await deleteStubAgent(request, headers, agentDbId);
    }
  });
});
