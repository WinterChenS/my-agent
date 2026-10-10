import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";
import App from "@/App";

describe("App 布局壳", () => {
  it("渲染侧边栏与各管理入口", () => {
    render(
      <MemoryRouter>
        <App />
      </MemoryRouter>,
    );
    expect(screen.getByText("my-agent")).toBeInTheDocument();
    for (const label of ["聊天", "模型", "Agent", "Skill", "MCP", "审计"]) {
      expect(screen.getByRole("link", { name: label })).toBeInTheDocument();
    }
  });
});
