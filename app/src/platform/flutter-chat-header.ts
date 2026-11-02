/**
 * 顶栏 + 输入框附属行的视图模型（Flutter 聊天页用）。
 *
 * 和聊天页一样走「视图迁移」：这里只把 WebView 已经算好的东西整理成一份
 * 可以直接画的 JSON，**不做任何协议推断**。规则仍然在各自的模型里：
 *
 *   - 标题 / 副标题：`app/src/ui/header-model.ts` 的 `buildHeaderView()`
 *   - 模型 / 思考档位：`app/src/ui/model-picker.ts`（`thinkingLabel` / `modelSubtitle`）
 *   - 安全级别：`app/src/ui/security-level-model.ts`
 *   - 发送模式：`app/src/ui/send-mode.ts`
 *   - 上下文用量：`app/src/ui/context-usage.ts`
 *
 * Dart 侧（`flutter_module/lib/chat/chat_models.dart` 的 `ChatHeaderView`）只认这里
 * 定义的字段名，不认识上面那些模块。
 */

import type { ContextUsage } from "../ui/context-usage.ts";
import type { ModelOption, ModelPickerSection } from "../ui/model-picker.ts";
import type { SecurityChoice } from "../ui/security-level-model.ts";
import type { SendMode } from "../ui/send-mode.ts";

/**
 * 顶栏上的一个 chip（模型 / 思考 / 安全 / 计划 / DSH）。
 *
 * `key` 同时是点击时回传给 WebView 的动作名（`chip` 动作的 `key` 字段），
 * 所以新增 chip 必须同时在 `dispatchFlutterChatAction` 里加分支。
 */
export interface ChatChip {
  key: "model" | "thinking" | "security" | "plan" | "dsh" | "history";
  label: string;
  /** 灰色：还没选（模型占位、思考未设、安全未设） */
  muted?: boolean;
  /** 高亮：计划模式开着 */
  active?: boolean;
}

export interface ChatTodoItem {
  text: string;
  /** `pending` / `in_progress` / `completed` */
  status: string;
}

export interface ChatTodoSummary {
  open: number;
  done: number;
  total: number;
  /** 正在做的那一条（有就显示） */
  current?: string;
  /** 展开后逐条显示；顺序就是 agent 给的顺序 */
  items: ChatTodoItem[];
}

export interface ChatSubagentSummary {
  active: number;
  total: number;
}

/** 上下文用量：只在 `contextWindow > 0` 时才有值（与 Web 版一致）。 */
export interface ChatContextChip {
  percent: number;
  overflow: boolean;
  compacting: boolean;
  /** 「12.3K / 128K」这种短标签 */
  label: string;
}

export interface ChatHeaderView {
  title: string;
  meta: string;
  running: boolean;
  /** 顺序即显示顺序 */
  chips: ChatChip[];
  context: ChatContextChip | null;
  /** 运行中才有「排队 / 插话」开关 */
  sendMode: SendMode;
  showSendMode: boolean;
  /** ACP 会话不能插话（`backendCapabilities().steer === false`） */
  canSteer: boolean;
  /** 提示词模板数量；0 表示没导入，按钮不显示 */
  promptCount: number;
  todo: ChatTodoSummary | null;
  subagents: ChatSubagentSummary | null;
  /** 有没有历史消息（`buildChatHeaderView` 据此决定「历史」chip 出不出现） */
  hasHistory: boolean;
}

export interface ChatHeaderInput {
  title: string;
  meta: string;
  running: boolean;
  /** 后端能力：ACP 会话的模型 / 思考 / 计划都是假的，入口不显示 */
  caps: { model: boolean; thinking: boolean; plan: boolean; steer: boolean; dsh: boolean };
  model: { label: string; placeholder: boolean };
  thinking: { level: string; label: string };
  security: { label: string; muted: boolean } | null;
  dsh: boolean;
  planOn: boolean;
  context: ContextUsage | null;
  sendMode: SendMode;
  promptCount: number;
  todo: ChatTodoSummary | null;
  subagents: ChatSubagentSummary | null;
  /** 有已加载的历史消息时给一个「历史」入口（跳转到某条消息） */
  hasHistory: boolean;
}

/** 上下文短标签：「12.3K / 128K」，>1M 用 M。 */
export function contextChipLabel(usage: Pick<ContextUsage, "contextTokens" | "contextWindow">): string {
  return `${shortTokens(usage.contextTokens)} / ${shortTokens(usage.contextWindow)}`;
}

/** 把 token 数缩成「12.3K」这种短标签（上下文 chip 与用量面板共用）。 */
export function shortTokens(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return "0";
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(value >= 10_000_000 ? 0 : 1)}M`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(value >= 100_000 ? 0 : 1)}K`;
  return `${Math.round(value)}`;
}

/**
 * 组装顶栏。
 *
 * chip 的顺序是「模型 → 思考 → 安全 → 计划 → DSH」，与 Web 版顶栏从左到右一致；
 * 不满足能力的整块不出现（ACP 会话只剩标题与状态）。
 */
export function buildChatHeaderView(input: ChatHeaderInput): ChatHeaderView {
  const chips: ChatChip[] = [];
  if (input.caps.model) {
    chips.push({ key: "model", label: input.model.label, ...(input.model.placeholder ? { muted: true } : {}) });
  }
  if (input.caps.thinking) {
    chips.push({
      key: "thinking",
      label: input.thinking.level ? `思考 ${input.thinking.label}` : "思考",
      ...(input.thinking.level ? {} : { muted: true }),
    });
  }
  if (input.security) {
    chips.push({ key: "security", label: input.security.label, ...(input.security.muted ? { muted: true } : {}) });
  }
  if (input.caps.plan) {
    chips.push({ key: "plan", label: "计划", ...(input.planOn ? { active: true } : {}) });
  }
  if (input.caps.dsh && input.dsh) {
    chips.push({ key: "dsh", label: "DSH" });
  }
  // 「历史」放最后：它是导航而不是状态，排在前面的 chip 都在说「当前用的是什么」
  if (input.hasHistory) chips.push({ key: "history", label: "历史" });

  return {
    title: input.title,
    meta: input.meta,
    running: input.running,
    chips,
    context: input.context
      ? {
          percent: Math.round(input.context.contextPercent),
          overflow: input.context.contextOverflow,
          compacting: input.context.isCompacting,
          label: contextChipLabel(input.context),
        }
      : null,
    sendMode: input.sendMode,
    showSendMode: input.running && input.caps.steer,
    canSteer: input.caps.steer,
    promptCount: input.promptCount,
    todo: input.todo,
    subagents: input.subagents,
    hasHistory: input.hasHistory,
  };
}

/* ------------------------------ 选择面板 ------------------------------ */

/**
 * 选择面板的一项。`id` 由各面板自己决定含义：
 * 模型是 `provider\u0000modelId`、思考是档位名、安全是等级 id 或 `""`（跟随全局）。
 */
export interface ChatPickerItem {
  id: string;
  label: string;
  description?: string;
  selected?: boolean;
  danger?: boolean;
  /** 分组标题（模型面板按 provider 分组） */
  section?: string;
  /**
   * 选中后要插到输入框里的文本。
   *
   * 有这一项表示插入是**纯文本动作**：由 Dart 直接写进它自己的输入框
   * （面板开着时输入框的文字以 Dart 为准，WebView 只在本页发送时收到全文），
   * 然后发一个 `closePicker` 就算完，不用再回 WebView 走一圈。
   */
  insert?: string;
}

export interface ChatPickerView {
  /** `model` / `thinking` / `security` / `prompt` / `slash` / `mention` / `context` */
  kind: string;
  title: string;
  subtitle?: string;
  searchable?: boolean;
  empty?: string;
  /**
   * 面板自己的「当前位置」，Dart 不认识内容、只在搜索时原样带回。
   *
   * @ 提及往下钻了一层之后，用户在面板里打字搜索，`pickerQuery` 必须
   * 还知道自己在哪一层 —— 否则一搜就跳回项目根目录。
   */
  context?: string;
  items: ChatPickerItem[];
}

/** 模型面板：按 provider 分组，选中的那个打勾，副标题带上下文窗口。 */
export function buildModelPicker(sections: readonly ModelPickerSection[], activeKey: string): ChatPickerView {
  const items: ChatPickerItem[] = [];
  for (const section of sections) {
    for (const model of section.models) {
      items.push({
        id: model.key,
        label: model.name,
        description: modelSubtitleOf(model),
        section: section.provider,
        ...(model.key === activeKey ? { selected: true } : {}),
      });
    }
  }
  return {
    kind: "model",
    title: "选择模型",
    searchable: true,
    empty: "这台机器上没有可用模型",
    items,
  };
}

function modelSubtitleOf(model: ModelOption): string {
  if (!model.contextWindow) return model.provider;
  const k = Math.round(model.contextWindow / 1000);
  const window = k >= 1000 ? `${Math.round(k / 1000)}M 上下文` : `${k}K 上下文`;
  return `${model.provider} · ${window}`;
}

/** 思考档位面板。`levels` 已经按 `thinkingLevelMap` 过滤过（不支持的档位不能出现）。 */
export function buildThinkingPicker(
  levels: readonly string[],
  active: string,
  labelOf: (level: string) => string,
  hintOf: (level: string) => string,
): ChatPickerView {
  return {
    kind: "thinking",
    title: "思考档位",
    subtitle: "档位越高越慢越贵，但复杂任务更稳",
    empty: "这个模型不支持调整思考档位",
    items: levels.map((level) => ({
      id: level,
      label: labelOf(level),
      description: hintOf(level),
      ...(level === active ? { selected: true } : {}),
    })),
  };
}

/** 安全级别面板：第一项是「跟随全局默认」（`id` 为空串），其余是各级别。 */
export function buildSecurityPicker(choices: readonly SecurityChoice[]): ChatPickerView {
  return {
    kind: "security",
    title: "安全级别",
    subtitle: "只对 pi 会话生效；ACP / DSH 由各自的后端管",
    empty: "这台机器没开启安全级别切换",
    items: choices.map((choice) => ({
      id: choice.id ?? "",
      label: choice.label,
      description: choice.description,
      ...(choice.active ? { selected: true } : {}),
    })),
  };
}
