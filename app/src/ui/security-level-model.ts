/**
 * 会话安全级别（桌面端 SecurityLevelMenu 的手机端投影）。
 *
 * 数据来源是 `/api/state` 的 `security` 字段（`WebSecurityState`）：
 *   { enabled, defaultLevelId, levels: [{id,name,description,builtin}], sessionOverrides }
 * 会话级覆盖只对 pi 会话有效 —— 服务端对 dsh / acp 直接 400 `securityUnsupportedBackend`，
 * 因为 DSH 走 runtime 的 permission 命令、ACP 由 agent CLI 自管（见 WebApiRoutes.ts:...）。
 *
 * 这里只做「读 + 整理成选择项」，写入走 `client.setSessionSecurityLevel`。
 */

export interface SecurityLevelRow {
  id: string;
  name: string;
  description: string;
  builtin: boolean;
}

export interface SecurityStateView {
  enabled: boolean;
  defaultLevelId: string;
  levels: SecurityLevelRow[];
  overrides: Record<string, string>;
}

export interface SecurityChoice {
  /** null = 清除会话覆盖，跟随全局默认 */
  id: string | null;
  label: string;
  description: string;
  active: boolean;
}

/** 服务端只在 `/api/state` 带 `security`；0.9.8 及更早没有这个字段，一律按「不可用」处理。 */
export function parseSecurityState(raw: unknown): SecurityStateView | null {
  if (!isRecord(raw)) return null;
  const levels: SecurityLevelRow[] = [];
  if (Array.isArray(raw.levels)) {
    for (const item of raw.levels) {
      if (!isRecord(item)) continue;
      const id = typeof item.id === "string" ? item.id.trim() : "";
      if (!id) continue;
      levels.push({
        id,
        name: typeof item.name === "string" && item.name.trim() ? item.name.trim() : id,
        description: typeof item.description === "string" ? item.description.trim() : "",
        builtin: item.builtin === true,
      });
    }
  }
  const overrides: Record<string, string> = {};
  if (isRecord(raw.sessionOverrides)) {
    for (const [key, value] of Object.entries(raw.sessionOverrides)) {
      if (typeof value === "string" && value.trim()) overrides[key] = value.trim();
    }
  }
  return {
    enabled: raw.enabled !== false,
    defaultLevelId: typeof raw.defaultLevelId === "string" ? raw.defaultLevelId.trim() : "",
    levels,
    overrides,
  };
}

/**
 * 会话当前生效的等级：有覆盖用覆盖，否则用全局默认。
 * 返回的 `inherited` 表示「来自全局默认」，UI 需要区分显示。
 */
export function securityLevelFor(
  state: SecurityStateView | null,
  sessionId: string | null,
): { level: SecurityLevelRow | null; inherited: boolean } {
  if (!state || !sessionId) return { level: null, inherited: true };
  const override = state.overrides[sessionId];
  const id = override ?? state.defaultLevelId;
  const level = state.levels.find((item) => item.id === id) ?? null;
  return { level, inherited: !override };
}

/** 选择器数据：第一项固定是「跟随全局默认」，避免用户找不到撤销覆盖的入口。 */
export function securityChoices(state: SecurityStateView | null, sessionId: string | null): SecurityChoice[] {
  if (!state) return [];
  const { level: current, inherited } = securityLevelFor(state, sessionId);
  const fallback = state.levels.find((item) => item.id === state.defaultLevelId);
  const choices: SecurityChoice[] = [
    {
      id: null,
      label: `跟随全局默认${fallback ? `（${fallback.name}）` : ""}`,
      description: inherited ? "当前就是这个" : "清除本会话的独立设置",
      active: inherited,
    },
  ];
  for (const level of state.levels) {
    choices.push({
      id: level.id,
      label: level.name,
      description: level.description,
      active: !inherited && current?.id === level.id,
    });
  }
  return choices;
}

/** 输入框上的 chip 文案：`安全 标准` / `安全`（读不到等级时退回中性文案）。 */
export function securityChipLabel(level: SecurityLevelRow | null): string {
  return level ? `安全 ${level.name}` : "安全";
}

/** dsh / acp 会话不能改安全级别（服务端会 400），UI 直接隐藏入口。 */
export function securityWritable(backend: unknown): boolean {
  const kind = typeof backend === "string" ? backend.trim().toLowerCase() : "";
  return kind !== "dsh" && kind !== "acp";
}

/** 关闭安全扩展时给一句解释，点开面板仍可看等级列表（与桌面端「只展示、不可切换」一致）。 */
export function securityDisabledHint(state: SecurityStateView | null): string {
  if (!state) return "桌面端版本较旧，没有安全级别接口（需要 PiDeck 0.9.9 及以上）。";
  if (!state.enabled) return "桌面端已关闭「安全管理」扩展，这里只展示等级，切换不生效。";
  return "";
}

/**
 * 输入框上「安全」「DSH」两个 chip 的显隐规则（0.9.9）：
 * - 安全级别只对 pi 会话有意义（dsh/acp 服务端回 400），且要 `state.security` 存在；
 * - 目标/子代理/技能面板只有 dsh 会话有。
 */
export function composerToolChips(
  backend: unknown,
  security: SecurityStateView | null,
  hasActiveSession: boolean,
): { security: boolean; dsh: boolean } {
  const kind = typeof backend === "string" ? backend.trim().toLowerCase() : "";
  return {
    security: hasActiveSession && security !== null && securityWritable(kind),
    dsh: hasActiveSession && kind === "dsh",
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
