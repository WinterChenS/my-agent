import { beforeEach, describe, expect, it, vi } from "vitest";
import forge from "node-forge";
import { encryptPassword, resetPasswordCipherCache } from "./passwordCipher";

/*
 * 用真实的 node-forge 生成一对密钥来验证"确实加密了、而且参数是对的"：
 * 服务端要求 RSA-OAEP + 摘要 SHA-256 + MGF1(SHA-256)，这里用同样的参数解回来比对。
 */
const apiFetch = vi.fn();
vi.mock("@/api/client", () => ({ apiFetch: (path: string) => apiFetch(path) }));

describe("passwordCipher 登录密码加密", () => {
  let keyPair: forge.pki.rsa.KeyPair;

  beforeEach(() => {
    // 与服务端一致的 2048 位（OAEP-SHA256 的明文上限关系到这里）
    keyPair = forge.pki.rsa.generateKeyPair({ bits: 2048, e: 0x10001 });
    apiFetch.mockReset();
    apiFetch.mockResolvedValue({
      keyId: "key-1",
      algorithm: "RSA-OAEP-256",
      publicKey: forge.pki.publicKeyToPem(keyPair.publicKey),
      ttlSeconds: 300,
    });
    resetPasswordCipherCache();
  });

  it("输出的是 OAEP(SHA-256) 密文，能解回密码/时间戳/随机数/会话", async () => {
    const result = await encryptPassword("s3cret!", "session-abcdefgh");

    expect(result.keyId).toBe("key-1");
    const bytes = forge.util.decode64(result.encryptedPassword);
    const plain = keyPair.privateKey.decrypt(bytes, "RSA-OAEP", {
      md: forge.md.sha256.create(),
      mgf1: { md: forge.md.sha256.create() },
    });
    const payload = JSON.parse(plain) as { p: string; t: number; n: string; s: string };

    expect(payload.p).toBe("s3cret!");
    expect(payload.s).toBe("session-abcdefgh");
    expect(typeof payload.t).toBe("number");
    expect(payload.n.length).toBeGreaterThan(8);
  });

  it("密文里搜不到明文密码（不是障眼法）", async () => {
    const result = await encryptPassword("SuperSecret123", "session-abcdefgh");
    expect(result.encryptedPassword).not.toContain("SuperSecret");
    // base64 解出来也不该出现明文
    expect(forge.util.decode64(result.encryptedPassword)).not.toContain("SuperSecret");
  });

  it("两次加密结果不同（同一密码也不会产生相同密文）", async () => {
    const first = await encryptPassword("same-password", "session-abcdefgh");
    const second = await encryptPassword("same-password", "session-abcdefgh");
    expect(first.encryptedPassword).not.toBe(second.encryptedPassword);
  });

  it("公钥会缓存，不会每次登录都打服务端", async () => {
    await encryptPassword("a", "session-abcdefgh");
    await encryptPassword("b", "session-abcdefgh");
    expect(apiFetch).toHaveBeenCalledTimes(1);
  });

  it("reset 之后会重新取公钥（服务端轮换密钥时用）", async () => {
    await encryptPassword("a", "session-abcdefgh");
    resetPasswordCipherCache();
    await encryptPassword("b", "session-abcdefgh");
    expect(apiFetch).toHaveBeenCalledTimes(2);
  });
});
