package dev.pideck.mobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 「桌面网页版」全屏页：把自己当成一个浏览器，直接打开 PiDeck 桌面端的 Web 端
 * （`http://<主机>:<端口>/?token=…`）。
 *
 * 为什么用裸 WebView 而不是 Capacitor 的 BridgeActivity：
 * 这里要加载的是**远程 origin** 的页面，桥（native-bridge.js）只在本地 origin 注入，
 * 拉过去也没用；而这一页需要的能力（返回键、文件选择、下载）用原生 WebView 几行就能给。
 * 桌面端自带的那份 UI 已经是给手机走查过的（视口 fit=cover、安全区、pointer:coarse、
 * 760px 以下抽屉侧栏），所以这里只管把系统栏和应用能力接上，不再重画界面。
 *
 * 传参（intent extra）：
 * - {@link #EXTRA_URL}     完整 URL，可带 `?token=`（必填）
 * - {@link #EXTRA_TITLE}   标题（仅日志用）
 */
public class DesktopWebActivity extends AppCompatActivity {

    private static final String TAG = "PiDeckDesktopWeb";
    static final String EXTRA_URL = "pideckWebUrl";
    static final String EXTRA_TITLE = "pideckWebTitle";
    /** 走本地外壳（内置缓存 + /api 转发）：值就是上游 origin，例如 http://10.0.0.7:8765 */
    static final String EXTRA_UPSTREAM = "pideckWebUpstream";
    static final String EXTRA_TOKEN = "pideckWebToken";

    private static final int REQUEST_FILE_CHOOSER = 0x5D11;

    private WebView webView;
    private WebChromeClient.FileChooserParams pendingChooser;
    private LocalWebServer localServer;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String upstream = intent == null ? null : intent.getStringExtra(EXTRA_UPSTREAM);
        String token = intent == null ? null : intent.getStringExtra(EXTRA_TOKEN);
        String url;
        if (upstream != null && !upstream.isEmpty()) {
            // 本地外壳：静态资源缓存 + /api 转发到 upstream（SSE 不缓冲），
            // 把页面放到同源 127.0.0.1 上，桌面端那套相对路径与 SW 都不必改。
            localServer = new LocalWebServer(this, upstream, token);
            String origin = localServer.start();
            if (origin == null) {
                localServer = null;
                url = intent.getStringExtra(EXTRA_URL);
            } else {
                // 页面自己会把 ?token= 存进 localStorage（桌面端 Web 端的行为），
                // 带上它免得页面以为没鉴权；真正的请求头仍由本地服务统一注入。
                url = origin + "/" + (token == null || token.isEmpty()
                        ? ""
                        : "?token=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8));
            }
        } else {
            url = intent == null ? null : intent.getStringExtra(EXTRA_URL);
        }
        if (url == null || url.isEmpty()) {
            Log.w(TAG, "既没有上游地址也没有 URL，直接结束");
            finish();
            return;
        }

        // 系统栏铺满：桌面 Web 端用 env(safe-area-inset-*) 自己让出刘海与手势条
        // （web.html 的 viewport 带 viewport-fit=cover），这里只需要别把它们涂成实色。
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 页面自己画背景；WebView 保持不透明，避免键盘弹出时露出系统底色的黑边
        webView.setBackgroundColor(Color.parseColor("#101014"));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true);
        // 桌面端 Web 端已经按 CSS 缩放适配手机，禁止 WebView 再二次缩放（会被当成 980px 视口）
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        // 桌面端用 ?token= 认 GET、Bearer 认写操作；页面自己把 token 存进 localStorage，
        // 所以这里只需允许 WebView 保存站点数据，不额外注入 cookie。
        CookieManager.getInstance().setAcceptCookie(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Log.i(TAG, "载入 " + url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri target = request.getUrl();
                String scheme = target.getScheme() == null ? "" : target.getScheme();
                // 站内导航留在 WebView；外链交给系统浏览器（否则没有退路）
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    String host = target.getHost() == null ? "" : target.getHost();
                    String currentHost = view.getUrl() == null ? "" : Uri.parse(view.getUrl()).getHost();
                    if (host.equals(currentHost)) return false;
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, target));
                    } catch (Exception error) {
                        Log.w(TAG, "打开外链失败: " + error);
                    }
                    return true;
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    android.webkit.ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams params) {
                return handleFileChooser(filePathCallback, params);
            }
        });

        setContentView(webView);
        webView.loadUrl(url);
    }

    /**
     * 图片附件（桌面 Web 端用 `<input type=file>`）：交给系统文件选择器。
     * 只让选图片，和桌面端 `MAX_ATTACHED_IMAGES` 的行为对齐（多选由页面自己限制）。
     */
    private boolean handleFileChooser(
            android.webkit.ValueCallback<Uri[]> callback,
            @Nullable WebChromeClient.FileChooserParams params) {
        if (callback == null) return false;
        pendingChooser = params;
        pendingChooserCallback = callback;
        Intent intent;
        try {
            intent = params == null ? null : params.createIntent();
        } catch (Exception error) {
            intent = null;
        }
        if (intent == null) {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "选择文件"), REQUEST_FILE_CHOOSER);
            return true;
        } catch (Exception error) {
            Log.w(TAG, "打开文件选择器失败: " + error);
            pendingChooserCallback = null;
            pendingChooser = null;
            callback.onReceiveValue(null);
            return false;
        }
    }

    private android.webkit.ValueCallback<Uri[]> pendingChooserCallback;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_FILE_CHOOSER) {
            android.webkit.ValueCallback<Uri[]> callback = pendingChooserCallback;
            pendingChooserCallback = null;
            pendingChooser = null;
            if (callback == null) return;
            Uri[] result = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            }
            callback.onReceiveValue(result);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (localServer != null) {
            localServer.stop();
            localServer = null;
        }
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    /** 供插件启动：单例入口，避免每处都拼 intent。 */
    static Intent intentFor(
            android.content.Context context,
            String url,
            String upstream,
            String token,
            String title) {
        Intent intent = new Intent(context, DesktopWebActivity.class);
        if (url != null) intent.putExtra(EXTRA_URL, url);
        if (upstream != null) intent.putExtra(EXTRA_UPSTREAM, upstream);
        if (token != null) intent.putExtra(EXTRA_TOKEN, token);
        intent.putExtra(EXTRA_TITLE, title);
        return intent;
    }
}
