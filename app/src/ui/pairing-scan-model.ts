/**
 * 扫码结果 → 配对链接。
 *
 * 纯逻辑，不碰相机/插件，所以能直接单测。
 * 真正「能不能连」由 @core 的 parsePairingInput 判定（它会给出人话报错），
 * 这里只负责把二维码里读到的东西洗干净、挑出最像配对链接的那一条。
 */

const ZERO_WIDTH = /[\u200b-\u200f\u202a-\u202e\ufeff]/g;

/**
 * 清洗二维码内容。
 *
 * 实测/预想的脏数据：
 *   - 前后夹空白、换行（桌面端把链接放进一段文本里生成二维码时很常见）
 *   - 外面包一层引号 / 反引号 / 尖括号（从聊天记录里复制再生成二维码）
 *   - 整串被 URL 编码过（`http%3A%2F%2F192.168...`）
 *   - 零宽字符、BOM（某些扫码 App 会加）
 *   - "PAIRING: " / "URL: " 之类的前缀
 */
export function normalizeScannedText(raw: string): string {
  let text = (raw ?? "").replace(ZERO_WIDTH, "").replace(/\r\n?/g, "\n").trim();
  if (!text) return "";

  // "PAIRING: http://..." —— 只取冒号后面看起来像地址的部分
  const prefixed = /^[a-z_\u4e00-\u9fa5][a-z0-9_ \u4e00-\u9fa5-]{0,15}\s*[:：]\s*(.+)$/i.exec(text);
  if (prefixed?.[1] && /([a-z][a-z0-9+.-]*:\/\/|^[\w.-]+:\d+)/i.test(prefixed[1])) {
    text = prefixed[1].trim();
  }

  // 只取第一行（二维码里可能还带了说明文字）
  const firstLine = text.split("\n")[0]?.trim() ?? "";
  if (firstLine) text = firstLine;

  // 去掉成对包裹的引号 / 反引号 / 尖括号 / 全角括号
  text = text.replace(/^["'`<（(【\[]+/, "").replace(/["'`>）)】\]]+$/, "").trim();

  // 整串被 URL 编码：解一次看看是不是更像链接
  if (/^(https?|pideck)%3a/i.test(text)) {
    try {
      const decoded = decodeURIComponent(text);
      if (decoded !== text) text = decoded.trim();
    } catch {
      // 编码坏了就保持原样，交给 parsePairingInput 报错
    }
  }

  return text;
}

/**
 * 便宜的形态预判：http(s) 地址且带 token 参数。
 *
 * 只用来在扫到多个码时排序挑一个，不作为最终校验——
 * 最终校验走 parsePairingInput，那边的报错更准确。
 */
export function looksLikePairingShape(text: string): boolean {
  const value = normalizeScannedText(text);
  if (!/^https?:\/\//i.test(value)) return false;
  if (!/[?&]token=/i.test(value)) return false;
  return /[?&]token=[^\s&#]{8,128}/i.test(value);
}

export type ScanPickReason = "empty" | "foreign";

export interface ScanPick {
  /** 挑出来的链接（已归一化）；没有则为 null */
  link: string | null;
  /** 读到的原始文本条数（排查「为什么没识别」用，不含内容本身，避免把 token 写进日志） */
  seen: number;
  /** link 为 null 时的原因 */
  reason?: ScanPickReason;
}

/**
 * 从一组扫码文本里挑出配对链接。
 *
 * 优先完全匹配形态的；都没有就把第一条非空文本交出去，
 * 让 parsePairingInput 给出「链接里没有 token」这种更具体的错误提示。
 */
export function pickScannedLink(texts: readonly string[]): ScanPick {
  const cleaned = texts.map(normalizeScannedText).filter((text) => text.length > 0);
  if (cleaned.length === 0) return { link: null, seen: texts.length, reason: "empty" };

  const matched = cleaned.find(looksLikePairingShape);
  if (matched) return { link: matched, seen: cleaned.length };

  // 扫到了东西但不是配对链接：交给上层报错，不要静默失败
  return { link: cleaned[0] ?? null, seen: cleaned.length, reason: "foreign" };
}

/** 给用户看的失败文案。 */
export function describeScanMiss(pick: ScanPick): string {
  if (pick.link) return "";
  return pick.reason === "empty"
    ? "没读到二维码内容：把二维码放进取景框正中，或换一张更清晰的图"
    : "读到内容了，但没有二维码链接；确认扫的是桌面端「扫码访问」那张码";
}
