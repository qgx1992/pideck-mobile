import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  // 必须与 android/app/build.gradle 的 applicationId 一致（Capacitor 会校验）
  appId: "top.caoayu.pideck",
  appName: "PiDeck",
  webDir: "dist",
  server: {
    // 允许在 WebView 里加载 http 的局域网地址（Tailscale / Cloudflare 用 https 时不受影响）
    androidScheme: "https",
    cleartext: true,
  },
  android: {
    // 允许明文 HTTP 直连局域网 PiDeck（仅内网使用；公网请走 Tailscale HTTPS）
    allowMixedContent: true,
  },
  plugins: {
    CapacitorHttp: {
      // 必须保持 false：enabled=true 会让 Capacitor 接管 window.fetch，
      // 而它把响应整体缓冲，流式增量（含 SSE）就没了。
      // 我们自己在 src/platform/fetch.ts 里分流：SSE 走 SsePlugin，其余显式调 CapacitorHttp.request()。
      enabled: false,
    },
  },
};

export default config;
