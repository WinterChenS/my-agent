import { describe, expect, it } from "vitest";
import { cn, money } from "@/lib/utils";

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

/**
 * 回归：审计看板的「成本」KPI 曾是 `toFixed(2)`，一次极小调用（0.000016）会显示成 **0.00**，
 * 看起来像“没花钱”。小额必须多给几位小数。
 */
describe("money", () => {
  it("≥1 保留 2 位", () => {
    expect(money(21.398646)).toBe("21.40");
    expect(money(1)).toBe("1.00");
    expect(money("3.5")).toBe("3.50");
  });

  it("0.01 ≤ 金额 < 1 保留 4 位（不会塌成 0）", () => {
    expect(money(0.01616)).toBe("0.0162");
    expect(money(0.01)).toBe("0.0100");
  });

  it("极小金额（本次反馈的 0.000016）保留 6 位，不能显示成 0", () => {
    expect(money(0.000016)).toBe("0.000016");
    expect(money(0.000001)).toBe("0.000001");
  });

  it("0 / 空值 / 非法值", () => {
    expect(money(0)).toBe("0.00");
    expect(money(null)).toBe("—");
    expect(money(undefined)).toBe("—");
    expect(money("")).toBe("—");
    expect(money("abc")).toBe("—");
  });
});
