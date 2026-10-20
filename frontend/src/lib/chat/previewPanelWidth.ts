/**
 * 预览面板宽度的约束（用户要求：可以左右拖拽调整它和聊天区的比例）。
 *
 * <p>三条边界：
 * <ul>
 *   <li>不小于 {@link PREVIEW_PANEL_MIN}（再窄内容就挤成一团了）；</li>
 *   <li>不大于窗口宽度的 {@link PREVIEW_PANEL_MAX_RATIO}（也别把聊天区挤没）；</li>
 *   <li>不大于 {@link PREVIEW_PANEL_MAX}（超大屏上也没必要更宽）。</li>
 * </ul>
 */
export const PREVIEW_PANEL_DEFAULT = 448;
export const PREVIEW_PANEL_MIN = 320;
export const PREVIEW_PANEL_MAX = 896;
const PREVIEW_PANEL_MAX_RATIO = 0.7;

/** 这个窗口宽度下允许的最大面板宽度。 */
export function previewPanelMaxWidth(viewportWidth: number): number {
  return Math.max(
    PREVIEW_PANEL_MIN,
    Math.min(PREVIEW_PANEL_MAX, Math.round(viewportWidth * PREVIEW_PANEL_MAX_RATIO)),
  );
}

/** 把想要的宽度夹到合法区间（拖拽时每帧都会调用）。 */
export function clampPreviewWidth(width: number, viewportWidth: number): number {
  const max = previewPanelMaxWidth(viewportWidth);
  return Math.min(Math.max(Math.round(width), PREVIEW_PANEL_MIN), max);
}
