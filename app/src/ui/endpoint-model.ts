/**
 * 多端点（一机连多台 PiDeck）的身份与上限规则。
 *
 * 为什么需要「端点 id」：所有按会话/项目缓存的 UI 状态（草稿、图片、学习到的
 * 模型偏好、todo 折叠、通知去重、额度缓存）都得能区分是哪台机器上的东西，
 * 否则两台机器同名 provider / 同 id 会话会互相串台。
 *
 * id 用 origin（协议 + 主机 + 端口），因为 token 可能变、路径可能带尾斜杠，
 * 但同一台机器的 origin 是稳定的。已知局限：同一台电脑用局域网 IP 和
 * Tailscale 域名访问会算成两台 —— PiDeck 的 /api/health 没返回设备 id，
 * 客户端无法自行判定，只能等上游补字段。
 */

/** 同时保持实时连接的上限：每条端点常驻一条 /api/events，再多就明显耗电。 */
export const MAX_LIVE_ENDPOINTS = 5;

export interface EndpointLike {
  baseUrl: string;
  label?: string;
}

/** 归一化成稳定 id（origin，小写）。解析不了就退回去空白的小写串。 */
export function endpointIdOf(baseUrl: string): string {
  const raw = baseUrl.trim();
  if (!raw) return "";
  try {
    const url = new URL(raw);
    return `${url.protocol}//${url.host}`.toLowerCase();
  } catch {
    return raw.replace(/\/+$/, "").toLowerCase();
  }
}

/** 显示名：用户备注 > 主机名（默认端口省略）> id。 */
export function endpointLabelOf(endpoint: EndpointLike): string {
  const label = typeof endpoint.label === "string" ? endpoint.label.trim() : "";
  if (label) return label;
  try {
    const url = new URL(endpoint.baseUrl);
    const port = url.port ? `:${url.port}` : "";
    return `${url.hostname}${port}`;
  } catch {
    return endpointIdOf(endpoint.baseUrl) || "未命名设备";
  }
}

/**
 * 去重：同一 origin 只留一条（先到先得，但后来的非空备注名会补上）。
 */
export function dedupeEndpoints<T extends EndpointLike>(list: readonly T[]): T[] {
  const seen = new Map<string, T>();
  for (const item of list) {
    const id = endpointIdOf(item.baseUrl);
    if (!id) continue;
    const existing = seen.get(id);
    if (!existing) {
      seen.set(id, item);
      continue;
    }
    const existingLabel = typeof existing.label === "string" ? existing.label.trim() : "";
    const nextLabel = typeof item.label === "string" ? item.label.trim() : "";
    if (!existingLabel && nextLabel) seen.set(id, { ...item });
  }
  return [...seen.values()];
}

/** 超过上限时要给用户的提示（空串表示不用提示）。 */
export function describeEndpointCap(count: number): string {
  if (count <= MAX_LIVE_ENDPOINTS) return "";
  return `同时保持 ${count} 台实时连接会明显耗电，建议只留常用的 ${MAX_LIVE_ENDPOINTS} 台`;
}

/** 端点选择器的排序：正在跑任务的排前面，其余按备注名。 */
export function sortEndpointsForPicker<T extends { label: string; running: number }>(list: readonly T[]): T[] {
  return [...list].sort((left, right) => {
    if (left.running !== right.running) return right.running - left.running;
    return left.label.localeCompare(right.label, "zh-CN");
  });
}
