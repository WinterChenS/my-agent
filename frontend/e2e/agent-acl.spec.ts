import { expect, test } from "@playwright/test";
import { API, apiToken, createStubAgent, deleteStubAgent } from "./helpers/api";

/**
 * 按资源授权（用户点名的边界）：Agent 可以**只给指定用户用**。
 *
 * 语义：**没有白名单记录 = 对所有人开放**；配了记录就只有这些用户 + ADMIN 能用。
 * 这里钉住三件事：①未授权用户**看不到**它（列表按用户过滤）②未授权用户**用不了**它（/agui/run 403）
 * ③界面能把白名单配出来并回填。
 */
test.describe("按资源授权：Agent 限定可用用户", () => {
  test("受限 Agent 对未授权用户不可见、不可用；界面可配置", async ({ page, request }) => {
    const stamp = Date.now();
    const token = await apiToken(request);
    const admin = { Authorization: `Bearer ${token}` };
    // 造一个普通用户（授权给他）
    const uname = `acl-user-${stamp}`;
    const created = await (
      await request.post(`${API}/api/users`, {
        headers: admin,
        data: { username: uname, password: "Probe@12345", displayName: uname, role: "USER" },
      })
    ).json();
    const allowedUserId = created.data.id as number;
    const otherName = `acl-other-${stamp}`;
    const other = await (
      await request.post(`${API}/api/users`, {
        headers: admin,
        data: { username: otherName, password: "Probe@12345", displayName: otherName, role: "USER" },
      })
    ).json();
    const otherUserId = other.data.id as number;

    const { agentName, agentDbId, headers } = await createStubAgent(request, stamp);
    try {
      // ① 界面：默认"全部用户"
      await page.goto("/login");
      await page.getByLabel("密码").fill("Admin@123");
      await page.getByRole("button", { name: "登录", exact: true }).click();
      await page.waitForURL(/\/chat/, { timeout: 20_000 });
      await page.goto("/admin/agents");
      const row = page.getByRole("row", { name: new RegExp(agentName) });
      await expect(row).toContainText("全部用户", { timeout: 20_000 });
      console.log("[ACL] 未配白名单 → 列表显示「全部用户」✓");

      // ② 用界面把可用用户配成"只授权给 acl-user-*"
      await request.put(`${API}/api/agents/${agentDbId}/users`, {
        headers: admin,
        data: { userIds: [allowedUserId] },
      });
      await page.reload();
      await expect(page.getByRole("row", { name: new RegExp(agentName) })).toContainText("1 个用户", {
        timeout: 20_000,
      });
      await page.getByRole("row", { name: new RegExp(agentName) }).getByTitle("编辑").click();
      await expect(page.getByTestId("agent-allowed-users-picker")).toBeVisible();
      expect(await page.getByTestId(`agent-allowed-user-${allowedUserId}`).isChecked()).toBe(true);
      console.log("[ACL] 列表显示「1 个用户」且编辑弹窗回填勾选 ✓");
      await page.keyboard.press("Escape");

      // ③ 未授权用户：列表看不到
      const otherLogin = await (
        await request.post(`${API}/api/auth/login`, {
          data: { username: otherName, password: "Probe@12345" },
        })
      ).json();
      const otherToken = otherLogin.data.token as string;
      const listForOther = await (
        await request.get(`${API}/api/agents?size=300`, {
          headers: { Authorization: `Bearer ${otherToken}` },
        })
      ).json();
      const rowsForOther = listForOther.data.records as { name: string }[];
      expect(rowsForOther.some((r) => r.name === agentName), "未授权用户不该看到它").toBe(false);

      // ④ 未授权用户：发起 run 被拒（这条最关键——否则白名单只是装饰）
      const runResp = await request.post(`${API}/agui/run`, {
        headers: { Authorization: `Bearer ${otherToken}` },
        data: {
          threadId: `acl-thread-${stamp}`,
          runId: `acl-run-${stamp}`,
          messages: [{ id: "m1", role: "user", content: "hi" }],
          forwardedProps: { agentId: agentName },
        },
      });
      console.log(`[ACL] 未授权用户发起 run -> HTTP ${runResp.status()} ${JSON.stringify((await runResp.text()).slice(0, 80))}`);
      expect(runResp.status(), "未授权用户发起 run 必须被拒").toBe(403);

      // ⑤ 授权用户：看得到
      const allowedLogin = await (
        await request.post(`${API}/api/auth/login`, {
          data: { username: uname, password: "Probe@12345" },
        })
      ).json();
      const allowedToken = allowedLogin.data.token as string;
      const listForAllowed = await (
        await request.get(`${API}/api/agents?size=300`, {
          headers: { Authorization: `Bearer ${allowedToken}` },
        })
      ).json();
      const rowsForAllowed = listForAllowed.data.records as { name: string }[];
      expect(rowsForAllowed.some((r) => r.name === agentName), "被授权用户应能看到").toBe(true);
      console.log("[ACL] 被授权用户可见 ✓");
    } finally {
      await deleteStubAgent(request, headers, agentDbId);
      await request.delete(`${API}/api/users/${otherUserId}`, { headers: admin });
      await request.delete(`${API}/api/users/${allowedUserId}`, { headers: admin });
    }
  });
});
