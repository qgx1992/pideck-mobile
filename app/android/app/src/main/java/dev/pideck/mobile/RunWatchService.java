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
import java.io.InputStream;
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
 * 这里用前台服务（不会被冻结）每 5s 拉一次 /api/sessions/runtimes（实测只有 ~3KB），
 * 发现某会话 running → 非 running 就自己发通知。
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
    private static final long POLL_INTERVAL_MS = 5_000L;

    private volatile boolean running = false;
    private Thread worker;
    private String baseUrl = "";
    private String token = "";
    private JSONObject titles = new JSONObject();
    private final Map<String, String> lastStatus = new HashMap<>();

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
                pollOnce();
            } catch (Exception error) {
                Log.w(TAG, "poll failed: " + error.getMessage());
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                break;
            }
        }
    }

    private void pollOnce() throws Exception {
        if (baseUrl.isEmpty() || token.isEmpty()) return;
        String url = baseUrl.replaceAll("/+$", "")
            + "/api/sessions/runtimes?token=" + URLEncoder.encode(token, "UTF-8");

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(4_000);
        connection.setReadTimeout(6_000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int code = connection.getResponseCode();
            if (code != 200) return;
            String body = readAll(connection.getInputStream());
            handleState(body);
        } finally {
            connection.disconnect();
        }
    }

    private void handleState(String body) {
        JSONArray runtimes;
        try {
            runtimes = new JSONObject(body).optJSONArray("runtimes");
        } catch (Exception error) {
            return;
        }
        if (runtimes == null) return;

        Map<String, String> next = new HashMap<>();
        for (int i = 0; i < runtimes.length(); i++) {
            JSONObject item = runtimes.optJSONObject(i);
            if (item == null) continue;
            String sessionId = item.optString("sessionId", "");
            if (sessionId.isEmpty()) continue;
            next.put(sessionId, item.optString("status", ""));
        }

        for (Map.Entry<String, String> entry : lastStatus.entrySet()) {
            if (!"running".equals(entry.getValue())) continue;
            String sessionId = entry.getKey();
            String current = next.get(sessionId);
            boolean finished = current == null || !"running".equals(current);
            if (!finished) continue;

            // App 可见时由 JS 负责提示，这里只记录状态，避免重复通知
            if (!appVisible) notifyFinished(sessionId, current);
        }
        lastStatus.clear();
        lastStatus.putAll(next);
    }

    private void notifyFinished(String sessionId, String status) {
        String title = titles.optString(sessionId, "");
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
            rememberNotified(sessionId);
        } catch (Exception error) {
            Log.w(TAG, "notify failed", error);
        }
    }

    /** 记下「原生已经通知过」的会话，JS 恢复后据此避免重复弹。 */
    private void rememberNotified(String sessionId) {
        SharedPreferences prefs = getSharedPreferences(RunWatchPlugin.PREFS, Context.MODE_PRIVATE);
        String key = "notified:" + sessionId;
        prefs.edit().putLong(key, System.currentTimeMillis()).apply();
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

    private static String readAll(InputStream stream) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line);
        }
        return builder.toString();
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
