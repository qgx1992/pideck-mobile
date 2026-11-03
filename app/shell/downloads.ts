/**
 * 会话导出（上游用 `blob:` + `<a download>` 触发下载）。
 *
 * Android WebView 不支持 `download` 属性：点了没反应，也没有任何报错。
 * 这里把带 download 的 blob 链接改成走原生 `FileSaver`（写到系统下载目录）。
 */

import { Capacitor, registerPlugin } from "@capacitor/core";

interface FileSaverApi {
	save(options: { name: string; mime?: string; data: string }): Promise<{ ok: boolean; location?: string }>;
}

const FileSaver = registerPlugin<FileSaverApi>("FileSaver");

function toBase64(buffer: ArrayBuffer): string {
	const bytes = new Uint8Array(buffer);
	const CHUNK = 0x8000;
	let binary = "";
	for (let index = 0; index < bytes.length; index += CHUNK) {
		binary += String.fromCharCode(...bytes.subarray(index, index + CHUNK));
	}
	return btoa(binary);
}

export function installDownloadBridge(): void {
	if (!Capacitor.isNativePlatform()) return;
	const nativeClick = HTMLAnchorElement.prototype.click;
	HTMLAnchorElement.prototype.click = function patchedClick(this: HTMLAnchorElement): void {
		const href = this.href;
		const name = this.download;
		if (!name || !href.startsWith("blob:")) {
			nativeClick.call(this);
			return;
		}
		void (async () => {
			try {
				const blob = await (await fetch(href)).blob();
				const buffer = await blob.arrayBuffer();
				await FileSaver.save({
					name,
					mime: blob.type || "application/octet-stream",
					data: toBase64(buffer),
				});
				// 上游点完会 revokeObjectURL，这里不需要额外处理
			} catch {
				// 保存失败不打断界面；上游没有回调可用
			}
		})();
	};
}
