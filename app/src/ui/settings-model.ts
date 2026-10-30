/**
 * 设置页的数据模型（纯逻辑，便于单测）。
 *
 * 桌面端的「pi 配置管理」走 Electron IPC 读写 settings.json，Web 服务**没有**对应接口。
 * 所以手机上能做的只有三类：
 *   1. 本地设置：外观、通知、连接与设备
 *   2. 只读概况：模型列表、技能/扩展列表、DSH 插件列表
 *   3. 有接口的开关：`/api/skills/toggle`、`/api/extensions/toggle`、`/runtime/permission`
 * 这个模块负责把后端那些"形状未固定"的 JSON 收拾成可直接渲染的行。
 */

export interface SkillRow {
  /** name + sourceId 才能唯一定位（后端不下发宿主路径） */
  key: string;
  name: string;
  sourceId: string;
  sourceLabel: string;
  description: string;
  enabled: boolean;
  /** 后端标了 valid=false 的技能点了也白点，UI 要置灰 */
  valid: boolean;
  /** 只允许用户手动 /skill:name 调用 */
  userOnly: boolean;
  warnings: string[];
}

export interface SkillSection {
  sourceId: string;
  label: string;
  rows: SkillRow[];
}

export interface ExtensionRow {
  key: string;
  source: string;
  name: string;
  scope: "user" | "project" | "unknown";
  enabled: boolean;
  projectId?: string;
  description: string;
}

export interface DshPluginRow {
  id: string;
  name: string;
  version: string;
  running: boolean;
  description: string;
}

export interface ModelRow {
  key: string;
  name: string;
  provider: string;
  modelId: string;
}

const SKILL_SOURCE_LABELS: Record<string, string> = {
  "pi-global": "pi 全局",
  "agents-global": "agents 全局",
  "pideck-global": "PiDeck 内置",
  "project-pi": "项目 pi",
  "project-agents": "项目 agents",
};

function str(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function bool(value: unknown): boolean {
  return value === true;
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" ? (value as Record<string, unknown>) : {};
}

/** `/api/skills` → 按来源分组、保持后端顺序。 */
export function normalizeSkills(raw: unknown): SkillSection[] {
  const payload = asRecord(raw);
  const skills = asArray(payload.skills);

  const rows: SkillRow[] = [];
  for (const item of skills) {
    const record = asRecord(item);
    const name = str(record.name).trim();
    const sourceId = str(record.sourceId).trim();
    if (!name || !sourceId) continue;
    rows.push({
      key: `${sourceId}::${name}`,
      name,
      sourceId,
      sourceLabel: str(record.sourceLabel).trim() || SKILL_SOURCE_LABELS[sourceId] || sourceId,
      description: str(record.description).trim(),
      enabled: record.enabled !== false,
      valid: record.valid !== false,
      userOnly: bool(record.userOnly),
      warnings: asArray(record.warnings).map((warning) => str(warning)).filter(Boolean),
    });
  }

  const order: string[] = [];
  const buckets = new Map<string, SkillRow[]>();
  for (const row of rows) {
    const bucket = buckets.get(row.sourceId);
    if (bucket) bucket.push(row);
    else {
      buckets.set(row.sourceId, [row]);
      order.push(row.sourceId);
    }
  }

  return order.map((sourceId) => ({
    sourceId,
    label: buckets.get(sourceId)?.[0]?.sourceLabel ?? SKILL_SOURCE_LABELS[sourceId] ?? sourceId,
    rows: buckets.get(sourceId) ?? [],
  }));
}

/** 技能开关成功后的本地状态回写（避免整页重拉）。 */
export function applySkillToggle(sections: SkillSection[], key: string, enabled: boolean): SkillSection[] {
  return sections.map((section) => ({
    ...section,
    rows: section.rows.map((row) => (row.key === key ? { ...row, enabled } : row)),
  }));
}

/** `/api/extensions` → 扁平列表。项目作用域的扩展带上 projectId 才能在 toggle 时定位。 */
export function normalizeExtensions(raw: unknown): ExtensionRow[] {
  const payload = asRecord(raw);
  const rows: ExtensionRow[] = [];
  for (const item of asArray(payload.extensions)) {
    const record = asRecord(item);
    const source = str(record.source) || str(record.path) || str(record.id);
    if (!source) continue;
    const scopeRaw = str(record.scope);
    const scope = scopeRaw === "user" || scopeRaw === "project" ? scopeRaw : "unknown";
    const projectId = str(record.projectId).trim() || undefined;
    const name = str(record.name) || str(record.title) || source.split(/[\\/]/).pop() || source;
    rows.push({
      key: `${scope}::${source}`,
      source,
      name,
      scope,
      enabled: record.enabled !== false,
      ...(projectId ? { projectId } : {}),
      description: str(record.description).trim(),
    });
  }
  return rows;
}

export function applyExtensionToggle(rows: ExtensionRow[], key: string, enabled: boolean): ExtensionRow[] {
  return rows.map((row) => (row.key === key ? { ...row, enabled } : row));
}

/** DSH 插件列表（形状未在文档里固定，宽松取）。 */
export function normalizeDshPlugins(raw: unknown): DshPluginRow[] {
  const rows: DshPluginRow[] = [];
  for (const item of asArray(raw)) {
    const record = asRecord(item);
    const id = str(record.id) || str(record.name);
    if (!id) continue;
    rows.push({
      id,
      name: str(record.name) || id,
      version: str(record.version),
      running: record.running === true || str(record.status) === "running",
      description: str(record.description).trim(),
    });
  }
  return rows;
}

/** 模型列表 → 设置页里「pi 概况」的只读行。 */
export function normalizeModels(raw: unknown): ModelRow[] {
  const rows: ModelRow[] = [];
  for (const item of asArray(raw)) {
    const record = asRecord(item);
    const provider = str(record.provider) || str(record.providerId);
    const modelId = str(record.id) || str(record.modelId);
    if (!provider && !modelId) continue;
    rows.push({
      key: `${provider}::${modelId}`,
      name: str(record.name) || modelId || provider,
      provider,
      modelId,
    });
  }
  return rows;
}

/** 令牌脱敏：设置页只显示前后各 4 位，避免截屏泄露。 */
export function maskToken(token: string): string {
  const value = token.trim();
  if (!value) return "（未设置）";
  if (value.length <= 8) return "*".repeat(value.length);
  return `${value.slice(0, 4)}…${value.slice(-4)}`;
}

/** 设置页里「需要在桌面端改」的说明（Web API 没有写配置的接口）。 */
export const DESKTOP_ONLY_NOTE =
  "模型供应商密钥、模型目录、Git/终端/代理/备份等配置走桌面端本地配置，Web 服务没有开放写接口；这里只做只读展示与有接口的开关。";
