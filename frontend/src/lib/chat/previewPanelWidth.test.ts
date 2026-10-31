import { describe, expect, it } from "vitest";
import {
  clampPreviewWidth,
  PREVIEW_PANEL_DEFAULT,
  PREVIEW_PANEL_MIN,
  previewPanelRatio,
} from "./previewPanelWidth";

describe("clampPreviewWidth", () => {
  it("区间内的值原样保留（取整）", () => {
    expect(clampPreviewWidth(500.4, 1280)).toBe(500);
    expect(clampPreviewWidth(PREVIEW_PANEL_DEFAULT, 1280)).toBe(PREVIEW_PANEL_DEFAULT);
  });

  it("小于下限抬到下限；低于最小值的容器也不会返回负数", () => {
    expect(clampPreviewWidth(120, 1280)).toBe(PREVIEW_PANEL_MIN);
    expect(clampPreviewWidth(120, 200)).toBe(PREVIEW_PANEL_MIN);
  });

  it("可以铺满：上限就是容器宽度（用户要求允许挤掉聊天区）", () => {
    expect(clampPreviewWidth(5000, 1280)).toBe(1280);
    expect(clampPreviewWidth(1280, 1280)).toBe(1280);
    expect(clampPreviewWidth(900, 1000)).toBe(900);
  });

  it("容器变窄时宽度跟着收回来（不会溢出到屏幕外）", () => {
    expect(clampPreviewWidth(1600, 1000)).toBe(1000);
  });
});

describe("previewPanelRatio", () => {
  it("铺满时比例是 1，容器为 0 时不除零", () => {
    expect(previewPanelRatio(1280, 1280)).toBe(1);
    expect(previewPanelRatio(640, 1280)).toBe(0.5);
    expect(previewPanelRatio(640, 0)).toBe(0);
  });
});
