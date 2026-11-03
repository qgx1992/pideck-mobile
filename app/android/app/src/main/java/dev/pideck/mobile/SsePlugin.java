package dev.pideck.mobile;

import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 原生 SSE 传输（`text/event-stream` 走原生栈，不再走 WebView 的 fetch）。
 *
 * ## 为什么需要
 *
 * CapacitorHttp（原生栈）不支持流式响应体，所以改造前 SSE 只能留在 WebView：
 *   - WebView 的 fetch 受 CORS 约束 → PiDeck 的预检不放行 `authorization`，
 *     跨域 SSE 只能把 token 放进 query（落进对端访问日志）
 *   - App 切后台后 WebView 被冻结 → SSE 静默断流，只能靠 RunWatchService 轮询补救
 *
 * 换成原生 HttpURLConnection 读流之后：SSE 与其它请求同一条栈，
 * 没有 CORS 概念，也不再依赖 WebView 的线程调度。
 *
 * ## 协议（与 `app/shell/native-sse.ts` 一一对应）
 *
 *   open({id,url,method,headers,body,connectTimeoutMs,readTimeoutMs})  → 立即 resolve，开始连接
 *
 * `method` 默认 GET；`body` 非空时用 POST/自定义方法，并把正文原样写出去
 * （上游 web 的 `/api/chat` 就是 POST + SSE 响应）。
 *   sseOpen  {id, status, headers}      响应头到手（含 4xx/5xx，错误体照样往下灌）
 *   sseData  {id, data}                 正文增量（**已按 UTF-8 解码成字符串**，不会切坏多字节字符）
 *   sseEnd   {id, status}               对端正常关闭
 *   sseError {id, message, code}        连接/读取异常
 *   close({id})                         结束某条流（id 传空则全关）
 *   list()                              当前在跑的流 id（排查用）
 *
 * 事件名特意避开 Capacitor 保留的 `app*` 前缀。
 */
@CapacitorPlugin(name = "Sse")
public class SsePlugin extends Plugin {

    private static final String TAG = "PiDeckSse";

    private static final String EVENT_OPEN = "sseOpen";
    private static final String EVENT_DATA = "sseData";
    private static final String EVENT_END = "sseEnd";
    private static final String EVENT_ERROR = "sseError";

    /** 建立连接的超时。SSE 本身不设读超时（见 DEFAULT_READ_TIMEOUT_MS） */
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 15_000;
    /** 0 = 无限。假死由 JS 侧看门狗（SessionChannel.stallMs，50s）负责 */
    private static final int DEFAULT_READ_TIMEOUT_MS = 0;
    /**
     * 增量合并窗口。每个桥事件都是一次 evaluateJavascript（跑在主线程），
     * 逐字节过桥会把渲染线程压死；16ms 一帧的粒度既流畅又省。
     */
    private static final int FLUSH_INTERVAL_MS = 16;
    /** 攒到这么多字符就立刻 flush，不等窗口（大块回放时不必顿一下） */
    private static final int FLUSH_THRESHOLD_CHARS = 2_048;

    private final Map<String, SseConnection> connections = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @PluginMethod
    public void open(PluginCall call) {
        String id = call.getString("id", "");
        String url = call.getString("url", "");
        if (id == null || id.isEmpty() || url == null || url.isEmpty()) {
            call.reject("id/url required");
            return;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        JSObject headerObject = call.getObject("headers");
        if (headerObject != null) {
            Iterator<String> keys = headerObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = headerObject.optString(key, null);
                if (key != null && !key.isEmpty() && value != null) headers.put(key, value);
            }
        }

        String method = call.getString("method", "GET");
        if (method == null || method.isEmpty()) method = "GET";
        String body = call.getString("body", null);

        int connectTimeoutMs = call.getInt("connectTimeoutMs", DEFAULT_CONNECT_TIMEOUT_MS);
        int readTimeoutMs = call.getInt("readTimeoutMs", DEFAULT_READ_TIMEOUT_MS);

        // 同 id 重复 open：先停旧连接，否则两条流会往同一个会话里重复灌帧
        SseConnection stale = connections.remove(id);
        if (stale != null) stale.abort();

        SseConnection connection = new SseConnection(id, url, method, headers, body, connectTimeoutMs, readTimeoutMs);
        connections.put(id, connection);
        io.execute(connection);
        call.resolve();
    }

    /** 结束一条（或全部）流。JS 侧 cancel / abort 时调用。 */
    @PluginMethod
    public void close(PluginCall call) {
        String id = call.getString("id", "");
        if (id == null || id.isEmpty()) {
            for (SseConnection connection : connections.values()) connection.abort();
            connections.clear();
        } else {
            SseConnection connection = connections.remove(id);
            if (connection != null) connection.abort();
        }
        call.resolve();
    }

    /** 当前在跑的流 id，排查「是不是真走了原生栈」时用。 */
    @PluginMethod
    public void list(PluginCall call) {
        JSArray ids = new JSArray();
        for (String id : connections.keySet()) ids.put(id);
        JSObject result = new JSObject();
        result.put("ids", ids);
        call.resolve(result);
    }

    @Override
    protected void handleOnDestroy() {
        for (SseConnection connection : connections.values()) connection.abort();
        connections.clear();
        io.shutdownNow();
        scheduler.shutdownNow();
        super.handleOnDestroy();
    }

    /* ------------------------------------------------------------------ */
    /* 单条连接                                                            */
    /* ------------------------------------------------------------------ */

    private final class SseConnection implements Runnable {

        private final String id;
        private final String url;
        private final String method;
        private final String body;
        private final Map<String, String> headers;
        private final int connectTimeoutMs;
        private final int readTimeoutMs;

        private final AtomicBoolean closed = new AtomicBoolean(false);
        /** 未到 flush 窗口的正文。只有读线程和定时器线程会碰，统一 synchronized(buffer) */
        private final StringBuilder buffer = new StringBuilder();
        /**
         * 取正文 + 推事件必须整体串行：定时器线程与读线程同时 flush 时，
         * 否则可能「后取到的先发」，JS 端拼出来的文本就是乱的。
         */
        private final Object flushLock = new Object();
        private volatile ScheduledFuture<?> pendingFlush;
        private volatile HttpURLConnection connection;
        private volatile InputStream stream;
        private volatile int status;

        SseConnection(String id, String url, String method, Map<String, String> headers, String body,
                      int connectTimeoutMs, int readTimeoutMs) {
            this.id = id;
            this.url = url;
            this.method = method;
            this.body = body;
            this.headers = headers;
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
        }

        @Override
        public void run() {
            Reader reader = null;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                connection = conn;
                conn.setRequestMethod(method);
                if (body != null && !body.isEmpty()) {
                    // 必须在 connect 之前置位；写入正文后 getResponseCode 才发请求
                    conn.setDoOutput(true);
                }
                conn.setConnectTimeout(connectTimeoutMs);
                conn.setReadTimeout(readTimeoutMs);
                conn.setUseCaches(false);
                conn.setDoInput(true);
                // 明确拒绝压缩：Android 的 HttpURLConnection 默认会带 Accept-Encoding: gzip，
                // 而 gzip 要攒够一个块才会吐数据，会把首 token 延迟和流式节奏一起拖坏。
                conn.setRequestProperty("Accept-Encoding", "identity");
                conn.setRequestProperty("Cache-Control", "no-cache");
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    conn.setRequestProperty(header.getKey(), header.getValue());
                }

                if (body != null && !body.isEmpty() && !"GET".equalsIgnoreCase(method)) {
                    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                    conn.setFixedLengthStreamingMode(payload.length);
                    java.io.OutputStream out = conn.getOutputStream();
                    out.write(payload);
                    out.flush();
                    out.close();
                }

                int code = conn.getResponseCode();
                status = code;
                if (closed.get()) return;
                emitOpen(code, conn.getHeaderFields());

                if (code >= 400) {
                    // 错误体也要交给 JS：client.ts 的 toError 会读 json() 拿 code/message
                    InputStream errorStream = conn.getErrorStream();
                    if (errorStream != null) {
                        stream = errorStream;
                        reader = new InputStreamReader(errorStream, StandardCharsets.UTF_8);
                        pump(reader);
                    }
                    finish();
                    return;
                }

                InputStream input = conn.getInputStream();
                stream = input;
                reader = new InputStreamReader(input, StandardCharsets.UTF_8);
                pump(reader);
                finish();
            } catch (Throwable error) {
                // 主动 close/disconnect 会让阻塞的 read 抛 IOException，那不是故障
                if (!closed.get()) emitError(error);
            } finally {
                closeQuietly(reader);
                cleanup();
            }
        }

        private void pump(Reader reader) throws IOException {
            char[] chunk = new char[4096];
            int read;
            while (!closed.get() && (read = reader.read(chunk, 0, chunk.length)) != -1) {
                if (read > 0) push(new String(chunk, 0, read));
            }
        }

        private void push(String text) {
            boolean immediate;
            synchronized (buffer) {
                buffer.append(text);
                immediate = buffer.length() >= FLUSH_THRESHOLD_CHARS;
            }
            if (immediate) {
                cancelPendingFlush();
                flushNow();
            } else if (pendingFlush == null) {
                pendingFlush = scheduler.schedule(this::flushFromTimer, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
            }
        }

        private void flushFromTimer() {
            pendingFlush = null;
            flushNow();
        }

        private void flushNow() {
            synchronized (flushLock) {
                String payload;
                synchronized (buffer) {
                    if (buffer.length() == 0) return;
                    payload = buffer.toString();
                    buffer.setLength(0);
                }
                if (closed.get()) return;
                JSObject data = new JSObject();
                data.put("id", id);
                data.put("data", payload);
                emit(EVENT_DATA, data);
            }
        }

        private void cancelPendingFlush() {
            ScheduledFuture<?> pending = pendingFlush;
            pendingFlush = null;
            if (pending != null) pending.cancel(false);
        }

        /** 对端正常关闭：把残留正文吐完再报结束（可能没有尾随空行）。 */
        private void finish() {
            if (closed.get()) return;
            synchronized (flushLock) {
                cancelPendingFlush();
                flushNow();
                if (closed.get()) return;
                JSObject payload = new JSObject();
                payload.put("id", id);
                payload.put("status", status);
                emit(EVENT_END, payload);
            }
        }

        private void emitOpen(int code, Map<String, List<String>> headerFields) {
            JSObject flat = new JSObject();
            if (headerFields != null) {
                for (Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
                    String name = entry.getKey();
                    // headerFields 的第一项是状态行，key 为 null
                    if (name == null) continue;
                    List<String> values = entry.getValue();
                    if (values == null || values.isEmpty()) continue;
                    flat.put(name.toLowerCase(Locale.ROOT), values.get(0));
                }
            }
            JSObject payload = new JSObject();
            payload.put("id", id);
            payload.put("status", code);
            payload.put("headers", flat);
            emit(EVENT_OPEN, payload);
        }

        private void emitError(Throwable error) {
            String message = error.getMessage();
            if (message == null || message.isEmpty()) message = error.getClass().getSimpleName();
            JSObject payload = new JSObject();
            payload.put("id", id);
            payload.put("message", message);
            payload.put("code", classify(error));
            emit(EVENT_ERROR, payload);
            Log.w(TAG, "SSE 失败 " + id + ": " + message);
        }

        private String classify(Throwable error) {
            if (error instanceof SocketTimeoutException) return "timeout";
            if (error instanceof UnknownHostException || error instanceof ConnectException) return "network";
            if (error instanceof IOException) return "io";
            return "unknown";
        }

        private void emit(String event, JSObject payload) {
            try {
                notifyListeners(event, payload);
            } catch (Throwable error) {
                Log.w(TAG, "推送事件失败 " + event + ": " + error.getMessage());
            }
        }

        /** JS 侧 cancel/abort：先置位，再关流让阻塞的 read 立刻退出。 */
        void abort() {
            if (!closed.compareAndSet(false, true)) return;
            cancelPendingFlush();
            closeQuietly(stream);
            disconnectQuietly(connection);
        }

        private void cleanup() {
            cancelPendingFlush();
            synchronized (buffer) {
                buffer.setLength(0);
            }
            closeQuietly(stream);
            disconnectQuietly(connection);
            closed.set(true);
            // minSdk 23：不用 Map.remove(k,v) 这个默认方法
            if (connections.get(id) == this) connections.remove(id);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 工具                                                                */
    /* ------------------------------------------------------------------ */

    private static void closeQuietly(java.io.Closeable target) {
        if (target == null) return;
        try {
            target.close();
        } catch (Throwable ignored) {
            // 已经断了，忽略
        }
    }

    private static void disconnectQuietly(HttpURLConnection conn) {
        if (conn == null) return;
        try {
            conn.disconnect();
        } catch (Throwable ignored) {
            // 同上
        }
    }
}
