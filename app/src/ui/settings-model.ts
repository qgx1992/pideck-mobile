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
  /** dynamic = 动态 Cordis 插件（可启停/卸载）；static = 静态 Loader 条目（只读） */
  kind: "dynamic" | "static";
  /** 归属 DSH host 的 agentId（dynamic 才有，启停/卸载时服务端自己按 sessionId 找，这里仅作展示） */
  packageId: string;
  /** 服务端给的原始状态文案（running/stopped/error/...） */
  status: string;
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
/**
 * `/api/dsh/plugins` → 平铺的行。
 *
 * 0.9.9 起服务端回 `{ dynamic: [...], static: [...] }`（WebApiRoutes.ts 的 dsh/plugins 分支）：
 *   dynamic[i] = { pluginId, agentId, packages: [{packageId,name,purpose,...}], currentPackageId?, activeRun?, status? }
 *   static[i]  = { entryId, moduleName, enabled, fiberPhase, origin? }
 * 更早的版本只有一个 `plugins` 平铺数组，同样兼容。
 */
export function normalizeDshPlugins(raw: unknown): DshPluginRow[] {
  const payload = asRecord(raw);
  const dynamic = Array.isArray(raw) ? [] : asArray(payload.dynamic);
  const statics = Array.isArray(raw) ? [] : asArray(payload.static);
  const legacy = Array.isArray(raw) ? raw : asArray(payload.plugins);

  const rows: DshPluginRow[] = [];
  for (const item of [...dynamic, ...legacy]) {
    const record = asRecord(item);
    const packages = asArray(record.packages).map(asRecord);
    const current = str(record.currentPackageId).trim();
    const currentPackage = packages.find((pkg) => str(pkg.packageId) === current) ?? packages[0];
    const id = str(record.pluginId).trim() || str(record.id).trim() || str(record.name).trim();
    if (!id) continue;
    const status = str(record.status).trim();
    rows.push({
      id,
      name: str(currentPackage?.name).trim() || str(record.name).trim() || id,
      version: str(record.version).trim(),
      running: record.activeRun != null || record.running === true || status === "running",
      description:
        str(currentPackage?.purpose).trim() ||
        str(record.purpose).trim() ||
        str(record.description).trim() ||
        str(record.error).trim(),
      kind: "dynamic",
      packageId: current || str(currentPackage?.packageId).trim(),
      status,
    });
  }

  for (const item of statics) {
    const record = asRecord(item);
    const id = str(record.entryId).trim() || str(record.id).trim();
    if (!id) continue;
    const origin = str(record.origin).trim();
    const phase = str(record.fiberPhase).trim();
    rows.push({
      id,
      name: str(record.moduleName).trim() || id,
      version: "",
      running: record.enabled === true,
      description: [origin === "user" ? "用户自装" : origin === "builtin" ? "随包内置" : "", phase]
        .filter(Boolean)
        .join(" · "),
      kind: "static",
      packageId: "",
      status: record.enabled === true ? "running" : "stopped",
    });
  }
  return rows;
}

/** 只有动态插件能启停/卸载（静态条目没有对应的 Web 路由）。 */
export function dshPluginActionable(row: DshPluginRow): boolean {
  return row.kind === "dynamic";
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
