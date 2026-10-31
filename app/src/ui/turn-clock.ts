/**
 * 回合计时：从提问发出到本轮结束。
 *
 * 以前输入框上方只有一行静态的「运行中…」——长回答里既看不出进度，也说不出这轮到底跑了多久。
 * 现在换成计时：
 *   - 运行中：每 100ms 刷一次，`12.4s` / `1:05`
 *   - 结束后：定格成「用时 12.4s · 15:32 完成」，失败/停止换掉最后一个词
 *
 * 口径是**整轮墙钟**：包含排队、工具执行与子代理，比 perf 里的 totalMs（单条模型请求）更接近
 * 用户体感。首 token / TPS 那些仍然在右侧胶囊里，两者不冲突。
 */

export interface TurnSpan {
  /** 这一轮开始的时间戳（用户发出提问 / 首次看到运行） */
  startedAt: number;
  /** 结束时间戳；null = 还在跑 */
  finishedAt: number | null;
}

/** 运行中显示的耗时：不到 1 分钟用一位小数的秒，之后用 m:ss。 */
export function formatTurnElapsed(ms: number): string {
  const safe = Number.isFinite(ms) && ms > 0 ? ms : 0;
  const totalSeconds = safe / 1_000;
  if (totalSeconds < 60) return `${totalSeconds.toFixed(1)}s`;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = Math.floor(totalSeconds % 60);
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

/** 完成时刻：今天只显示 HH:MM，跨天补上 M/D（避免「昨天的 15:32」看不出是哪天）。 */
export function formatFinishClock(at: number, now: number = Date.now()): string {
  if (!Number.isFinite(at) || at <= 0) return "";
  const date = new Date(at);
  const pad = (value: number): string => String(value).padStart(2, "0");
  const clock = `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  if (date.toDateString() === new Date(now).toDateString()) return clock;
  return `${date.getMonth() + 1}/${date.getDate()} ${clock}`;
}

/**
 * 状态行文案。
 *
 * 运行中只有耗时（前面有转圈，不必再写「运行中」占宽度）；
 * 结束后是「用时 + 完成时刻 + 结果」。
 */
export function turnStatusText(turn: TurnSpan | null, status: string, now: number = Date.now()): string {
  if (status === "running") {
    return turn ? formatTurnElapsed(now - turn.startedAt) : "0.0s";
  }
  if (!turn) {
    return status === "error" ? "本轮失败" : status === "aborted" ? "已停止" : "";
  }
  const end = turn.finishedAt ?? now;
  const elapsed = formatTurnElapsed(end - turn.startedAt);
  const clock = formatFinishClock(end, now);
  const head = clock ? `用时 ${elapsed} · ${clock}` : `用时 ${elapsed}`;
  if (status === "error") return `${head} · 本轮失败`;
  if (status === "aborted") return `${head} · 已停止`;
  return `${head} 完成`;
}

/** 细节提示（长按/悬停）。 */
export function turnStatusTitle(status: string): string {
  if (status === "running") return "本轮已运行时间：从提问发出算起，含工具执行与子代理";
  return "本轮总用时与完成时刻；从提问发出算起，含工具执行与子代理";
}

/**
 * 最后一条用户提问的时间戳。
 *
 * 打开一个**正在跑**的会话时，拿它当回合起点，比「打开的那一瞬间」准得多
 * （否则跑了两分钟的那轮会从 0.0s 开始数）。取不到就返回 undefined。
 */
export function lastUserTimestamp(messages: readonly { role?: unknown; timestamp?: unknown }[]): number | undefined {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if (!message) continue;
    const role = typeof message.role === "string" ? message.role.toLowerCase() : "";
    if (!role.includes("user")) continue;
    const at = typeof message.timestamp === "number" ? message.timestamp : undefined;
    if (at !== undefined && Number.isFinite(at) && at > 0) return at;
  }
  return undefined;
}
