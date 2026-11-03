package dev.pideck.mobile;

import android.content.Context;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** JVM 侧验证：本地外壳服务的转发语义（静态缓存、鉴权注入、SSE 不缓冲、POST 体透传）。 */
public class LocalWebServerTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        Context.CACHE = new File(System.getProperty("java.io.tmpdir"), "piwebc-" + System.nanoTime());
        Context.CACHE.mkdirs();

        ServerSocket upstream = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        int upstreamPort = upstream.getLocalPort();
        Thread t = new Thread(() -> acceptUpstream(upstream));
        t.setDaemon(true);
        t.start();
        String base = "http://127.0.0.1:" + upstreamPort;

        Class<?> cls = Class.forName("dev.pideck.mobile.LocalWebServer");
        Object server = cls.getDeclaredConstructor(android.content.Context.class, String.class, String.class)
                .newInstance(new Context(), base, "TOKEN-abc");
        String origin = (String) cls.getDeclaredMethod("start").invoke(server);
        System.out.println("origin = " + origin);
        check("服务已启动", origin != null && origin.startsWith("http://127.0.0.1:"));

        // 1) 静态入口：回源 + 落盘
        String entry = http(origin, "GET", "/", null, null);
        check("入口页回源", entry.contains("200 OK") && entry.contains("ENTRY v1"));
        check("入口页落盘缓存", new File(Context.CACHE, "webui/index.html").isFile());

        // 2) 静态资源带扩展名
        String asset = http(origin, "GET", "/assets/app.js", null, null);
        check("assets 回源", asset.contains("200 OK") && asset.contains("console.log(1)"));
        check("assets content-type", asset.toLowerCase().contains("content-type: text/javascript"));

        // 3) /api/* 注入 Authorization 并透传响应
        String state = http(origin, "GET", "/api/state", null, null);
        check("api 透传状态码", state.contains("200 OK"));
        check("api 注入 Bearer", state.contains("\"auth\":\"Bearer TOKEN-abc\""));
        check("api 透传 body", state.contains("\"ok\":true"));

        // 4) POST 请求体透传
        String echoed = http(origin, "POST", "/api/chat", "{\"prompt\":\"hi\"}", "application/json");
        check("POST body 透传", echoed.contains("\"echo\":{\"prompt\":\"hi\"}"));

        // 5) SSE：第一块必须在服务端收尾之前到达（不被缓冲）
        long started = System.currentTimeMillis();
        long firstChunkAt = -1;
        long doneAt;
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), Integer.parseInt(origin.replaceAll(".*:", "")))) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET /api/events HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[4096];
            StringBuilder all = new StringBuilder();
            int read;
            while ((read = in.read(buf)) > 0) {
                String chunk = new String(buf, 0, read, StandardCharsets.UTF_8);
                all.append(chunk);
                if (firstChunkAt < 0 && all.indexOf("data: 1") >= 0) {
                    firstChunkAt = System.currentTimeMillis();
                }
            }
            doneAt = System.currentTimeMillis();
            if (all.indexOf("data: 2") < 0) check("SSE 第二块到达", false);
            else check("SSE 第二块到达", true);
        }
        long gap = firstChunkAt > 0 ? firstChunkAt - started : -1;
        check("SSE 首块延迟 < 500ms（实测 " + gap + "ms，整体 " + (doneAt - started) + "ms）", gap >= 0 && gap < 500);

        // 6) 桌面掉线：静态资源仍然出自缓存
        upstream.close();
        Thread.sleep(200);
        String cachedEntry = http(origin, "GET", "/", null, null);
        check("掉线后入口仍在缓存", cachedEntry.contains("200 OK") && cachedEntry.contains("ENTRY v1"));
        String deepLink = http(origin, "GET", "/s/session-9", null, null);
        check("掉线后深链走缓存入口", deepLink.contains("200 OK") && deepLink.contains("ENTRY v1"));
        String apiDown = http(origin, "GET", "/api/state", null, null);
        check("掉线后 api 报错而不是挂住", apiDown.isEmpty() || apiDown.contains("502") || apiDown.contains("503") || apiDown.contains("500"));

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void acceptUpstream(ServerSocket server) {
        while (true) {
            try {
                Socket socket = server.accept();
                Thread worker = new Thread(() -> serveUpstream(socket));
                worker.setDaemon(true);
                worker.start();
            } catch (IOException error) {
                return;
            }
        }
    }

    static void serveUpstream(Socket socket) {
        try (Socket client = socket) {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String path = requestLine.split(" ")[1];
            String auth = null;
            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("authorization:")) auth = line.substring(line.indexOf(':') + 1).trim();
                if (lower.startsWith("content-length:")) contentLength = Integer.parseInt(line.substring(15).trim());
            }
            byte[] body = new byte[contentLength];
            int offset = 0;
            while (offset < contentLength) {
                int read = in.read(body, offset, contentLength - offset);
                if (read < 0) break;
                offset += read;
            }
            String bodyText = new String(body, StandardCharsets.UTF_8);

            if (path.equals("/") || path.startsWith("/s/")) {
                respond(out, "text/html; charset=utf-8", "<!-- ENTRY v1 -->");
            } else if (path.equals("/assets/app.js")) {
                respond(out, "text/javascript; charset=utf-8", "console.log(1)");
            } else if (path.equals("/api/state")) {
                respond(out, "application/json; charset=utf-8",
                        "{\"ok\":true,\"auth\":\"" + auth + "\"}");
            } else if (path.equals("/api/chat")) {
                respond(out, "application/json; charset=utf-8", "{\"echo\":" + bodyText + "}");
            } else if (path.equals("/api/events")) {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                        + "Cache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write("event: state\ndata: 1\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(700);
                out.write("event: state\ndata: 2\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(100);
            } else {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Exception error) {
            // 掉线测试会走到这里，忽略
        }
    }

    static void respond(OutputStream out, String contentType, String payload) throws IOException {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\nContent-Length: " + data.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int read;
        while ((read = in.read()) >= 0) {
            if (read == '\n') break;
            if (read != '\r') buffer.write(read);
        }
        if (read < 0 && buffer.size() == 0) return null;
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    static String http(String origin, String method, String path, String body, String contentType) throws IOException {
        String hostPort = origin.replace("http://", "");
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), Integer.parseInt(hostPort.split(":")[1]))) {
            socket.setSoTimeout(8000);
            OutputStream out = socket.getOutputStream();
            byte[] data = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(hostPort).append("\r\n");
            if (contentType != null) head.append("Content-Type: ").append(contentType).append("\r\n");
            head.append("Content-Length: ").append(data.length).append("\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) buffer.write(buf, 0, read);
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void check(String name, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "OK   " : "FAIL ") + name);
    }
}
