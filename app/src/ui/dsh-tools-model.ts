/**
 * DSH 会话的「工具面板」数据整理（对齐桌面端 DshAgentToolsPanel）。
 *
 * 三个只读接口（PiDeck 0.9.9 起）：
 *   GET /api/sessions/:id/dsh/goal       → { goal: { objective, phase, roundsStarted, maxGoalRounds } | null }
 *   GET /api/sessions/:id/dsh/subagents  → { subagents: [{ id, label?, activity, kind?, mode? }] }
 *   GET /api/sessions/:id/dsh/skills     → { skills: [{ name, description, whenToUse?, modelInvocable }] }
 *   （子代理详情另有一条：/dsh/subagents/:childSessionId/history → { messages, hasMore }）
 *
 * 服务端在没有活跃 runtime 时直接返回空集合（不报错），所以「空」是正常态，不能当错误。
 * 手机端只做只读展示：目标的 pause/resume/complete 走 runtime 命令，不在这一层做。
 */

export interface DshGoalView {
  objective: string;
  phase: string;
  roundsStarted: number;
  maxGoalRounds: number;
}

export interface DshSubagentView {
  id: string;
  label: string;
  running: boolean;
  diagnostic: boolean;
  modeLabel: string;
}

export interface DshSkillView {
  name: string;
  description: string;
  whenToUse: string;
  modelInvocable: boolean;
}

export interface DshHistoryLine {
  role: string;
  text: string;
}

export const DSH_EMPTY_GOAL = "这个 DSH 会话还没有设置目标。";
export const DSH_EMPTY_SUBAGENTS = "没有子代理。";
export const DSH_EMPTY_SKILLS = "没有可用的技能。";

/** `{ goal: {...} | null }` → 视图；目标缺 objective 视为没有目标。 */
export function parseDshGoal(raw: unknown): DshGoalView | null {
  const record = isRecord(raw) ? raw : null;
  const goal = record && isRecord(record.goal) ? record.goal : null;
  if (!goal) return null;
  const objective = str(goal.objective);
  if (!objective) return null;
  return {
    objective,
    phase: str(goal.phase) || "active",
    roundsStarted: num(goal.roundsStarted),
    maxGoalRounds: num(goal.maxGoalRounds),
  };
}

export function goalPhaseLabel(phase: string): string {
  switch (phase) {
    case "paused":
      return "已暂停";
    case "blocked":
      return "受阻";
    case "complete":
      return "已完成";
    default:
      return "进行中";
  }
}

/** `第 3/10 轮`；没有上限时只写已用轮次。 */
export function goalRoundsText(goal: DshGoalView): string {
  if (goal.maxGoalRounds > 0) return `第 ${goal.roundsStarted}/${goal.maxGoalRounds} 轮`;
  return goal.roundsStarted > 0 ? `已进行 ${goal.roundsStarted} 轮` : "";
}

export function parseDshSubagents(raw: unknown): DshSubagentView[] {
  const list = isRecord(raw) && Array.isArray(raw.subagents) ? raw.subagents : [];
  const rows: DshSubagentView[] = [];
  for (const item of list) {
    if (!isRecord(item)) continue;
    const id = str(item.id);
    if (!id) continue;
    const mode = str(item.mode);
    rows.push({
      id,
      label: str(item.label) || id,
      running: str(item.activity) === "running",
      diagnostic: str(item.kind) === "diagnostic",
      modeLabel: mode === "continuable" ? "可续跑" : mode === "one-shot" || mode === "oneshot" ? "一次性" : "",
    });
  }
  return rows;
}

export function parseDshSkills(raw: unknown): DshSkillView[] {
  const list = isRecord(raw) && Array.isArray(raw.skills) ? raw.skills : [];
  const rows: DshSkillView[] = [];
  for (const item of list) {
    if (!isRecord(item)) continue;
    const name = str(item.name);
    if (!name) continue;
    rows.push({
      name,
      description: str(item.description),
      whenToUse: str(item.whenToUse),
      modelInvocable: item.modelInvocable !== false,
    });
  }
  return rows;
}

/** 子代理历史：消息体里的 text 可能是字符串，也可能是 parts 数组（只取文本片段）。 */
export function parseDshSubagentHistory(raw: unknown): DshHistoryLine[] {
  const record = isRecord(raw) ? raw : null;
  const list = record && Array.isArray(record.messages) ? record.messages : [];
  const lines: DshHistoryLine[] = [];
  for (const item of list) {
    if (!isRecord(item)) continue;
    const text = messageText(item);
    if (!text) continue;
    lines.push({ role: str(item.role) || "unknown", text });
  }
  return lines;
}

function messageText(message: Record<string, unknown>): string {
  const direct = str(message.text);
  if (direct) return direct;
  if (Array.isArray(message.parts)) {
    const pieces: string[] = [];
    for (const part of message.parts) {
      if (!isRecord(part)) continue;
      const text = str(part.text);
      if (text) pieces.push(text);
    }
    return pieces.join("\n").trim();
  }
  return "";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function str(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function num(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? Math.round(value) : 0;
}
