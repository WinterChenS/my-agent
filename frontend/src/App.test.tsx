import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it } from "vitest";
import App from "@/App";
import { useAuthStore } from "@/stores/auth";

describe("App 路由与布局壳", () => {
  beforeEach(() => {
    useAuthStore.setState({ token: null, user: null });
  });

  it("未登录时重定向到登录页", () => {
    render(
      <MemoryRouter initialEntries={["/chat"]}>
        <App />
      </MemoryRouter>,
    );
    expect(screen.getByText("登录 my-agent")).toBeInTheDocument();
  });

  it("已登录时渲染侧边栏与各管理入口", async () => {
    useAuthStore.setState({
      token: "test-token",
      user: { id: 1, username: "admin", displayName: "平台管理员", role: "ADMIN" },
    });

    render(
      <MemoryRouter initialEntries={["/chat"]}>
        <App />
      </MemoryRouter>,
    );

    // 管理页/聊天页现在是按需加载（React.lazy），首帧会先渲染 Suspense 占位，
    // 所以这里用 findBy* 等异步 chunk 解析完成
    expect(await screen.findByText("my-agent")).toBeInTheDocument();
    // 侧边栏收敛成 4 个管理主菜单 + 聊天/记忆：同族页面进到主菜单后用 tab / 二级菜单区分
    for (const label of [
      "聊天",
      "记忆",
      "Agent 与模型",
      "能力",
      "定时任务",
      "系统设置",
    ]) {
      expect(await screen.findByRole("link", { name: label })).toBeInTheDocument();
    }
  });

  it("老地址会重定向到新的分区页（书签不失效）", async () => {
    useAuthStore.setState({
      token: "test-token",
      user: { id: 1, username: "admin", displayName: "平台管理员", role: "ADMIN" },
    });

    render(
      <MemoryRouter initialEntries={["/admin/models"]}>
        <App />
      </MemoryRouter>,
    );

    // 重定向后落到「Agent 与模型 / 模型」tab，页面标题由 ModelsPage 自己渲染
    expect(await screen.findByRole("tab", { name: "模型" })).toBeInTheDocument();
    // 模型页自身的内容渲染出来即说明重定向 + tab 外壳都通了
    expect(await screen.findAllByText(/模型列表|供应商/)).not.toHaveLength(0);
  });
});
