/**
 * 配对历史：支持保存多个 PiDeck 连接（家里/公司/Tailscale），重新配对时可一键切回。
 *
 * 存储放在 localStorage；上架前应换 @capacitor/preferences + 原生加密。
 */

export interface SavedPairing {
  baseUrl: string;
  token: string;
  savedAt: number;
  /** 最近一次成功连接的时间，用于排序 */
  lastUsedAt?: number;
  /** 用户可改的备注名，例如「家里台式机」 */
  label?: string;
}

const KEY = ["pideck", "pairing", "v1"].join("-");
const HISTORY_KEY = ["pideck", "pairing", "history", "v1"].join("-");
const MAX_HISTORY = 12;

function sanitize(raw: unknown): SavedPairing | null {
  if (!raw || typeof raw !== "object") return null;
  const parsed = raw as Partial<SavedPairing>;
  if (typeof parsed.baseUrl !== "string" || typeof parsed.token !== "string") return null;
  if (!parsed.baseUrl || !parsed.token) return null;
  return {
    baseUrl: parsed.baseUrl,
    token: parsed.token,
    savedAt: typeof parsed.savedAt === "number" ? parsed.savedAt : 0,
    ...(typeof parsed.lastUsedAt === "number" ? { lastUsedAt: parsed.lastUsedAt } : {}),
    ...(typeof parsed.label === "string" && parsed.label.trim() ? { label: parsed.label.trim() } : {}),
  };
}

/** 当前生效的连接。 */
export function loadPairing(): SavedPairing | null {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? sanitize(JSON.parse(raw)) : null;
  } catch {
    return null;
  }
}

export function savePairing(pairing: SavedPairing): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(pairing));
  } catch {
    /* 隐私模式写不进去，忽略 */
  }
  rememberPairing(pairing);
}

export function clearPairing(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* ignore */
  }
}

/* ---------------------------- 历史列表 ---------------------------- */

function readHistory(): SavedPairing[] {
  try {
    const raw = localStorage.getItem(HISTORY_KEY);
    const parsed = raw ? (JSON.parse(raw) as unknown) : [];
    if (!Array.isArray(parsed)) return [];
    return parsed.map(sanitize).filter((item): item is SavedPairing => item !== null);
  } catch {
    return [];
  }
}

function writeHistory(list: SavedPairing[]): void {
  try {
    localStorage.setItem(HISTORY_KEY, JSON.stringify(list.slice(0, MAX_HISTORY)));
  } catch {
    /* ignore */
  }
}

/** 历史连接，按最近使用倒序。 */
export function listPairings(): SavedPairing[] {
  return readHistory().sort((a, b) => (b.lastUsedAt ?? b.savedAt) - (a.lastUsedAt ?? a.savedAt));
}

/**
 * 记入历史。同一 baseUrl 视为同一台机器：更新 token 与时间，保留原备注名。
 */
export function rememberPairing(pairing: SavedPairing, options: { touch?: boolean } = {}): void {
  const list = readHistory();
  const index = list.findIndex((item) => item.baseUrl === pairing.baseUrl);
  // 同一毫秒内连续写入时 Date.now() 会相等，导致排序不稳定；用已存最大值 +1 兜底
  const now = Math.max(Date.now(), ...list.map((item) => item.lastUsedAt ?? 0)) + 1;

  if (index >= 0) {
    const previous = list[index]!;
    list[index] = {
      ...previous,
      token: pairing.token,
      savedAt: pairing.savedAt || previous.savedAt,
      lastUsedAt: options.touch === false ? previous.lastUsedAt : now,
      ...(pairing.label ? { label: pairing.label } : {}),
    };
  } else {
    list.push({ ...pairing, lastUsedAt: options.touch === false ? undefined : now });
  }

  writeHistory(list);
}

export function forgetPairing(baseUrl: string): void {
  writeHistory(readHistory().filter((item) => item.baseUrl !== baseUrl));
}

export function renamePairing(baseUrl: string, label: string): void {
  const list = readHistory();
  const index = list.findIndex((item) => item.baseUrl === baseUrl);
  if (index < 0) return;
  const trimmed = label.trim();
  const next = { ...list[index]! };
  if (trimmed) next.label = trimmed;
  else delete next.label;
  list[index] = next;
  writeHistory(list);
}

/** 展示用名称：优先备注，其次 host:port。 */
export function pairingLabel(pairing: SavedPairing): string {
  if (pairing.label) return pairing.label;
  try {
    const url = new URL(pairing.baseUrl);
    return url.host || pairing.baseUrl;
  } catch {
    return pairing.baseUrl;
  }
}

/** 判断两条记录是否指向同一台机器（忽略 token）。 */
export function samePairingHost(a: SavedPairing, b: SavedPairing): boolean {
  return a.baseUrl === b.baseUrl;
}
