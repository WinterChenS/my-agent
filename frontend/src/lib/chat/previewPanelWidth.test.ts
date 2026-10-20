import { describe, expect, it } from "vitest";
import {
  clampPreviewWidth,
  PREVIEW_PANEL_DEFAULT,
  PREVIEW_PANEL_MAX,
  PREVIEW_PANEL_MIN,
  previewPanelMaxWidth,
} from "./previewPanelWidth";

describe("previewPanelMaxWidth", () => {
  it("窄窗口按 70% 算，宽窗口封顶 896", () => {
    expect(previewPanelMaxWidth(1280)).toBe(896); // 896 = 70% of 1280，正好到顶
    expect(previewPanelMaxWidth(1920)).toBe(PREVIEW_PANEL_MAX);
    expect(previewPanelMaxWidth(1000)).toBe(700);
  });

  it("极窄窗口不返回比最小值还小的上限（否则上下限互相打架）", () => {
    expect(previewPanelMaxWidth(320)).toBe(PREVIEW_PANEL_MIN);
  });
});

describe("clampPreviewWidth", () => {
  it("区间内的值原样保留（取整）", () => {
    expect(clampPreviewWidth(500.4, 1280)).toBe(500);
    expect(clampPreviewWidth(PREVIEW_PANEL_DEFAULT, 1280)).toBe(PREVIEW_PANEL_DEFAULT);
  });

  it("小于下限 → 抬到下限；大于上限 → 压到上限", () => {
    expect(clampPreviewWidth(120, 1280)).toBe(PREVIEW_PANEL_MIN);
    expect(clampPreviewWidth(5000, 1280)).toBe(previewPanelMaxWidth(1280));
  });

  it("上限随窗口变化（拖到很宽也不会把聊天区挤没）", () => {
    expect(clampPreviewWidth(900, 1000)).toBe(700); // 窄窗口：被 70% 压住
    expect(clampPreviewWidth(900, 1920)).toBe(PREVIEW_PANEL_MAX); // 宽窗口：被绝对上限压住
    expect(clampPreviewWidth(800, 1920)).toBe(800); // 两者之间：原样
  });
});
