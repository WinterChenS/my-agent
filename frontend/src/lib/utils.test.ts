import { describe, expect, it } from "vitest";
import { cn } from "@/lib/utils";

describe("cn", () => {
  it("合并冲突的 Tailwind 类，后者生效", () => {
    expect(cn("p-2", "p-4")).toBe("p-4");
  });

  it("保留互不冲突的类", () => {
    expect(cn("flex", "items-center")).toBe("flex items-center");
  });

  it("忽略假值", () => {
    const falsy: string | undefined | false = false;
    expect(cn("flex", falsy, undefined)).toBe("flex");
  });
});
