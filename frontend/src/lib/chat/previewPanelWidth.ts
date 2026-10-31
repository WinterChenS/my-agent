/**
 * 预览面板宽度的约束。
 *
 * <p>用户要求「面板可以铺满」（像 IDE 那样把聊天区挤到 0），所以**不再设上限**；
 * 只保留一个最小宽度，避免拖成一条缝之后内容不可读、也不知道往哪拖回来。
 *
 * <p>上限由调用方用**容器宽度**夹住（`clampPreviewWidth(w, containerWidth)`）——
 * 不能直接用 `window.innerWidth`，因为左侧还有导航栏，用窗口宽度会把面板推到屏幕外面。
 */
export const PREVIEW_PANEL_DEFAULT = 448;
export const PREVIEW_PANEL_MIN = 320;

/** 把想要的宽度夹到合法区间（拖拽时每帧都会调用）。 */
export function clampPreviewWidth(width: number, containerWidth: number): number {
  const max = Math.max(PREVIEW_PANEL_MIN, Math.round(containerWidth));
  return Math.min(Math.max(Math.round(width), PREVIEW_PANEL_MIN), max);
}

/** 面板占据的比例（0~1），铺满时是 1，用于判断"聊天区是否已经不可见"。 */
export function previewPanelRatio(width: number, containerWidth: number): number {
  if (containerWidth <= 0) return 0;
  return Math.min(1, Math.max(0, width / containerWidth));
}
