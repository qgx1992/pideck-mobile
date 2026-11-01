/**
 * 服务端「拒收本条提问」的翻译层。
 *
 * 桌面端只回结构化的 `i18nKey` + `i18nParams`（`SendPromptResult`，见桌面
 * `src/shared/types/session.ts:515`），文案由渲染层本地化。手机端以前完全不看返回值，
 * 于是一次拒收的表现是：**用户的气泡上屏了、什么都没发生、也没有任何提示**。
 * ACP 会话尤其容易撞上——回合中发消息（turnBusy）、带图（imagesNotSupported）、
 * 计划模式注入（hostInstructionsUnsupported）都会被拒。
 *
 * 文案对齐桌面端 `rendererCopy.zh-CN.ts:249-263`，只做两处手机端改写：
 *   - 「设置 → ACP 工具」改成「电脑端的 设置 → ACP 工具」，因为手机上根本没有那个设置页；
 *   - 去掉「请升级到最新版本后重试」这类只在桌面端成立的指引。
 */

export interface PromptRejection {
  accepted: false;
  error?: string;
  /** rejected = 服务端确定没发出去；unknown = 可能已经发出去（别回滚气泡） */
  delivery?: string;
  i18nKey?: string;
  i18nParams?: Record<string, unknown>;
  debugDetails?: string;
}

type Params = Record<string, unknown>;

/** 缺参数时的兜底称呼：宁可说「该 CLI」，也不要漏出 `{tool}` 花括号或裸 id。 */
const TOOL_FALLBACK = "该 CLI";

const TEMPLATES: Record<string, (params: Params) => string> = {
  "acp.processNotRunning": (params) => `${toolOf(params)} 进程未在运行，请重试`,
  "acp.toolNotInstalled": (params) => {
    const command = strOf(params.command);
    return `「${toolOf(params)}」启动失败：电脑上这个 CLI 没装或不在 PATH 里${command ? `（${command}）` : ""}，装好后再在电脑端检测一次`;
  },
  "acp.toolMissing": () => "这个会话绑定的 ACP 工具已被删除或停用，请在电脑端 设置 → ACP 工具 里重新登记后重试",
  "acp.startupTimeout": (params) => `「${toolOf(params)}」启动超时（首次下载或冷启动会比较慢），再发一次试试`,
  "acp.turnTimeout": (params) => {
    const minutes = strOf(params.minutes);
    return `「${toolOf(params)}」回合超时${minutes ? `（连续 ${minutes} 分钟没有任何输出）` : ""}，可以在电脑端把超时保护调大`;
  },
  "acp.hostInstructionsUnsupported": () => "ACP 后端不支持 PiDeck 的内部指令（计划模式、安全级别这类）",
  "acp.turnBusy": (params) => `${toolOf(params)} 还在回复中，等这一轮结束或先点停止再发`,
  "acp.abortForceStopped": (params) => `「${toolOf(params)}」长时间没响应停止请求，已强制结束本轮，重新发送即可继续`,
  "acp.stallForceStopped": (params) => `「${toolOf(params)}」长时间没有任何输出，已自动结束本轮，检查该 CLI 的模型/网络后再试`,
  "acp.emptyTurn": (params) => `「${toolOf(params)}」这一轮结束了但没有任何输出，重发一次试试`,
  "acp.imagesNotSupported": (params) => `${toolOf(params)} 不接受图片输入，移除图片后再发`,
  "acp.authRequired": (params) => `「${toolOf(params)}」未登录或凭据已失效：先在电脑的终端里运行这个 CLI 完成登录，再回来重试`,
};

function toolOf(params: Params): string {
  const tool = strOf(params.tool);
  return tool || TOOL_FALLBACK;
}

function strOf(value: unknown): string {
  if (typeof value === "string") return value.trim();
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return "";
}

/**
 * 条目是不是「拒收」。只看 `accepted === false`：
 * 老桌面端（0.9.x 早期）`/prompt` 的 result 可能是裸空对象，那种不能当拒收。
 */
export function isPromptRejection(result: unknown): result is PromptRejection {
  if (!result || typeof result !== "object") return false;
  return (result as { accepted?: unknown }).accepted === false;
}

/**
 * 拒绝文案。命中 i18nKey 就用本地化模板；没命中退到服务端原文 `error`；
 * 两者都没有给一句能 actionable 的兜底。
 */
export function rejectionMessage(rejection: PromptRejection): string {
  const key = typeof rejection.i18nKey === "string" ? rejection.i18nKey.trim() : "";
  const template = key ? TEMPLATES[key] : undefined;
  if (template) {
    const params = rejection.i18nParams && typeof rejection.i18nParams === "object" ? rejection.i18nParams : {};
    return template(params as Params);
  }
  const raw = typeof rejection.error === "string" ? rejection.error.trim() : "";
  if (raw) return `发送被拒绝：${raw}`;
  return "发送被拒绝：桌面端没有说明原因，可以重发一次看看";
}

/**
 * 拒收后要不要把乐观上屏的气泡撤掉。
 *
 * `delivery === "unknown"` 表示服务端自己也不确定发没发出去（比如回合中途超时），
 * 这时撤掉气泡会跟真实历史对不上，宁可按「已发出」处理。
 */
export function shouldRollbackRejection(rejection: PromptRejection): boolean {
  return rejection.delivery !== "unknown";
}
