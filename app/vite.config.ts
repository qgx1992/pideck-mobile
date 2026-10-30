import { readFileSync } from "node:fs";
import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";

/** 设置页「关于」里要显示客户端版本，构建时注入，避免和 package.json 漂移。 */
const pkg = JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf-8")) as {
  version: string;
};

/**
 * 开发时把 /api 代理到本机 PiDeck（8765），让浏览器里跑的是同源请求，
 * 从而绕开「预检只允许 content-type」这条限制。生产/真机走 CapacitorHttp。
 */
const target = process.env.PIDECK_TARGET ?? "http://127.0.0.1:8765";

export default defineConfig({
  base: "./",
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
  },
  resolve: {
    alias: {
      "@core": fileURLToPath(new URL("../src", import.meta.url)),
    },
  },
  server: {
    host: true,
    port: 5173,
    fs: { allow: [".."] },
    proxy: {
      "/api": { target, changeOrigin: false },
    },
  },
  build: {
    outDir: "dist",
    target: "es2022",
    sourcemap: true,
  },
});
