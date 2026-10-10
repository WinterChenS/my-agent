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

  it("已登录时渲染侧边栏与各管理入口", () => {
    useAuthStore.setState({
      token: "test-token",
      user: { id: 1, username: "admin", displayName: "平台管理员", role: "ADMIN" },
    });

    render(
      <MemoryRouter initialEntries={["/chat"]}>
        <App />
      </MemoryRouter>,
    );

    expect(screen.getByText("my-agent")).toBeInTheDocument();
    for (const label of ["聊天", "模型", "Agent", "Skill", "MCP", "审计"]) {
      expect(screen.getByRole("link", { name: label })).toBeInTheDocument();
    }
  });
});
