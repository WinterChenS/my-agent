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
