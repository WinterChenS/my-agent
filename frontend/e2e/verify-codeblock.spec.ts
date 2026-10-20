import { expect, test, type APIRequestContext } from "@playwright/test";

/**
 * 代码块可读性（用户反馈过「代码块几乎看不清」）。
 *
 * **自给自足**：自己造一个指向本地桩的 Agent（桩按模型名含 `code-fence` 返回带围栏代码块的 Markdown），
 * 不依赖库里恰好有一条含代码块的会话 —— 之前就是靠"扫前 10 个会话"，被 e2e 残留数据挤掉后直接红了。
 *
 * 判定方式不是"看着还行"，而是把 oklch 颜色在浏览器里转成 sRGB、算 **WCAG 对比度**，要求 > 4.5:1。
 */
const API = "http://localhost:8080";
const STUB = "http://localhost:8099/v1";

async function apiToken(request: APIRequestContext): Promise<string> {
  const res = await request.post(`${API}/api/auth/login`, {
    data: { username: "admin", password: "Admin@123" },
  });
  return (await res.json()).data.token as string;
}

test("代码块文字与背景对比度足够", async ({ page, request }) => {
  test.setTimeout(120_000);
  const stamp = Date.now();
  const token = await apiToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const agentId = `e2e-codeblock-${stamp}`;

  const provider = await (
    await request.post(`${API}/api/model-providers`, {
      headers,
      data: {
        name: `e2e-cb-prov-${stamp}`,
        providerType: "OPENAI_COMPATIBLE",
        baseUrl: STUB,
        apiKey: "stub-key",
        status: "ENABLED",
      },
    })
  ).json();
  const model = await (
    await request.post(`${API}/api/models`, {
      headers,
      data: { providerId: provider.data.id, modelId: `stub-code-fence-${stamp}`, status: "ENABLED" },
    })
  ).json();
  const agent = await (
    await request.post(`${API}/api/agents`, {
      headers,
      data: {
        name: agentId,
        agentId,
        sysPrompt: "你是助手。",
        modelId: model.data.id,
        permissionMode: "ALWAYS_ALLOW",
        maxIterations: 2,
        status: "ENABLED",
        skillIds: [],
        mcpServerIds: [],
      },
    })
  ).json();
  const agentIdNum = agent.data.id as number;
  let sessionId: number | null = null;

  try {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto("/login");
    await page.getByLabel("用户名").fill("admin");
    await page.getByLabel("密码").fill("Admin@123");
    await page.getByRole("button", { name: /登录/ }).click();
    await page.waitForURL(/\/chat/);

    // 新建会话并按**名字**选中我们刚造的 Agent（与其它用例一致的做法；
    // 直接 selectOption(数字) 之前不生效，会话会落到默认的 deepseek Agent 上 → 拿不到回复）
    await page.waitForSelector("[data-testid='session-item'], [data-testid='chat-input']", {
      timeout: 30000,
    });
    await page.getByTitle("新建会话").click();
    const agentSelect = page.locator("select");
    await expect(agentSelect).toContainText(agentId, { timeout: 20_000 });
    await agentSelect.selectOption({ label: agentId });

    const input = page.getByTestId("chat-input");
    await input.click();
    await page.keyboard.type("给我一段 JSON");
    await page.keyboard.press("Enter");

    const pre = page.locator("[data-testid='assistant-text'] pre").first();
    await expect(pre).toBeVisible({ timeout: 90_000 });

    // 关键：先等这一轮**跑完**再测量。
    // 流式过程中每次增量都会重建 DOM 节点，此时拿到的 pre 很快会 "not attached to the DOM"（实测）。
    await expect(page.getByTestId("chat-input")).toHaveAttribute("contenteditable", "true", {
      timeout: 90_000,
    });
    await page.waitForTimeout(500);

    /*
     * 取样必须**轮询到拿到非空颜色**为止。
     *
     * 为什么：一轮收尾时 chat items 会被整体替换（replayMessages），气泡 DOM 有一帧重建 ——
     * 恰好在这一帧取到的 `pre` 已经是**脱离文档的旧节点**，`getComputedStyle` 返回空字符串，
     * 于是对比度算成 1.00:1、用例假红（实测：单独跑绿、全量跑红，日志就是 `文字/背景 = / `）。
     */
    let styles = { preColor: "", preBg: "", codeColor: null as string | null };
    await expect
      .poll(
        async () => {
          styles = await page
            .locator("[data-testid='assistant-text'] pre")
            .first()
            .evaluate((el) => {
              const cs = getComputedStyle(el);
              const code = el.querySelector("code");
              const ccs = code ? getComputedStyle(code) : null;
              return {
                preColor: cs.color,
                preBg: cs.backgroundColor,
                codeColor: ccs?.color ?? null,
              };
            });
          return styles.preColor !== "" && styles.preBg !== "";
        },
        { timeout: 30_000, message: "pre 的颜色应能取到（说明拿到的是活节点）" },
      )
      .toBe(true);
    console.log("pre  文字/背景 =", styles.preColor, "/", styles.preBg);

    const contrast = await page.evaluate(
      ([fg, bg]) => {
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
        const l1 = lum(toRgb(fg));
        const l2 = lum(toRgb(bg));
        const [hi, lo] = l1 > l2 ? [l1, l2] : [l2, l1];
        return (hi + 0.05) / (lo + 0.05);
      },
      [styles.preColor, styles.preBg] as const,
    );
    console.log("pre 对比度 =", contrast.toFixed(2), ": 1");
    expect(contrast, "代码块文字与背景对比度过低").toBeGreaterThan(4.5);

    // 截图取「最后一个助手气泡」而不是那个 pre 元素：流式结束后 DOM 会重建，
    // 直接给 pre 截图会遇到 "Element is not attached to the DOM"（实测）
    await page
      .locator("[data-testid='assistant-text']")
      .last()
      .screenshot({ path: "../docs/acceptance/task-24-codeblock-fixed.png" });

    // 记下本次会话 id，收尾时删掉（不给库添垃圾）
    const sessions = await (await request.get(`${API}/api/sessions`, { headers })).json();
    sessionId = (sessions.data as { id: number; agentId: number | null }[]).find(
      (s) => s.agentId === agentIdNum,
    )?.id ?? null;
  } finally {
    if (sessionId != null) {
      await request.delete(`${API}/api/sessions/${sessionId}`, { headers });
    }
    await request.delete(`${API}/api/agents/${agentIdNum}`, { headers });
    await request.delete(`${API}/api/models/${model.data.id}`, { headers });
    await request.delete(`${API}/api/model-providers/${provider.data.id}`, { headers });
  }
});
