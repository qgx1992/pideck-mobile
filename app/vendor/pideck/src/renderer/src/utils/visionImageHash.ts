/**
 * 图片内容哈希（浏览器端 sha256 前 24 位）。
 * 与扩展 resources/extensions/pi-deck-vision.ts 的 imageHash 算法保持一致：
 * sha256(utf8 字符串) 的 hex 前 24 位，否则视觉桥事件文件里的 imageHash 匹配不上。
 * 数据源同为 base64 字符串，TextEncoder 编码与 node createHash().update() 的 utf8 编码一致。
 */
export async function visionImageHash(data: string, prefixLength = 24): Promise<string> {
	const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(data));
	const bytes = new Uint8Array(digest);
	let hex = "";
	for (const byte of bytes) hex += byte.toString(16).padStart(2, "0");
	return hex.slice(0, prefixLength);
}

/** 批量计算：返回与原数组同序的哈希列表。 */
export async function visionImageHashes(dataList: string[]): Promise<string[]> {
	return Promise.all(dataList.map((data) => visionImageHash(data)));
}
