import "@testing-library/jest-dom/vitest";
import { webcrypto } from "node:crypto";

/**
 * jsdom 未实现 scrollIntoView（浏览器里存在）。这里补齐，避免聊天页的自动滚动在测试里报错。
 */
if (typeof Element !== "undefined" && !Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = function scrollIntoView() {
    // no-op：测试不关心滚动位置
  };
}

/** jsdom 环境可能没有 crypto.randomUUID，用 Node 的 webcrypto 补齐。 */
if (typeof globalThis.crypto === "undefined" || !globalThis.crypto.randomUUID) {
  Object.defineProperty(globalThis, "crypto", {
    value: webcrypto,
    configurable: true,
  });
}

/**
 * jsdom 没实现 `URL.createObjectURL` / `revokeObjectURL`（浏览器里都有）。
 * 产物图片预览靠 blob URL，组件卸载时一定会 revoke —— 缺了会在 cleanup 里直接抛错。
 */
if (typeof URL !== "undefined") {
  if (typeof URL.createObjectURL !== "function") {
    URL.createObjectURL = () => "blob:test-url";
  }
  if (typeof URL.revokeObjectURL !== "function") {
    URL.revokeObjectURL = () => {};
  }
}
