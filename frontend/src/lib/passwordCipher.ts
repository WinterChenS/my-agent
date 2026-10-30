import forge from "node-forge";
import { apiFetch } from "@/api/client";
import { newId } from "./id";

/**
 * 登录密码的传输加密（前端加密 / 后端解密）。
 *
 * <p>为什么需要它：内网是 `http://192.168.x.x`，密码在链路上是明文，抓个包就看到了。
 * 这层能挡住"被动嗅探"，但**不能替代 HTTPS**（公钥和加密代码对使用者是可见的）。
 *
 * <p>为什么用 node-forge 而不是 WebCrypto：`crypto.subtle` **只在安全上下文**
 * （HTTPS / localhost）里存在，内网 http 打开时它是 undefined —— 和 `navigator.clipboard`、
 * `crypto.randomUUID` 是同一个坑。node-forge 是纯 JS 实现，到处都能跑。
 *
 * <p>算法必须与服务端严格一致：**RSA-2048 / OAEP，摘要 SHA-256，MGF1 也是 SHA-256**
 * （forge 默认 MGF1 用 SHA-1，所以这里必须显式指定，否则 Java 那边解不开）。
 */

export interface LoginKey {
  keyId: string;
  algorithm: string;
  publicKey: string;
  ttlSeconds: number;
}

export interface EncryptedPassword {
  keyId: string;
  /** RSA-OAEP(SHA-256) 密文的 base64 */
  encryptedPassword: string;
}

let cached: { key: LoginKey; fetchedAt: number } | null = null;

/** 公钥缓存在内存里（服务端会轮换；登录失败时调 reset 重新取）。 */
async function currentKey(): Promise<LoginKey> {
  const now = Date.now();
  if (cached && now - cached.fetchedAt < Math.max(30, cached.key.ttlSeconds - 10) * 1000) {
    return cached.key;
  }
  const key = await apiFetch<LoginKey>("/api/auth/login-key");
  cached = { key, fetchedAt: now };
  return key;
}

export function resetPasswordCipherCache(): void {
  cached = null;
}

/**
 * 把密码加密成可以安全上路的密文。
 *
 * <p>明文里除了密码还带了三样东西（都由服务端校验，重放整段密文没用）：
 * - `t` 毫秒时间戳：太旧直接拒
 * - `n` 一次性随机数：服务端去重，同一段密文只能用一次
 * - `s` 会话标识：和本次登录/验证码会话绑定
 */
export async function encryptPassword(
  password: string,
  sessionKey: string,
): Promise<EncryptedPassword> {
  const key = await currentKey();
  // 载荷要能塞进 RSA 明文上限：2048 位 + OAEP(SHA-256) 只有 190 字节。
  // 所以随机数压到 16 个 hex 字符（仍有 2^64 空间，配合 120 秒有效期足够防重放）。
  const nonce = newId().replace(/-/g, "").slice(0, 16);
  const payload = JSON.stringify({
    p: password,
    t: Date.now(),
    n: nonce,
    s: sessionKey,
  });

  const publicKey = forge.pki.publicKeyFromPem(key.publicKey);
  let ciphertext: string;
  try {
    ciphertext = publicKey.encrypt(payload, "RSA-OAEP", {
      md: forge.md.sha256.create(),
      mgf1: { md: forge.md.sha256.create() },
    });
  } catch {
    // 只有"密码特别长"才会走到这里（明文上限约 190 字节 - 载荷开销）
    throw new Error("密码过长，请缩短后重试");
  }

  return { keyId: key.keyId, encryptedPassword: forge.util.encode64(ciphertext) };
}
