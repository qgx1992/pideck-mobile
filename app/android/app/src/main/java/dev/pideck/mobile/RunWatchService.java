package dev.pideck.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 后台任务看护（前台服务）。
 *
 * 为什么必须用原生服务：App 切到后台后会被系统判定为 cached 进程并**冻结**，
 * WebView 里的 JS（包括 SSE 回调和定时器）全部停摆，于是「跑完了」这件事在
 * JS 侧永远观察不到 —— 这就是「有时候收到通知、有时候收不到」的根因。
 *
 * 这里用前台服务（不会被冻结）守着 /api/events 这条 SSE：
 *   - 服务端连上就先推一份完整 state，之后只在「运行时状态 / 待答卡片」变化时推送
 *   - 由此可以同时盯两件事：会话跑完（running → 非 running）、AI 提问（新的待答卡片）
 * 之所以不用轮询 /api/state：那份 JSON 有 57KB，而 SSE 一天下来也只推几次；
 * /api/sessions/runtimes 虽然只有 2.9KB 但不含 pendingUiRequests，看不到提问。
 *
 * 两条约束：
 *   - 只在 App 不可见时发通知（可见时由 JS 自己发，避免重复）
 *   - 服务必须在 App 还活着时启动（Android 12+ 不允许后台启动前台服务），
 *     所以由 JS 在「开始跑」的那一刻就把它拉起来
 */
public class RunWatchService extends Service {

    private static final String TAG = "RunWatch";

    public static final String ACTION_START = "dev.pideck.mobile.RUNWATCH_START";
    public static final String ACTION_STOP = "dev.pideck.mobile.RUNWATCH_STOP";
    public static final String EXTRA_BASE_URL = "baseUrl";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_TITLES = "titles";

    /** App 是否可见；可见时服务只跟踪状态、不发通知（由 JS 负责提示） */
    private static volatile boolean appVisible = true;

    private static final String CHANNEL_ONGOING = "pideck-watch";
    private static final String CHANNEL_DONE = "pideck-task";
    private static final int ONGOING_NOTIFICATION_ID = 9001;
    /** 断线重连间隔 */
    private static final long RECONNECT_DELAY_MS = 3_000L;
    /** 读超时：SSE 平时没数据，超过这个时间没有任何字节就认为连接已死 */
    private static final int READ_TIMEOUT_MS = 120_000;

    private volatile boolean running = false;
    private Thread worker;
    private String baseUrl = "";
    private String token = "";
    private JSONObject titles = new JSONObject();
    private final Map<String, String> lastStatus = new HashMap<>();
    /** 上一次见过的待答卡片（sessionId:requestId），用来判断「新提问」 */
    private final java.util.Set<String> knownRequests = new java.util.HashSet<>();
    private boolean seeded = false;

    public static void setAppVisible(boolean visible) {
        appVisible = visible;
    }

    public static void start(Context context, String baseUrl, String token, String titlesJson) {
        Intent intent = new Intent(context, RunWatchService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_BASE_URL, baseUrl);
        intent.putExtra(EXTRA_TOKEN, token);
        intent.putExtra(EXTRA_TITLES, titlesJson);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, RunWatchService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (Exception error) {
            Log.w(TAG, "stop failed", error);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : "";
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String nextBaseUrl = intent != null ? intent.getStringExtra(EXTRA_BASE_URL) : null;
        String nextToken = intent != null ? intent.getStringExtra(EXTRA_TOKEN) : null;
        String nextTitles = intent != null ? intent.getStringExtra(EXTRA_TITLES) : null;
        if (nextBaseUrl != null) baseUrl = nextBaseUrl;
        if (nextToken != null) token = nextToken;
        if (nextTitles != null) {
            try {
                titles = new JSONObject(nextTitles);
            } catch (Exception ignored) {
                titles = new JSONObject();
            }
        }

        startForegroundSafely();
        startWorkerIfNeeded();
        return START_STICKY;
    }

    private void startForegroundSafely() {
        ensureChannels();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setContentTitle("PiDeck")
            .setContentText("正在等待任务完成…")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    ONGOING_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                );
            } else {
                startForeground(ONGOING_NOTIFICATION_ID, notification);
            }
        } catch (Exception error) {
            Log.w(TAG, "startForeground failed", error);
        }
    }

    private void startWorkerIfNeeded() {
        if (running) return;
        running = true;
        worker = new Thread(this::loop, "pideck-runwatch");
        worker.setDaemon(true);
        worker.start();
    }

    private void loop() {
        while (running) {
            try {
                listenOnce();
            } catch (Exception error) {
                Log.w(TAG, "stream failed: " + error.getMessage());
            }
            if (!running) break;
            try {
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException interrupted) {
                break;
            }
        }
    }

    /** 连一次 /api/events 并读到断线为止。 */
    private void listenOnce() throws Exception {
        if (baseUrl.isEmpty() || token.isEmpty()) {
            Thread.sleep(RECONNECT_DELAY_MS);
            return;
        }
        String url = baseUrl.replaceAll("/+$", "")
            + "/api/events?token=" + URLEncoder.encode(token, "UTF-8");

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setRequestProperty("Cache-Control", "no-cache");

        try {
            int code = connection.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "events http " + code);
                return;
            }
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
            );
            String line;
            String event = null;
            StringBuilder data = null;

            while (running && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data != null) {
                        handleEvent(event, data.toString());
                    }
                    event = null;
                    data = null;
                    continue;
                }
                if (line.startsWith(":")) continue;                 // 注释/心跳
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                    continue;
                }
                if (!line.startsWith("data:")) continue;
                String chunk = line.substring(5).trim();
                if (data == null) data = new StringBuilder(chunk);
                else data.append(chunk);
            }
        } finally {
            connection.disconnect();
        }
    }

    private void handleEvent(String event, String payload) {
        if (event != null && !"state".equals(event)) return;
        try {
            handleState(payload);
        } catch (Exception error) {
            Log.w(TAG, "bad state payload: " + error.getMessage());
        }
    }

    private void handleState(String body) throws Exception {
        JSONObject root = new JSONObject(body);

        Map<String, String> next = new HashMap<>();
        JSONArray runtimes = root.optJSONArray("runtimes");
        if (runtimes != null) {
            for (int i = 0; i < runtimes.length(); i++) {
                JSONObject item = runtimes.optJSONObject(i);
                if (item == null) continue;
                String sessionId = item.optString("sessionId", "");
                if (sessionId.isEmpty()) continue;
                next.put(sessionId, item.optString("status", ""));
            }
        }

        JSONObject sessionTitles = new JSONObject();
        JSONArray sessions = root.optJSONArray("sessions");
        if (sessions != null) {
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject item = sessions.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                if (!id.isEmpty()) sessionTitles.put(id, item.optString("title", ""));
            }
        }

        // 1) 会话跑完：running → 非 running（含 runtime 消失）
        if (!lastStatus.isEmpty()) {
            for (Map.Entry<String, String> entry : lastStatus.entrySet()) {
                if (!"running".equals(entry.getValue())) continue;
                String sessionId = entry.getKey();
                String current = next.get(sessionId);
                boolean finished = current == null || !"running".equals(current);
                if (!finished) continue;
                if (!appVisible) notifyFinished(sessionId, current, sessionTitles.optString(sessionId, ""));
            }
        }
        lastStatus.clear();
        lastStatus.putAll(next);

        // 2) AI 提问：新出现的待答卡片
        JSONArray pendings = root.optJSONArray("pendingUiRequests");
        java.util.Set<String> nextRequests = new java.util.HashSet<>();
        JSONArray fresh = new JSONArray();
        if (pendings != null) {
            for (int i = 0; i < pendings.length(); i++) {
                JSONObject item = pendings.optJSONObject(i);
                if (item == null) continue;
                String sessionId = item.optString("sessionId", "");
                String requestId = item.optString("requestId", "");
                if (sessionId.isEmpty() || requestId.isEmpty()) continue;
                String key = sessionId + ":" + requestId;
                nextRequests.add(key);
                // 第一次收到的快照只当基线：用户可能正盯着那张卡片
                if (seeded && !knownRequests.contains(key)) fresh.put(item);
            }
        }
        knownRequests.clear();
        knownRequests.addAll(nextRequests);
        seeded = true;

        if (fresh.length() > 0) {
            // 同一会话多个提问合并成一条，别刷屏
            Map<String, Integer> counts = new HashMap<>();
            Map<String, String> firstTitle = new HashMap<>();
            for (int i = 0; i < fresh.length(); i++) {
                JSONObject item = fresh.optJSONObject(i);
                if (item == null) continue;
                String sessionId = item.optString("sessionId", "");
                counts.put(sessionId, counts.getOrDefault(sessionId, 0) + 1);
                if (!firstTitle.containsKey(sessionId)) {
                    String question = item.optString("title", "").trim();
                    if (question.isEmpty()) question = item.optString("question", "").trim();
                    firstTitle.put(sessionId, question);
                }
                rememberNotified("ask:" + sessionId + ":" + item.optString("requestId", ""));
            }
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                String sessionId = entry.getKey();
                if (!appVisible) {
                    notifyAsk(sessionId, entry.getValue(), firstTitle.get(sessionId), sessionTitles.optString(sessionId, ""));
                }
            }
        }
    }

    /** AI 提问通知：agent 会一直阻塞等回答，所以这条比「跑完了」更该及时。 */
    private void notifyAsk(String sessionId, int count, String question, String sessionTitle) {
        String name = sessionTitle == null ? "" : sessionTitle.trim();
        String body;
        if (count > 1) {
            body = name.isEmpty() ? ("有 " + count + " 个问题待回答") : ("「" + name + "」有 " + count + " 个问题待回答");
        } else if (question != null && !question.trim().isEmpty()) {
            body = name.isEmpty() ? question.trim() : ("「" + name + "」：" + question.trim());
        } else {
            body = name.isEmpty() ? "有一个问题待回答" : ("「" + name + "」有一个问题待回答");
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(MainActivity.EXTRA_SESSION_ID, sessionId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getActivity(this, (sessionId + "#ask").hashCode(), intent, flags);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_DONE)
            .setContentTitle("AI 在等你回答")
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build();

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        try {
            manager.notify((sessionId + "#ask").hashCode(), notification);
        } catch (Exception error) {
            Log.w(TAG, "ask notify failed", error);
        }
    }

    private void notifyFinished(String sessionId, String status, String sessionTitle) {
        String title = sessionTitle.isEmpty() ? titles.optString(sessionId, "") : sessionTitle;
        String heading = status == null ? "任务已结束" : ("error".equals(status) ? "任务出错" : "任务完成");
        String body = title.isEmpty() ? "会话已经跑完一轮" : "「" + title + "」已结束";

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(MainActivity.EXTRA_SESSION_ID, sessionId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getActivity(this, sessionId.hashCode(), intent, flags);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_DONE)
            .setContentTitle(heading)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build();

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        try {
            manager.notify(sessionId.hashCode(), notification);
            rememberNotified("run:" + sessionId);
        } catch (Exception error) {
            Log.w(TAG, "notify failed", error);
        }
    }

    /** 记下「原生已经通知过」的会话，JS 恢复后据此避免重复弹。 */
    private void rememberNotified(String key) {
        SharedPreferences prefs = getSharedPreferences(RunWatchPlugin.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putLong("notified:" + key, System.currentTimeMillis()).apply();
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        NotificationChannel ongoing = new NotificationChannel(
            CHANNEL_ONGOING,
            "后台任务看护",
            NotificationManager.IMPORTANCE_MIN
        );
        ongoing.setDescription("任务运行期间保持连接，以便及时提醒");
        manager.createNotificationChannel(ongoing);

        NotificationChannel done = new NotificationChannel(
            CHANNEL_DONE,
            "任务完成提醒",
            NotificationManager.IMPORTANCE_HIGH
        );
        done.setDescription("会话里 Agent 跑完一轮时提醒");
        manager.createNotificationChannel(done);
    }


    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
