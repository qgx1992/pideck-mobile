import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "dev.pideck.mobile",
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
      // 而原生 HTTP 不支持流式响应体，SSE 会直接断掉。
      // 我们只在 src/platform/fetch.ts 里对非 GET 显式调用 CapacitorHttp.request()。
      enabled: false,
    },
  },
};

export default config;
