/**
 * 后端选择（b 方案）：新建会话时挑 pi / DSH / 某个 ACP CLI，草稿期还能换。
 *
 * 为什么不能照搬桌面端的「ACP → 子菜单选工具」两级：手机上一次点击就该到位，
 * 所以 ACP 直接展开成「一个工具一行」的平铺列表（通常 1~3 个）。
 *
 * 三条硬约束（来自桌面端 `WebBackendSelector.tsx` 与 `/api/sessions` 的行为）：
 *   1. `backend` / `acpToolId` 只在**草稿期**可改：会话一旦拉起 runtime 就锁定，
 *      此时再改后端等于让已经跑着的进程换了主人。
 *   2. 从 ACP 切走（或切到 ACP）必须显式把 model / thinkingLevel 置 null，
 *      否则旧绑定会被带到新后端上。
 *   3. `/api/state` 的 `acpTools` 为空 = 这台电脑没开 ACP，**不展示** ACP 入口，
 *      而不是展示一个点了会 400 的选项。
 */

import type { AcpToolInfo, BackendKind } from "@core/types.ts";
import { normalizeBackend, type KnownBackend } from "./backend-capabilities.ts";

/** 一个可创建/可切换的后端选择。`acpToolId` 只在 backend === "acp" 时有值。 */
export interface BackendChoice {
  backend: KnownBackend;
  acpToolId: string | null;
}

export const DEFAULT_BACKEND_CHOICE: BackendChoice = { backend: "pi", acpToolId: null };

/** 弹层里的一行。`key` 稳定，供测试与「当前选中」判定用。 */
export interface BackendOption {
  key: string;
  choice: BackendChoice;
  label: string;
  hint: string;
}

const BACKEND_HINTS: Record<string, string> = {
  pi: "内置 pi agent，模型与思考档位都能切",
  dsh: "DSH 后端（目标 / 子代理 / 技能）",
};

function readToolList(acpTools: unknown): AcpToolInfo[] {
  if (!Array.isArray(acpTools)) return [];
  const out: AcpToolInfo[] = [];
  for (const item of acpTools) {
    const tool = item as { id?: unknown; name?: unknown } | null;
    const id = typeof tool?.id === "string" ? tool.id.trim() : "";
    const name = typeof tool?.name === "string" ? tool.name.trim() : "";
    // 没有 id 就没法引用，跳过；没有名字就退回 id —— 这里是「选择列表」，
    // 让用户看到一个可读标识总比少一行选项好
    if (id) out.push({ id, name: name || id });
  }
  return out;
}

export function choiceKey(choice: BackendChoice): string {
  const backend = normalizeBackend(choice.backend);
  if (backend !== "acp") return backend;
  const toolId = typeof choice.acpToolId === "string" ? choice.acpToolId.trim() : "";
  return toolId ? `acp:${toolId}` : "acp";
}

export function sameChoice(a: BackendChoice, b: BackendChoice): boolean {
  return choiceKey(a) === choiceKey(b);
}

/**
 * 可选的后端列表。
 *
 * `include` 默认 `["pi", "dsh"]`：生图会话在手机上只能看不能建（草稿期无法填提示词模板），
 * 所以默认不放进新建流程，但会话菜单里若需要辨认仍可用 `label` 系列函数。
 */
export function backendOptions(
  acpTools: unknown,
  include: readonly KnownBackend[] = ["pi", "dsh"],
): BackendOption[] {
  const options: BackendOption[] = [];
  for (const backend of include) {
    const kind = normalizeBackend(backend);
    if (kind === "acp") continue; // ACP 由工具列表展开，不单独出现
    options.push({
      key: kind,
      choice: { backend: kind, acpToolId: null },
      label: kind === "pi" ? "Pi" : kind === "dsh" ? "DSH" : kind === "imagegen" ? "生图" : kind,
      hint: BACKEND_HINTS[kind] ?? "",
    });
  }
  for (const tool of readToolList(acpTools)) {
    options.push({
      key: `acp:${tool.id}`,
      choice: { backend: "acp", acpToolId: tool.id },
      label: `ACP · ${tool.name}`,
      hint: "电脑上跑的外部 agent CLI",
    });
  }
  return options;
}

/** 当前选择在列表里的位置（-1 = 已不在列表中，比如工具被删了）。 */
export function indexOfChoice(options: readonly BackendOption[], choice: BackendChoice): number {
  const key = choiceKey(choice);
  return options.findIndex((option) => option.key === key);
}

/** 展示用的一行文字：`Pi` / `DSH` / `ACP · Gemini CLI`。 */
export function describeChoice(choice: BackendChoice, acpTools?: unknown): string {
  const backend = normalizeBackend(choice.backend);
  if (backend !== "acp") {
    return backend === "pi" ? "Pi" : backend === "dsh" ? "DSH" : backend === "imagegen" ? "生图" : backend;
  }
  const toolId = typeof choice.acpToolId === "string" ? choice.acpToolId.trim() : "";
  const tool = readToolList(acpTools).find((item) => item.id === toolId);
  return tool ? `ACP · ${tool.name}` : "ACP";
}

/**
 * `/api/sessions/:id/update` 的补丁。
 *
 * `previous` 用来判断要不要清 model / thinkingLevel：从 ACP 切走、或者切到 ACP，
 * 旧绑定都不再有意义（桌面端 WebChatApp 同样显式置 null）。
 */
export function backendPatch(
  choice: BackendChoice,
  previous?: BackendChoice,
): Record<string, unknown> {
  const next = normalizeBackend(choice.backend);
  const toolId = typeof choice.acpToolId === "string" ? choice.acpToolId.trim() : "";
  const patch: Record<string, unknown> = {
    backend: next,
    acpToolId: next === "acp" && toolId ? toolId : null,
  };
  const before = previous ? normalizeBackend(previous.backend) : undefined;
  if (before !== next || next === "acp") {
    patch.model = null;
    patch.thinkingLevel = null;
  }
  return patch;
}

/** 新建会话的请求体：pi 是默认值，不必显式带字段（老桌面端不认识会报错）。 */
export function createSessionInput(
  projectId: string,
  choice: BackendChoice = DEFAULT_BACKEND_CHOICE,
): { projectId: string; backend?: BackendKind; acpToolId?: string } {
  const backend = normalizeBackend(choice.backend);
  if (backend === "pi") return { projectId };
  const input: { projectId: string; backend?: BackendKind; acpToolId?: string } = {
    projectId,
    backend: backend as BackendKind,
  };
  const toolId = typeof choice.acpToolId === "string" ? choice.acpToolId.trim() : "";
  if (backend === "acp" && toolId) input.acpToolId = toolId;
  return input;
}

/** 选中的是 ACP 但没绑定工具 —— 服务端会 400 sessionToolRequired，发出去之前先拦下。 */
export function missingAcpTool(choice: BackendChoice): boolean {
  const toolId = typeof choice.acpToolId === "string" ? choice.acpToolId.trim() : "";
  return normalizeBackend(choice.backend) === "acp" && toolId === "";
}

/**
 * 后端能不能改：只有还没有 runtime 的草稿才能改。
 *
 * 有 runtime 时不光服务端不认，客户端缓存里的模型、思考档位、工具清单也全是旧的，
 * 放开只会换来一次「改完没生效」。
 */
export function backendSwitchAllowed(runtimes: readonly { sessionId?: string }[], sessionId: string): boolean {
  return !runtimes.some((runtime) => runtime.sessionId === sessionId);
}

export function backendLockedHint(choice: BackendChoice, acpTools?: unknown): string {
  return `后端已锁定：${describeChoice(choice, acpTools)}（会话已经开始过，后端只能在新建时选）`;
}
