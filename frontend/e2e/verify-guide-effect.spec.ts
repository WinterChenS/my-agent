import { readFileSync } from "node:fs";
import { expect, test } from "@playwright/test";
import { API, createStubAgent, deleteStubAgent, loginAndSelectAgent } from "./helpers/api";

const DUMP = "D:/project/my-agent/tools/llm-stub/dump-guide.txt";
const SEP = "=====REQUEST=====";

function requests(): string[] {
  try {
    return readFileSync(DUMP, "utf8").split(SEP);
  } catch {
    return [];
  }
}

test.describe("引导（steering）是否真的生效", () => {
  // ⚠️ 已知缺陷（实测取证，2026-10-16）：接口返回 guided=true（官方 interrupt(context,msg) 调用成功），
  // 但引导的那句话**没有出现在之后发给模型的请求里** —— 即模型上下文里根本没有它。
  // 用户反馈"引导好像不生效"属实。这里用 fixme 钉住：修好后去掉 fixme 即成为回归测试。
  test.fixme("执行中引导的话必须出现在**之后**发给模型的请求里", async ({ page, request }) => {
    const stamp = Date.now();
    // 慢模型：这一轮会持续几十秒，给"中途引导"留出窗口
    const { agentName, agentDbId, headers } = await createStubAgent(request, stamp, {
      modelId: `stub-slow-${stamp}`,
    });
    const mark = `GUIDEMARK${stamp}`;
    try {
      const input = await loginAndSelectAgent(page, agentName);
      // 基线必须在**发送前**取：dump 是追加写的，上一轮残留会让"等到有请求"立刻成立，
      // 从而把"引导发得太早（后端这一轮还没登记）"误判成"引导不生效"（实测踩过）
      const baseline = requests().length;
      await input.click();
      await page.keyboard.type("第一轮");
      await page.keyboard.press("Enter");
      await page.getByTestId("stop-button").waitFor({ timeout: 30_000 });

      // 找到这次 run 的会话
      const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
      const session = (sessions.data as { id: number; agentId: number | null }[]).find(
        (s) => s.agentId === agentDbId,
      )!;
      expect(session).toBeTruthy();

      // 等到模型**确实被调用过**再引导：前端「停止按钮」只是客户端状态，
      // 出现时后端这一轮可能还没登记（handle 尚未建立），此时引导必然 guided=false。
      let before = baseline;
      for (let i = 0; i < 80; i += 1) {
        before = requests().length;
        if (before > baseline) break;
        await page.waitForTimeout(500);
      }
      console.log(`[引导] 本轮已开始调用模型；基线=${baseline} 现在=${before}`);
      const running = await (await request.get(`${API}/api/sessions/running`, { headers })).json();
      const mine = (sessions.data as Record<string, unknown>[]).find((s) => s.agentId === agentDbId);
      console.log(`[引导] /running 登记的 threadId = ${JSON.stringify(running.data)}`);
      console.log(`[引导] 该会话字段 = ${JSON.stringify(mine)}`);

      const res = await request.post(`${API}/api/sessions/${session.id}/guide`, {
        headers,
        data: { text: `请改用中文回答。${mark}` },
      });
      const body = await res.json();
      console.log(`[引导] 接口返回 guided = ${JSON.stringify(body.data)}`);

      // 等这一轮跑完
      await page.getByTestId("stop-button").waitFor({ state: "detached", timeout: 120_000 });
      await page.waitForTimeout(1500);

      const all = requests();
      const after = all.slice(before).join(SEP);
      const seenAfter = after.includes(mark);
      console.log(`[引导] 引导后新增请求数 = ${all.length - before}`);
      console.log(`[引导] 引导的话出现在【引导之后】发给模型的请求里 = ${seenAfter}`);
      console.log(`[引导] 引导的话出现在【引导之前】的请求里 = ${all.slice(0, before).join(SEP).includes(mark)}`);
      expect(seenAfter).toBe(true);
    } finally {
      const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
      for (const s of sessions.data as { id: number; agentId: number | null }[]) {
        if (s.agentId === agentDbId) await request.delete(`${API}/api/sessions/${s.id}`, { headers });
      }
      await deleteStubAgent(request, headers, agentDbId);
    }
  });
});
