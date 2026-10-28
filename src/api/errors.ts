/** 统一错误模型：把 HTTP / 网络 / 协议异常收敛成上层能分支的少数几类。 */

export type PiDeckErrorKind = "auth" | "offline" | "runtime" | "http" | "protocol";

export interface PiDeckErrorInfo {
  kind: PiDeckErrorKind;
  message: string;
  status?: number;
  /** 服务端返回的 i18n 错误码，例如 webError.unauthorized */
  code?: string;
  /** 服务端附带的结构化参数（i18nParams 等） */
  details?: unknown;
  cause?: unknown;
}

export class PiDeckError extends Error {
  readonly kind: PiDeckErrorKind;
  readonly status?: number;
  readonly code?: string;
  readonly details?: unknown;

  constructor(info: PiDeckErrorInfo) {
    super(info.message);
    this.name = "PiDeckError";
    this.kind = info.kind;
    this.status = info.status;
    this.code = info.code;
    this.details = info.details;
    if (info.cause !== undefined) (this as { cause?: unknown }).cause = info.cause;
  }
}

/** 令牌缺失/过期/轮换：应引导用户重新配对。 */
export class PiDeckAuthError extends PiDeckError {
  constructor(info: Omit<PiDeckErrorInfo, "kind">) {
    super({ ...info, kind: "auth" });
    this.name = "PiDeckAuthError";
  }
}

/** 连不上：桌面端没开 Web 服务、地址不对、隧道断了、被防火墙拦。 */
export class PiDeckOfflineError extends PiDeckError {
  constructor(info: Omit<PiDeckErrorInfo, "kind">) {
    super({ ...info, kind: "offline" });
    this.name = "PiDeckOfflineError";
  }
}

/** 409：运行时已被重启/替换（runtimeGeneration 过期），需要重新拉 /api/state。 */
export class PiDeckRuntimeError extends PiDeckError {
  constructor(info: Omit<PiDeckErrorInfo, "kind">) {
    super({ ...info, kind: "runtime" });
    this.name = "PiDeckRuntimeError";
  }
}

export class PiDeckHttpError extends PiDeckError {
  constructor(info: Omit<PiDeckErrorInfo, "kind">) {
    super({ ...info, kind: "http" });
    this.name = "PiDeckHttpError";
  }
}

export function isAuthError(error: unknown): error is PiDeckAuthError {
  return error instanceof PiDeckError && error.kind === "auth";
}

export function isOfflineError(error: unknown): error is PiDeckOfflineError {
  return error instanceof PiDeckError && error.kind === "offline";
}

export function isRuntimeStaleError(error: unknown): error is PiDeckRuntimeError {
  return error instanceof PiDeckError && error.kind === "runtime";
}

export function describeError(error: unknown): string {
  if (error instanceof PiDeckError) return error.message;
  if (error instanceof Error) return error.message;
  return String(error);
}
