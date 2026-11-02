/**
 * 通用底部弹层的快照形状（侧栏与聊天页共用）。
 *
 * 为什么要有这一层：侧栏的「新建会话 / 切换后端」和聊天页的「DSH 工具」原本都走
 * `openSheet()`——那是 WebView 自己的 DOM 弹层，被 Flutter 页盖着根本看不见，
 * 所以只能 `leaveFlutterThen()` 闪回网页版再弹。
 *
 * 但它们的**内容**其实是数据：一个标题、一段说明、若干分组里的若干行。
 * 于是这里把它抽成可 JSON 化的形状：WebView 侧算「有哪些行、每行点了发什么 id」，
 * Flutter 侧只管画并回传 `sheetPick { id }`。执行逻辑仍然只有 `main.ts` 一份。
 *
 * 刻意保持无状态：选中态不放在 Dart（否则「选后端」这种两步操作要跨端同步两处状态），
 * 而是由 WebView 在每次 `sheetPick` 后重算整份视图再推一次。
 */

/** 弹层里的一行。 */
export interface SheetItemView {
  /** 点击后回传给 WebView 的 id（`sheetPick`） */
  id: string;
  label: string;
  /** 第二行小字 */
  description?: string;
  /** 分组小标题：连续相同者归一组，空串表示不分组 */
  group?: string;
  danger?: boolean;
  disabled?: boolean;
  /** 当前选中的那一项（右侧打勾） */
  selected?: boolean;
  /** 可点的行画 chevron；纯信息行不给（点了也没反应会更糟） */
  actionable?: boolean;
}

/** 一份完整的弹层视图。 */
export interface SheetView {
  /** 谁发的（`newSession` / `backend` / `dsh`…），只用于日志与调试 */
  kind: string;
  title: string;
  /** 标题下面那段说明 */
  note?: string;
  items: SheetItemView[];
}

/** 后端选择列表 → 弹层行（新建会话与切换后端共用）。 */
export function backendSheetItems(
  options: readonly { key: string; label: string; hint: string }[],
  selectedKey: string,
  opts?: { prefix?: string; disabled?: boolean; group?: string; actionable?: boolean },
): SheetItemView[] {
  const prefix = opts?.prefix ?? "backend:";
  return options.map((option) => ({
    id: `${prefix}${option.key}`,
    label: option.label,
    ...(option.hint ? { description: option.hint } : {}),
    ...(opts?.group ? { group: opts?.group } : {}),
    selected: option.key === selectedKey,
    ...(opts?.disabled ? { disabled: true } : {}),
    actionable: opts?.actionable ?? true,
  }));
}
