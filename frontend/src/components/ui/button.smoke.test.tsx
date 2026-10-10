import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Button } from "@/components/ui/button";

describe("shadcn Button（验证 @/components/ui 别名可用）", () => {
  it("渲染并可点击", () => {
    render(<Button>确定</Button>);
    expect(screen.getByRole("button", { name: "确定" })).toBeInTheDocument();
  });
});
