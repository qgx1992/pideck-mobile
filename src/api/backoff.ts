/** 指数退避 + 抖动：SSE 断线重连的唯一节流策略。 */

export interface BackoffOptions {
  baseMs?: number;
  maxMs?: number;
  factor?: number;
  jitter?: number;
  random?: () => number;
}

export class Backoff {
  private attempt = 0;
  private readonly baseMs: number;
  private readonly maxMs: number;
  private readonly factor: number;
  private readonly jitter: number;
  private readonly random: () => number;

  constructor(options: BackoffOptions = {}) {
    this.baseMs = options.baseMs ?? 1_000;
    this.maxMs = options.maxMs ?? 15_000;
    this.factor = options.factor ?? 2;
    this.jitter = options.jitter ?? 0.2;
    this.random = options.random ?? Math.random;
  }

  /** 第 n 次重试前该等多久 */
  nextDelay(): number {
    const raw = Math.min(this.maxMs, this.baseMs * this.factor ** this.attempt);
    this.attempt += 1;
    const spread = raw * this.jitter;
    const jittered = raw - spread + this.random() * spread * 2;
    return Math.max(0, Math.round(jittered));
  }

  reset(): void {
    this.attempt = 0;
  }

  get attempts(): number {
    return this.attempt;
  }
}

export function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal?.aborted) return resolve();
    const timer = setTimeout(finish, ms);
    function finish() {
      clearTimeout(timer);
      signal?.removeEventListener("abort", finish);
      resolve();
    }
    signal?.addEventListener("abort", finish, { once: true });
  });
}
