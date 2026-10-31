/**
 * 配对：把桌面端「扫码访问」二维码 / 手填地址，解析成 { baseUrl, token }。
 *
 * 桌面端二维码里的链接形如：
 *   http://192.168.1.20:8765/?token=8f3c...（局域网，token 已附带）
 * Tailscale 场景可能是：
 *   https://my-pc.tailnet.ts.net/?token=8f3c...
 * 也接受不带 scheme 的 "192.168.1.20:8765/?token=..."（手输兜底）。
 */

import { PiDeckError } from "./api/errors.ts";
import { normalizeBaseUrl } from "./api/client.ts";

export interface PairingPayload {
  baseUrl: string;
  token: string;
  /** 原始输入，便于日志脱敏前的排错（不要写进持久化） */
  raw: string;
}

const TOKEN_MIN = 8;
const TOKEN_MAX = 128;

export function parsePairingInput(input: string): PairingPayload {
  const raw = input.trim();
  if (!raw) throw new PiDeckError({ kind: "protocol", message: "配对链接为空" });

  let candidate = raw;
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(candidate)) {
    // 手输兜底：允许省略 http://
    candidate = `http://${candidate}`;
  }

  let url: URL;
  try {
    url = new URL(candidate);
  } catch {
    throw new PiDeckError({ kind: "protocol", message: `无法解析为地址：${raw}` });
  }

  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new PiDeckError({ kind: "protocol", message: `不支持的协议：${url.protocol}` });
  }

  const token = (url.searchParams.get("token") ?? "").trim();
  if (!token) {
    throw new PiDeckError({
      kind: "protocol",
      message: "链接里没有 token：请在桌面端「Web 服务」页扫码或复制完整链接",
    });
  }
  if (token.length < TOKEN_MIN || token.length > TOKEN_MAX || /\s/.test(token)) {
    throw new PiDeckError({
      kind: "protocol",
      message: `token 形状不合法（应为 ${TOKEN_MIN}-${TOKEN_MAX} 个可打印字符）`,
    });
  }

  return { baseUrl: normalizeBaseUrl(url.toString()), token, raw };
}

/** 反向：生成配对链接（自测 / 传递用）。 */export function buildPairingUrl(baseUrl: string, token: string): string {
  const normalized = normalizeBaseUrl(baseUrl);
  const url = new URL(normalized);
  url.searchParams.set("token", token);
  return url.toString();
}

/** 判断链接是否可能是 PiDeck 的配对链接（扫码时先拦一道）。 */
export function looksLikePairingUrl(input: string): boolean {
  try {
    parsePairingInput(input);
    return true;
  } catch {
    return false;
  }
}

const PRIVATE_SUFFIXES = [".local", ".lan", ".home", ".internal", ".ts.net"];

/**
 * 主机名是否落在「本机 / 局域网 / Tailscale」这些私有范围。
 *
 * 只用于风险提示：私有范围内走明文 http 是正常用法（局域网内没有证书），
 * 一旦出了这个范围，明文意味着令牌会裸露在公网上。
 */
export function isPrivateHostname(hostname: string): boolean {
  const host = hostname.trim().toLowerCase().replace(/^\[|\]$/g, "");
  if (!host) return false;
  if (host === "localhost" || host.endsWith(".localhost")) return true;
  if (PRIVATE_SUFFIXES.some((suffix) => host.endsWith(suffix))) return true;

  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) {
    const [a, b] = host.split(".").map(Number) as [number, number, number, number];
    if (a === 127 || a === 10) return true;
    if (a === 192 && b === 168) return true;
    if (a === 172 && b >= 16 && b <= 31) return true;
    if (a === 169 && b === 254) return true;
    // Tailscale 落在 100.64.0.0/10（CGNAT），也是私有
    if (a === 100 && b >= 64 && b <= 127) return true;
    return false;
  }

  if (host === "::1") return true;
  if (/^f[cd][0-9a-f]{0,2}:/.test(host)) return true; // fc00::/7 唯一本地地址
  if (/^fe[89ab][0-9a-f]?:/.test(host)) return true; // fe80::/10 链路本地
  return false;
}

export interface PairingRisk {
  /** 需要用户确认后才能连 */
  needsConfirm: boolean;
  /** 逐条说明，直接显示给用户 */
  messages: string[];
}

/**
 * 配对前的风险判断。
 *
 * 扫码的结果是可以被伪造的：任何一张写着 `http://<陌生 IP>:8765/?token=...` 的二维码
 * 都会让 App 把本机令牌发过去。所以出了私有网段就要明确问一句。
 */
export function pairingRisk(payload: { baseUrl: string }): PairingRisk {
  let url: URL;
  try {
    url = new URL(payload.baseUrl);
  } catch {
    return { needsConfirm: true, messages: [`地址无法解析：${payload.baseUrl}`] };
  }
  const host = url.hostname;
  const isPrivate = isPrivateHostname(host);
  const plaintext = url.protocol === "http:";
  const messages: string[] = [];
  if (!isPrivate) messages.push(`${host} 不在局域网范围内，请确认这确实是你的电脑`);
  if (plaintext && !isPrivate) messages.push("而且是明文 http：令牌会裸露在公网上");
  return { needsConfirm: messages.length > 0, messages };
}
