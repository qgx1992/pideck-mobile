#!/usr/bin/env node
/**
 * 校验下载页里的二维码到底指向哪。
 *
 * 二维码是内联 SVG（一堆 0.8mm 的小方块），肉眼没法核对，扫一次才发现指向旧 IP/旧文件名。
 * 这里把 SVG 的方块还原成位图，用 jsQR 解出来，跟期望值比对。
 *
 *   node app/scripts/verify-qr.mjs                                  # 打印内容
 *   node app/scripts/verify-qr.mjs --expect http://10.10.0.172:8080/
 */

import { readFileSync } from "node:fs";
import path from "node:path";
import process from "node:process";

import jsQR from "jsqr";

const APK_DIR = path.resolve(import.meta.dirname, "..", "..", "apk");

function arg(name) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 ? process.argv[index + 1] : undefined;
}

/** SVG 里每个模块写成 `Mx,yHx2Vy2Hx1z`；单元边长随生成器不同（旧版 0.8，qrcode 是 1），从数据里推。 */
function rasterize(svg, scale = 6, quiet = 4) {
  const cells = [...svg.matchAll(/M([\d.]+),([\d.]+)H([\d.]+)V([\d.]+)H([\d.]+)z/g)].map((m) =>
    m.slice(1).map(Number),
  );
  if (cells.length === 0) throw new Error("SVG 里没找到模块方块");

  const minX = Math.min(...cells.map((c) => c[0]));
  const minY = Math.min(...cells.map((c) => c[1]));
  const maxX = Math.max(...cells.map((c) => c[2]));
  const maxY = Math.max(...cells.map((c) => c[3]));
  const unit = Math.min(...cells.map((c) => c[2] - c[0]).filter((w) => w > 0));
  const cols = Math.round((maxX - minX) / unit);
  const rows = Math.round((maxY - minY) / unit);

  const pad = quiet * scale;
  const width = cols * scale + pad * 2;
  const height = rows * scale + pad * 2;
  const data = new Uint8ClampedArray(width * height * 4).fill(255);

  for (const [x1, y1] of cells) {
    const col = Math.round((x1 - minX) / unit);
    const row = Math.round((y1 - minY) / unit);
    for (let dy = 0; dy < scale; dy += 1) {
      for (let dx = 0; dx < scale; dx += 1) {
        const px = pad + col * scale + dx;
        const py = pad + row * scale + dy;
        const offset = (py * width + px) * 4;
        data[offset] = 0;
        data[offset + 1] = 0;
        data[offset + 2] = 0;
      }
    }
  }
  return { data, width, height, cols, rows };
}

const html = readFileSync(path.join(APK_DIR, "index.html"), "utf-8");
const block = /<div class="qr">([\s\S]*?)<\/div>/.exec(html);
if (!block) {
  console.error("✗ index.html 里找不到 <div class=\"qr\">");
  process.exit(1);
}

const { data, width, height, cols, rows } = rasterize(block[1]);
const found = jsQR(data, width, height, { inversionAttempts: "dontInvert" });
if (!found) {
  console.error(`✗ 解码失败（网格 ${cols}x${rows}），二维码可能被改坏`);
  process.exit(1);
}

const expect = arg("expect");
console.log(`二维码 ${cols}x${rows} → ${found.data}`);
if (expect && found.data !== expect) {
  console.error(`✗ 与期望不符：${expect}`);
  console.error("  重新生成：python app/scripts/gen-qr.py --url " + expect);
  process.exit(1);
}
