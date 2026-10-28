import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";

/**
 * 开发时把 /api 代理到本机 PiDeck（8765），让浏览器里跑的是同源请求，
 * 从而绕开「预检只允许 content-type」这条限制。生产/真机走 CapacitorHttp。
 */
const target = process.env.PIDECK_TARGET ?? "http://127.0.0.1:8765";

export default defineConfig({
  base: "./",
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
