/**
 * 后端能力矩阵：同一个按钮在 pi / dsh / acp / 生图 会话上到底有没有意义。
 *
 * 为什么要集中成一张表：这些入口散落在输入框 chips、顶栏 chips、长按菜单、
 * 额度面板、待办条里，逐个写 `if (backend === "acp")` 迟早漏一处，
 * 而漏掉的代价是「点了没反应」——用户只会以为 App 坏了。
 *
 * ACP 的结论来自桌面端实现（详见 docs/acp-desktop-research.md）：
 *   - 模型 / 思考档：ACP 的 model、mode、thought_level 属于 `session/set_config_option`，
 *     桌面端只走 IPC 下发；响应的 `/runtime/model`、`/runtime/thinking` 对 ACP 是 no-op，
 *     `/runtime/models` 恒返回 `[]`（AcpAgentManager.ts:258/410/859）。
 *   - 计划模式：靠 `extra.agentMessage` 注入隐藏指令，ACP 侧必被 `acp.hostInstructionsUnsupported` 拒。
 *   - 插话（steer）：ACP 没有 steer，回合中发消息一律 `acp.turnBusy`。
 *   - 分叉 / 改消息 / 删消息：历史由 agent CLI 的归档主导，运行时没有这些命令。
 *   - 供应商额度：ACP 会话的 provider 是转发层名 "acp"，探针查不到任何供应商。
 *   - 待办：`/api/sessions/:id/todo` 依赖 pi 会话条目的 filePath，ACP 恒 null。
 * 安全级别没有放进来：它另有 `securityWritable()`，因为还跟 `state.security` 是否存在有关。
 */

export type KnownBackend = "pi" | "dsh" | "acp" | "imagegen";

export interface BackendCapabilities {
  /** 模型选择 chip（真实可用，不是空面板） */
  model: boolean;
  /** 思考档位 chip */
  thinking: boolean;
  /** 计划模式按钮（注入 agentMessage） */
  plan: boolean;
  /** 运行中「插话」发送 */
  steer: boolean;
  /** 长按消息 → 从这里分叉 */
  fork: boolean;
  /** 长按消息 → 编辑 / 改完重发 / 删除 */
  editMessage: boolean;
  /** 上下文面板里的「供应商额度」查询 */
  usage: boolean;
  /** 输入框上方的待办条（依赖 /api/sessions/:id/todo） */
  todo: boolean;
}

/** 认不出来的 backend 一律按 pi 处理：老桌面端不下发该字段，默认行为就是 pi。 */
export function normalizeBackend(backend: unknown): KnownBackend {
  const kind = typeof backend === "string" ? backend.trim().toLowerCase() : "";
  if (kind === "dsh" || kind === "acp" || kind === "imagegen") return kind;
  return "pi";
}

const PI_CAPABILITIES: BackendCapabilities = {
  model: true,
  thinking: true,
  plan: true,
  steer: true,
  fork: true,
  editMessage: true,
  usage: true,
  todo: true,
};

const DSH_CAPABILITIES: BackendCapabilities = { ...PI_CAPABILITIES };

/**
 * ACP：只剩「看 + 发 + 批权限」。
 * 权限审批走 pendingUiRequests 的 select 卡片，不需要这里的开关。
 */
const ACP_CAPABILITIES: BackendCapabilities = {
  model: false,
  thinking: false,
  plan: false,
  steer: false,
  fork: false,
  editMessage: false,
  usage: false,
  todo: false,
};

const IMAGEGEN_CAPABILITIES: BackendCapabilities = { ...PI_CAPABILITIES };

export function backendCapabilities(backend: unknown): BackendCapabilities {
  switch (normalizeBackend(backend)) {
    case "acp":
      return { ...ACP_CAPABILITIES };
    case "dsh":
      return { ...DSH_CAPABILITIES };
    case "imagegen":
      return { ...IMAGEGEN_CAPABILITIES };
    default:
      return { ...PI_CAPABILITIES };
  }
}

/**
 * 侧栏徽标文案：pi 不显示（绝大多数会话都是 pi，标出来只是噪音）。
 * ACP 用大写，和 DSH 一致；生图用中文。
 */
export function backendBadge(backend: unknown): string {
  switch (normalizeBackend(backend)) {
    case "dsh":
      return "DSH";
    case "acp":
      return "ACP";
    case "imagegen":
      return "生图";
    default:
      return "";
  }
}

/**
 * 徽标后的补充说明（只进 title，不占行宽）：
 * ACP 会话必须让用户知道背后是哪个 CLI 在跑，否则两个 ACP 会话长得一模一样。
 */
export function backendBadgeHint(backend: unknown, acpToolName?: string | null): string {
  const badge = backendBadge(backend);
  if (!badge) return "";
  if (normalizeBackend(backend) !== "acp") return `${badge} 后端`;
  const tool = typeof acpToolName === "string" ? acpToolName.trim() : "";
  return tool ? `ACP 后端 · ${tool}` : "ACP 后端";
}

/**
 * 从 `/api/state` 的 `acpTools`（`{id,name}[]`，桌面端已按 acpEnabled + enabled 门控）
 * 里找某个 acpToolId 的显示名。取不到返回 null——不要回退成 id，那串用户不认识。
 */
export function acpToolNameOf(tools: unknown, toolId: unknown): string | null {
  if (!Array.isArray(tools)) return null;
  const wanted = typeof toolId === "string" ? toolId.trim() : "";
  if (!wanted) return null;
  for (const item of tools) {
    if (!item || typeof item !== "object") continue;
    const record = item as Record<string, unknown>;
    const id = typeof record.id === "string" ? record.id.trim() : "";
    if (id !== wanted) continue;
    const name = typeof record.name === "string" ? record.name.trim() : "";
    return name || null;
  }
  return null;
}
