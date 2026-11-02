import { afterEach, describe, expect, it } from "vitest";
import { newId } from "@/lib/id";

/**
 * id 生成必须在内网 http 部署下也能用。
 *
 * <p>真实故障：`crypto.randomUUID` 只在安全上下文（HTTPS / localhost）存在，
 * 部署到 `http://localhost:18080` 后一点发送就抛 TypeError，请求压根没发出去。
 */
describe("newId：非安全上下文也要能生成 id", () => {
  const original = globalThis.crypto;

  afterEach(() => {
    Object.defineProperty(globalThis, "crypto", { value: original, configurable: true });
  });

  it("有 randomUUID 时用标准实现（v4 格式）", () => {
    const id = newId();
    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });

  it("没有 randomUUID 时降级，不抛异常", () => {
    // 模拟内网 http 环境：randomUUID 不存在
    Object.defineProperty(globalThis, "crypto", {
      value: { getRandomValues: (arr: Uint8Array) => original.getRandomValues(arr) },
      configurable: true,
    });

    const id = newId();
    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });

  it("连 getRandomValues 都没有也能兜底", () => {
    Object.defineProperty(globalThis, "crypto", { value: {}, configurable: true });
    expect(newId()).toMatch(/^[0-9a-f]{8}-/);
  });

  it("连续生成不重复", () => {
    const ids = new Set(Array.from({ length: 200 }, () => newId()));
    expect(ids.size).toBe(200);
  });
});
