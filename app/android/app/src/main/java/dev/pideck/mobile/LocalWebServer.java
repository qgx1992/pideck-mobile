package dev.pideck.mobile;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLSocketFactory;

/**
 * 「桌面网页版」的本地外壳服务：把桌面端自带的 Web 端（`web.html` + `/assets`）搬到
 * `http://127.0.0.1:<port>/` 上，并把 `/api/*` 原样转发回桌面端。
 *
 * 为什么值得多这一层（而不是让 WebView 直接开 `http://<电脑>:8765/`）：
 * 1. **同源**：桌面 Web 端的所有路径都是相对的（`/api/...`、`/assets/...`、`sw.js`），
 *    换 origin 后依然成立，不用改桌面端一行代码；
 * 2. **离线首屏**：静态资源回源一次后落盘缓存，之后桌面不在线也能把界面画出来（数据面照常报错）；
 * 3. **鉴权收口**：`Authorization: Bearer <token>` 由这一层统一注入，页面不拿 token 也能用，
 *    EventSource 这种不能发头的请求也照样有人补头；
 * 4. **多设备**：换配对只改上游地址，不用重新加载页面（{@link #setUpstream}）。
 *
 * 只监听 127.0.0.1，随机端口，进程退出即失效。
 */
final class LocalWebServer {

    private static final String TAG = "PiDeckLocalWeb";
    /** 转发请求体上限：桌面端 `/api/chat` 的 body 上限就是 8 MB，留点余量。 */
    private static final int MAX_BODY_BYTES = 12 * 1024 * 1024;
    /** 静态资源单文件上限（桌面端 assets 里最大的 mermaid chunk 约 3 MB）。 */
    private static final long MAX_ASSET_BYTES = 24L * 1024 * 1024;
    private static final String CRLF = new String(new char[]{13, 10});

    private final Context appContext;
    private final File cacheRoot;
    private volatile String upstreamBase;
    private volatile String token;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;
    private int port;

    LocalWebServer(Context context, String upstreamBase, String token) {
        this.appContext = context.getApplicationContext();
        this.cacheRoot = new File(this.appContext.getCacheDir(), "webui");
        this.upstreamBase = normalizeBase(upstreamBase);
        this.token = token == null ? "" : token;
    }

    static String normalizeBase(String base) {
        if (base == null) return "";
        String value = base.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    /** 启动并返回本地 origin（`http://127.0.0.1:<port>`）；失败返回 null。 */
    synchronized String start() {
        if (running) return origin();
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            // 只给本机用：WebView 与代理都是同进程，不给局域网留口子
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            port = serverSocket.getLocalPort();
            running = true;
        } catch (IOException error) {
            Log.w(TAG, "启动失败: " + error);
            serverSocket = null;
            return null;
        }
        acceptThread = new Thread(this::acceptLoop, "pi-local-web-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "本地界面服务已启动 " + origin() + " → " + upstreamBase);
        return origin();
    }

    synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        acceptThread = null;
    }

    String origin() {
        return "http://127.0.0.1:" + port;
    }

    /** 换配对设备：只改上游，页面不用重载（下次请求即生效）。 */
    void setUpstream(String base, String newToken) {
        this.upstreamBase = normalizeBase(base);
        this.token = newToken == null ? "" : newToken;
    }

    private void acceptLoop() {
        while (running) {
            Socket client = null;
            try {
                client = serverSocket.accept();
            } catch (IOException error) {
                if (running) Log.w(TAG, "accept 失败: " + error);
                continue;
            }
            final Socket socket = client;
            Thread worker = new Thread(() -> handle(socket), "pi-local-web-conn");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void handle(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(30_000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }

            byte[] body = new byte[0];
            String lengthHeader = headers.get("content-length");
            if (lengthHeader != null) {
                int length;
                try {
                    length = Integer.parseInt(lengthHeader.trim());
                } catch (NumberFormatException error) {
                    length = 0;
                }
                if (length > 0) {
                    if (length > MAX_BODY_BYTES) {
                        writeSimple(out, 413, "text/plain; charset=utf-8", "request body too large");
                        return;
                    }
                    body = readFully(in, length);
                }
            }

            String path = target;
            int queryAt = path.indexOf('?');
            String query = queryAt >= 0 ? path.substring(queryAt) : "";
            if (queryAt >= 0) path = path.substring(0, queryAt);

            if (path.equals("/__pideck/status")) {
                writeSimple(out, 200, "application/json; charset=utf-8",
                        "{\"ok\":true,\"upstream\":\"" + upstreamBase + "\"}");
                return;
            }

            if (path.startsWith("/api/")) {
                proxy(method, path + query, headers, body, out);
                return;
            }

            serveStatic(method, path, out);
        } catch (IOException error) {
            Log.w(TAG, "连接处理失败: " + error);
        }
    }

    // ---------------------------------------------------------------- 静态资源

    /**
     * 静态资源：优先用缓存（桌面端的 assets 是 immutable，缓存永远正确），
     * 没缓存就回源取一次再落盘；桌面不在线时退回缓存，连缓存都没有就给一个说明页。
     */
    private void serveStatic(String method, String path, OutputStream out) throws IOException {
        String normalized = normalizeAssetPath(path);
        File cached = new File(cacheRoot, normalized);
        if (cached.isFile() && cached.length() > 0) {
            sendFile(out, cached, contentTypeOf(normalized));
            return;
        }
        Fetched fetched = null;
        try {
            // 入口页在桌面端是 `/`（它优先回 web.html），不是 `/index.html`
            fetched = fetchUpstream("GET", normalized.equals("index.html") ? "" : normalized);
        } catch (IOException error) {
            Log.w(TAG, "回源失败 " + normalized + ": " + error);
        }
        if (fetched != null && fetched.status == 200) {
            storeCache(normalized, fetched.body);
            writeBody(out, 200, contentTypeOf(normalized), fetched.body);
            return;
        }
        if (fetched != null && fetched.status >= 400 && fetched.status < 500) {
            // 资源真的不存在：把 404 透出去，别拿入口页糊弄（否则页面会执行一段 HTML）
            writeBody(out, fetched.status, "text/plain; charset=utf-8",
                    ("not found: " + normalized).getBytes(StandardCharsets.UTF_8));
            return;
        }
        // 回源失败：SPA 兜底 → 用缓存的入口页（桌面掉线时界面仍然出得来）
        if (!normalized.equals("index.html")) {
            File entry = new File(cacheRoot, "index.html");
            if (entry.isFile() && entry.length() > 0) {
                sendFile(out, entry, "text/html; charset=utf-8");
                return;
            }
        }
        writeSimple(out, 503, "text/html; charset=utf-8", offlinePage());
    }

    private String normalizeAssetPath(String path) {
        String clean = path;
        while (clean.startsWith("/")) clean = clean.substring(1);
        if (clean.isEmpty()) return "index.html";
        if (!clean.contains(".")) {
            // 桌面端把 `/` 与 `/s/<id>` 这类无扩展名路径都当入口页（web.html）
            return "index.html";
        }
        return clean;
    }

    private void storeCache(String name, byte[] data) {
        if (data.length > MAX_ASSET_BYTES) return;
        File target = new File(cacheRoot, name);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
        try (FileOutputStream stream = new FileOutputStream(target)) {
            stream.write(data);
        } catch (IOException error) {
            Log.w(TAG, "写缓存失败 " + name + ": " + error);
        }
    }

    private void sendFile(OutputStream out, File file, String contentType) throws IOException {
        long length = file.length();
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Cache-Control: public, max-age=31536000, immutable\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
        out.flush();
    }

    // ---------------------------------------------------------------- 代理

    /**
     * `/api/*` 直通。要点：
     * - 统一注入 `Authorization: Bearer <token>`（GET 也能用，页面不必把 token 拼在 URL 上）；
     * - 请求侧 `Connection: close`，响应侧原样透传（含 chunked），SSE 逐块 flush 不攒包。
     */
    private void proxy(String method, String pathAndQuery, Map<String, String> headers, byte[] body, OutputStream out)
            throws IOException {
        String base = upstreamBase;
        if (base.isEmpty()) {
            writeSimple(out, 503, "application/json; charset=utf-8",
                    "{\"ok\":false,\"error\":\"尚未配对设备\"}");
            return;
        }
        Socket upstream = openSocket(base);
        try {
            upstream.setSoTimeout(0); // SSO/SSE：不设读超时，由对端决定何时结束
            OutputStream upOut = upstream.getOutputStream();
            StringBuilder request = new StringBuilder();
            request.append(method).append(' ').append(pathAndQuery).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(hostOf(base)).append("\r\n");
            String contentType = headers.get("content-type");
            if (contentType != null) request.append("Content-Type: ").append(contentType).append("\r\n");
            String accept = headers.get("accept");
            if (accept != null) request.append("Accept: ").append(accept).append("\r\n");
            if (!token.isEmpty()) request.append("Authorization: Bearer ").append(token).append("\r\n");
            request.append("Content-Length: ").append(body.length).append("\r\n");
            request.append("Connection: close\r\n\r\n");
            upOut.write(request.toString().getBytes(StandardCharsets.UTF_8));
            if (body.length > 0) upOut.write(body);
            upOut.flush();

            InputStream upIn = upstream.getInputStream();
            // 状态行 + 响应头原样透传（只把 Connection 改成 close，避免客户端等 keep-alive）
            ByteArrayOutputStream headBuffer = new ByteArrayOutputStream();
            String line;
            while ((line = readLine(upIn)) != null) {
                if (line.isEmpty()) break;
                if (line.toLowerCase(Locale.ROOT).startsWith("connection:")) continue;
                if (line.toLowerCase(Locale.ROOT).startsWith("transfer-encoding:")) {
                    headBuffer.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
                    continue;
                }
                headBuffer.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            headBuffer.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(headBuffer.toByteArray());
            out.flush();

            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = upIn.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                out.flush(); // SSE：一块一块走，别攒
            }
        } finally {
            try {
                upstream.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 一次静态资源回源的结果：404 与「连不上」要区分处理。 */
    private static final class Fetched {
        final int status;
        final byte[] body;

        Fetched(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }
    }

    /** 一次性把上游响应读完（只给静态资源回源用）。 */
    private Fetched fetchUpstream(String method, String path) throws IOException {
        String base = upstreamBase;
        if (base.isEmpty()) return null;
        Socket upstream = openSocket(base);
        try {
            upstream.setSoTimeout(20_000);
            OutputStream upOut = upstream.getOutputStream();
            String requestPath = path.isEmpty() ? "/" : "/" + path;
            String request = method + " " + requestPath + " HTTP/1.1" + CRLF
                    + "Host: " + hostOf(base) + CRLF
                    + "Connection: close" + CRLF + CRLF;
            upOut.write(request.getBytes(StandardCharsets.UTF_8));
            upOut.flush();

            InputStream in = upstream.getInputStream();
            String status = readLine(in);
            int code = 0;
            if (status != null) {
                String[] parts = status.split(" ");
                if (parts.length >= 2) {
                    try {
                        code = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                // 静态资源不需要上游的响应头
            }
            if (code != 200) return new Fetched(code, new byte[0]);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > MAX_ASSET_BYTES) return null;
            }
            return new Fetched(code, buffer.toByteArray());
        } finally {
            try {
                upstream.close();
            } catch (IOException ignored) {
            }
        }
    }

    private Socket openSocket(String base) throws IOException {
        java.net.URI uri = java.net.URI.create(base);
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        int port = uri.getPort();
        if (port <= 0) port = "https".equals(scheme) ? 443 : 80;
        if ("https".equals(scheme)) {
            return SSLSocketFactory.getDefault().createSocket(host, port);
        }
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 10_000);
        return socket;
    }

    private static String hostOf(String base) {
        java.net.URI uri = java.net.URI.create(base);
        int port = uri.getPort();
        return port > 0 ? uri.getHost() + ":" + port : uri.getHost();
    }

    // ---------------------------------------------------------------- 工具

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int read;
        while ((read = in.read()) >= 0) {
            if (read == '\n') break;
            if (read != '\r') buffer.write(read);
            if (buffer.size() > 16 * 1024) break;
        }
        if (read < 0 && buffer.size() == 0) return null;
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(data, offset, length - offset);
            if (read < 0) break;
            offset += read;
        }
        if (offset == length) return data;
        byte[] trimmed = new byte[offset];
        System.arraycopy(data, 0, trimmed, 0, offset);
        return trimmed;
    }

    private static void writeSimple(OutputStream out, int status, String contentType, String text) throws IOException {
        writeBody(out, status, contentType, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBody(OutputStream out, int status, String contentType, byte[] data) throws IOException {
        String reason;
        switch (status) {
            case 200: reason = "OK"; break;
            case 404: reason = "Not Found"; break;
            case 413: reason = "Payload Too Large"; break;
            default: reason = "Service Unavailable"; break;
        }
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private static String contentTypeOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json") || lower.endsWith(".webmanifest")) return "application/json; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".wasm")) return "application/wasm";
        return "application/octet-stream";
    }

    /** 桌面不在线、缓存也没有时给的一页：说清楚为什么，而不是让 WebView 显示白屏。 */
    private String offlinePage() {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">"
                + "<title>PiDeck</title><style>"
                + "body{margin:0;height:100dvh;display:flex;align-items:center;justify-content:center;"
                + "background:#101014;color:#e8e8ee;font:15px/1.6 system-ui,-apple-system,sans-serif;padding:24px}"
                + ".card{max-width:26rem}.t{font-size:17px;font-weight:600;margin-bottom:8px}"
                + ".m{opacity:.7}.u{margin-top:14px;font:12px/1.5 ui-monospace,monospace;opacity:.5;word-break:break-all}"
                + "</style></head><body><div class=\"card\">"
                + "<div class=\"t\">没能连上这台电脑</div>"
                + "<div class=\"m\">桌面端没有响应；界面的本地副本也还没缓存过（第一次打开需要电脑在线）。"
                + "确认电脑上 PiDeck 正在运行、设置 → Web 服务已开启，然后返回重试。</div>"
                + "<div class=\"u\">" + escapeHtml(upstreamBase) + "</div>"
                + "</div></body></html>";
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
