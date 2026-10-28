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

/** 反向：生成配对链接（自测 / 传递用）。 */
export function buildPairingUrl(baseUrl: string, token: string): string {
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
