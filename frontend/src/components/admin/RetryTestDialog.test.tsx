import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { RetryTestDialog } from "@/components/admin/RetryTestDialog";

const getRetryPolicy = vi.fn();
const testModelRetry = vi.fn();

vi.mock("@/api/models", () => ({
  getRetryPolicy: (...args: unknown[]) => getRetryPolicy(...args),
  testModelRetry: (...args: unknown[]) => testModelRetry(...args),
}));

/**
 * 模型「重试测试」弹窗。
 *
 * <p>为什么要测：用户反馈"感觉没重试过"，这个弹窗就是给他们的证据来源——
 * 策略必须真的读后端配置，模拟/真实两种测试必须真的调对应接口并把结果讲清楚。
 */
describe("RetryTestDialog：重试测试", () => {
  beforeEach(() => {
    getRetryPolicy.mockReset();
    testModelRetry.mockReset();
    getRetryPolicy.mockResolvedValue({
      maxRetries: 3,
      initialBackoffMs: 2000,
      maxBackoffMs: 30000,
      multiplier: 2,
      timeoutMinutes: 5,
      retryOnUnknownStatus: true,
      retryOnStreamFailure: true,
      summary: "失败后最多重试 3 次（首次之外）；参数错误(400)不重试。",
    });
    testModelRetry.mockResolvedValue({
      ok: true,
      failures: 1,
      retries: 1,
      elapsedMs: 2100,
      sampleText: "第二次尝试（重试成功）",
      error: null,
    });
  });

  it("打开时展示后端返回的重试策略", async () => {
    render(
      <RetryTestDialog
        open
        onOpenChange={() => {}}
        model={{ id: 34, modelId: "deepseek-v4.1-flash" }}
      />,
    );

    expect(await screen.findByText("最多重试 3 次")).toBeInTheDocument();
    expect(screen.getByText(/参数错误\(400\)不重试/)).toBeInTheDocument();
  });

  it("模拟断流：调 simulate=true 并显示重试次数", async () => {
    render(
      <RetryTestDialog open onOpenChange={() => {}} model={{ id: 34, modelId: "m" }} />,
    );

    fireEvent.click(await screen.findByRole("button", { name: /模拟断流自检/ }));

    await waitFor(() => expect(testModelRetry).toHaveBeenCalledWith(34, true));
    expect(await screen.findByText(/实际重试/)).toBeInTheDocument();
    expect(screen.getByText("第二次尝试（重试成功）", { exact: false })).toBeInTheDocument();
  });

  it("真实调用：simulate=false", async () => {
    render(
      <RetryTestDialog open onOpenChange={() => {}} model={{ id: 34, modelId: "m" }} />,
    );

    fireEvent.click(await screen.findByRole("button", { name: /真实调用测试/ }));

    await waitFor(() => expect(testModelRetry).toHaveBeenCalledWith(34, false));
  });

  it("没有选中模型时真实调用按钮不可用", async () => {
    render(<RetryTestDialog open onOpenChange={() => {}} model={null} />);

    expect(await screen.findByRole("button", { name: /真实调用测试/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: /模拟断流自检/ })).toBeEnabled();
  });
});
