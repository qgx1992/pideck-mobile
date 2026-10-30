/**
 * 设置页（全屏覆盖层）。
 *
 * 数据由 main.ts 组装后 `update()` 进来，这里只负责画。
 * 之所以不做成底部 sheet：设置项多（外观/通知/设备/pi 概况/关于），
 * 抽屉里滚动体验太差，而且用户是「进去看一遍再出来」的模式。
 *
 * 一个诚实的前提：桌面端的 pi 配置管理走 Electron IPC，Web 服务没有写接口。
 * 所以这里只有「本地设置 + 只读概况 + 有接口的开关」，写配置仍然要回桌面端。
 */

import { h, clear } from "./dom.ts";
import { createChevron, setChevronExpanded } from "./chevron.ts";
import { DESKTOP_ONLY_NOTE, maskToken } from "./settings-model.ts";
import type { DshPluginRow, ExtensionRow, ModelRow, SkillSection } from "./settings-model.ts";

export interface SettingsDeviceRow {
  id: string;
  label: string;
  url: string;
  active: boolean;
  running: number;
  pending: number;
  enabled: boolean;
}

export interface SettingsData {
  theme: { mode: string; options: ReadonlyArray<{ value: string; label: string }> };
  notify: { enabled: boolean };
  connection: { serviceUrl: string; token: string; status: string; version: string };
  devices: SettingsDeviceRow[];
  models: ModelRow[];
  skills: SkillSection[];
  extensions: ExtensionRow[];
  dshPlugins: DshPluginRow[];
  /** 概况类数据的加载状态 */
  loading: boolean;
  /** 加载失败时的人话说明（比如旧版 PiDeck 没有 /api/skills） */
  loadError?: string;
}

export interface SettingsHandlers {
  onClose: () => void;
  onTheme: (mode: string) => void;
  onToggleNotify: (enabled: boolean) => void;
  onDeviceActivate: (id: string) => void;
  onDeviceMenu: (id: string) => void;
  onAddDevice: () => void;
  onDisconnect: () => void;
  onToggleSkill: (section: SkillSection, skillName: string, enabled: boolean) => void;
  onToggleExtension: (extension: ExtensionRow, enabled: boolean) => void;
  onOpenUsage: () => void;
  onRefresh: () => void;
}

export interface SettingsPage {
  el: HTMLElement;
  update: (data: SettingsData) => void;
  destroy: () => void;
}

function section(title: string, rows: HTMLElement[]): HTMLElement {
  return h("section", { class: "settings-section" }, [
    h("h2", { class: "settings-section-title", text: title }),
    ...rows,
  ]);
}

function row(label: string, value: string, options: { sub?: string; onClick?: () => void } = {}): HTMLElement {
  const left = h("span", { class: "settings-row-text" }, [
    h("span", { class: "settings-row-label", text: label }),
    ...(options.sub ? [h("span", { class: "settings-row-sub", text: options.sub })] : []),
  ]);
  const el = options.onClick
    ? h("button", { class: "settings-row settings-row--action", onclick: options.onClick }, [
        left,
        h("span", { class: "settings-row-value", text: value }),
      ])
    : h("div", { class: "settings-row" }, [left, h("span", { class: "settings-row-value", text: value })]);
  return el;
}

function switchRow(
  label: string,
  checked: boolean,
  onChange: (next: boolean) => void,
  options: { sub?: string; disabled?: boolean } = {},
): HTMLElement {
  const knob = h("span", { class: `settings-switch${checked ? " is-on" : ""}`, role: "switch" });
  knob.setAttribute("aria-checked", checked ? "true" : "false");
  knob.setAttribute("aria-label", label);
  const button = h(
    "button",
    {
      class: "settings-row settings-row--switch",
      disabled: options.disabled === true,
      onclick: () => {
        if (options.disabled) return;
        onChange(!checked);
      },
    },
    [
      h("span", { class: "settings-row-text" }, [
        h("span", { class: "settings-row-label", text: label }),
        ...(options.sub ? [h("span", { class: "settings-row-sub", text: options.sub })] : []),
      ]),
      knob,
    ],
  );
  return button;
}

/** 可折叠分组（技能按来源折，避免一屏几十行）。 */
function collapsible(title: string, count: number, build: () => HTMLElement[]): HTMLElement {
  const body = h("div", { class: "settings-fold-body" });
  body.style.display = "none";
  const chevron = createChevron(false, 12);
  const head = h("button", { class: "settings-fold-head" }, [
    chevron,
    h("span", { class: "settings-fold-title", text: title }),
    h("span", { class: "settings-fold-count", text: String(count) }),
  ]);
  let open = false;
  head.addEventListener("click", () => {
    open = !open;
    setChevronExpanded(chevron, open);
    body.style.display = open ? "" : "none";
    if (open && body.childElementCount === 0) body.append(...build());
  });
  return h("div", { class: "settings-fold" }, [head, body]);
}

export function createSettingsPage(handlers: SettingsHandlers): SettingsPage {
  const body = h("div", { class: "settings-body" });
  const title = h("h1", { class: "settings-title", text: "设置" });

  const back = h("button", {
    class: "settings-back",
    text: "←",
    title: "返回",
    "aria-label": "返回",
    onclick: () => handlers.onClose(),
  });

  const refresh = h("button", {
    class: "settings-refresh",
    text: "刷新",
    title: "重新读取模型 / 技能 / 扩展",
    onclick: () => handlers.onRefresh(),
  });

  const el = h("div", { class: "settings-page" }, [
    h("header", { class: "settings-head" }, [back, title, refresh]),
    body,
  ]);

  const update = (data: SettingsData): void => {
    clear(body);

    // ── 外观 ──
    const themeRow = h("div", { class: "settings-choice" });
    for (const option of data.theme.options) {
      themeRow.append(
        h("button", {
          class: `settings-choice-item${option.value === data.theme.mode ? " is-active" : ""}`,
          text: option.label,
          onclick: () => handlers.onTheme(option.value),
        }),
      );
    }
    body.append(
      section("外观", [
        h("div", { class: "settings-row settings-row--stack" }, [
          h("span", { class: "settings-row-label", text: "主题" }),
          themeRow,
        ]),
      ]),
    );

    // ── 通知 ──
    body.append(
      section("通知", [
        switchRow("任务完成 / AI 提问提醒", data.notify.enabled, (next) => handlers.onToggleNotify(next), {
          sub: "锁屏后由原生前台服务负责；多设备时只覆盖一台",
        }),
      ]),
    );

    // ── 连接与设备 ──
    const deviceRows: HTMLElement[] = data.devices.map((device) => {
      const badges = [
        device.active ? "当前" : "",
        device.running > 0 ? `${device.running} 运行中` : "",
        device.pending > 0 ? `${device.pending} 待答` : "",
        device.enabled ? "" : "未参与同时连接",
      ]
        .filter(Boolean)
        .join(" · ");
      const left = h("span", { class: "settings-row-text" }, [
        h("span", { class: "settings-row-label", text: device.label }),
        h("span", { class: "settings-row-sub", text: `${device.url}${badges ? ` · ${badges}` : ""}` }),
      ]);
      return h(
        "button",
        {
          class: `settings-row settings-row--action${device.active ? " is-active" : ""}`,
          onclick: () => handlers.onDeviceActivate(device.id),
          title: "点击切换，长按管理",
        },
        [left, h("span", { class: "settings-row-value", text: device.active ? "使用中" : "切换" })],
      );
    });

    deviceRows.push(
      h("div", { class: "settings-actions" }, [
        h("button", { class: "settings-action", text: "添加设备", onclick: () => handlers.onAddDevice() }),
        h("button", {
          class: "settings-action settings-action--danger",
          text: "断开全部连接",
          onclick: () => handlers.onDisconnect(),
        }),
      ]),
    );

    body.append(section(`连接与设备（${data.devices.length}）`, deviceRows));

    // ── pi 概况 ──
    const overview: HTMLElement[] = [
      h("p", { class: "settings-note", text: DESKTOP_ONLY_NOTE }),
    ];

    if (data.loading) {
      overview.push(h("p", { class: "settings-note", text: "正在读取模型 / 技能 / 扩展…" }));
    } else if (data.loadError) {
      overview.push(h("p", { class: "settings-note settings-note--error", text: data.loadError }));
    }

    overview.push(row("可用模型", `${data.models.length} 个`));
    if (data.models.length > 0) {
      overview.push(
        collapsible("模型列表", data.models.length, () =>
          data.models.map((model) =>
            row(model.name, model.provider ? `${model.provider}` : "—", {
              ...(model.modelId !== model.name ? { sub: model.modelId } : {}),
            }),
          ),
        ),
      );
    }

    overview.push(
      row("供应商额度", "查询", { sub: "与桌面端同源，未开启时会给引导", onClick: () => handlers.onOpenUsage() }),
    );
    body.append(section("pi 概况", overview));

    // ── 技能 / 扩展 ──
    const skillsSection: HTMLElement[] = [];
    if (data.skills.length === 0 && !data.loading) {
      skillsSection.push(h("p", { class: "settings-note", text: "这台 PiDeck 没有返回技能列表" }));
    }
    for (const group of data.skills) {
      skillsSection.push(
        collapsible(group.label, group.rows.length, () =>
          group.rows.map((skill) =>
            switchRow(
              skill.name,
              skill.enabled,
              (next) => handlers.onToggleSkill(group, skill.name, next),
              {
                sub: [skill.description, skill.userOnly ? "仅手动调用" : "", skill.valid ? "" : "无效"]
                  .filter(Boolean)
                  .join(" · "),
                disabled: !skill.valid,
              },
            ),
          ),
        ),
      );
    }
    body.append(section("技能", skillsSection));

    const extensionSection: HTMLElement[] = [];
    if (data.extensions.length === 0 && !data.loading) {
      extensionSection.push(h("p", { class: "settings-note", text: "没有返回扩展列表" }));
    } else if (data.extensions.length > 0) {
      extensionSection.push(
        collapsible("扩展列表", data.extensions.length, () =>
          data.extensions.map((extension) =>
            switchRow(
              extension.name,
              extension.enabled,
              (next) => handlers.onToggleExtension(extension, next),
              {
                sub: [extension.scope === "project" ? "项目级" : "", extension.description]
                  .filter(Boolean)
                  .join(" · "),
              },
            ),
          ),
        ),
      );
    }
    body.append(section("扩展", extensionSection));

    // ── DSH 插件 ──
    if (data.dshPlugins.length > 0) {
      body.append(
        section(
          "DSH 插件",
          data.dshPlugins.map((plugin) =>
            row(plugin.name, plugin.running ? "运行中" : plugin.version || "已安装", {
              ...(plugin.description ? { sub: plugin.description } : {}),
            }),
          ),
        ),
      );
    }

    // ── 关于 ──
    body.append(
      section("关于", [
        row("客户端版本", data.connection.version),
        row("服务地址", data.connection.serviceUrl),
        row("访问令牌", maskToken(data.connection.token), { sub: "明文存在本机，上架前需换原生加密存储" }),
        row("连接状态", data.connection.status),
      ]),
    );
  };

  return {
    el,
    update,
    destroy: () => el.remove(),
  };
}
