package dev.pideck.mobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

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

    /**
     * Android 16 起 `windowOptOutEdgeToEdgeEnforcement` 被移除，系统强制铺满，
     * 内容必然画到系统栏底下 —— 那种机型只能靠 insets 给 WebView 加 padding 兜住。
     */
    private static final boolean forcedEdgeToEdge = Build.VERSION.SDK_INT >= 36;
    /** 承载 WebView 的容器：系统栏占位加在这上面，而不是 WebView 自己身上。 */
    private FrameLayout contentRoot;
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

        // 系统栏：**内容不进栏底下**（见 AppTheme.WebShell 的 windowOptOutEdgeToEdgeEnforcement），
        // 栏的颜色由 applyBarTheme() 跟着页面底色同步，视觉上仍是连成一片的。
        //
        // 为什么不学 0.3.0 那样铺到栏底下、让页面自己让位：web.html 的确写了
        // `pt-[env(safe-area-inset-top)]`，可那是真浏览器（有 UI 框、会上报 safe-area）里才成立；
        // 裸 WebView 里 `env(safe-area-inset-*)` 恒为 0，抽屉（`fixed inset-y-0`）的顶栏和会话标题
        // 就顶进状态栏，连点都点不到。0.3.1 用给 WebView 加 padding 兜过，但内容只是「不画在那里」，
        // 栏底下那一带的触摸还是归系统 —— 用户要的是内容压根别上去。
        // 显式定死布局模式：opt-out 生效的机型（≤ Android 15）让系统把内容排在栏下面，
        // Android 16+ 没有 opt-out，只能铺满 + insets padding。
        WindowCompat.setDecorFitsSystemWindows(getWindow(), !forcedEdgeToEdge);
        getWindow().setStatusBarColor(Color.parseColor("#101014"));
        getWindow().setNavigationBarColor(Color.parseColor("#101014"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 兜底：让页面内容的顶边永远不高于「状态栏底边」。
        //
        // 靠 theme 的 opt-out / setDecorFitsSystemWindows 决定布局模式，靠 insets 决定要不要 padding ——
        // 这两条都可能落空（ROM 忽略 opt-out、insets 在 decor-fit 时恒为 0），一旦落空页面顶边就回到
        // 屏幕 0 行，桌面 Web 端的顶栏（y≈9dp 的汉堡按钮）正好骑在状态栏下面：看不见也点不到。
        // 所以再加一层实测：WebView 在屏幕上的 y 坐标 < 状态栏高度，就补足这段 padding。

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
            public void onPageFinished(WebView view, String url) {
                // 系统栏跟着页面主题走：页面底色是浅的就要深色图标，反之亦然。
                view.evaluateJavascript(THEME_PROBE, null);
            }

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

        contentRoot = new FrameLayout(this);
        contentRoot.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(contentRoot);
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot, (view, insets) -> {
            applyBarInsets(insets);
            return insets;
        });
        // 布局完成后再量一次：insets 首次派发时容器还没排好，位置读不到
        contentRoot.post(() -> applyBarInsets(ViewCompat.getRootWindowInsets(contentRoot)));
        webView.addJavascriptInterface(new ShellBridge(), "PiDeckShell");
        webView.loadUrl(url);
    }

    /**
     * 页面把「当前是深色还是浅色 + 底色」回传过来，系统栏图标与 WebView 底色跟着变。
     *
     * 为什么不能写死：桌面 Web 端主题跟桌面端设置（也可能是跟随系统），
     * 而 `AppTheme.WebShell` 的静态样式是「深色一套」 —— 浅色主题下会变成白底白图标。
     */
    private final class ShellBridge {
        @JavascriptInterface
        public void setTheme(final boolean dark, final String background) {
            new Handler(Looper.getMainLooper()).post(() -> applyBarTheme(dark, background));
        }
    }

    private void applyBarTheme(boolean dark, String background) {
        int background_int = parseColor(background, dark ? Color.parseColor("#101014") : Color.WHITE);
        // 容器与窗口底色一起改：padding 区 / 回弹区露的是窗口底色，
        // 不跟着页面走就会在输入条下面留一条与主题无关的黑边。
        if (contentRoot != null) contentRoot.setBackgroundColor(background_int);
        getWindow().setBackgroundDrawable(new ColorDrawable(background_int));
        if (webView != null) {
            // 页面自己铺满，但回弹 / 缩放时露出的是 WebView 底色，用同样的色才不跳
            webView.setBackgroundColor(background_int);
        }
        Window window = getWindow();
        // 两条栏跟着页面底色：栏是不透明的（内容不在它底下，点了也不会点到状态栏），
        // 但颜色一样，看起来仍是一整块。API 35 退出强制后 statusBarColor 依旧生效。
        try {
            window.setStatusBarColor(background_int);
            window.setNavigationBarColor(background_int);
        } catch (Exception error) {
            Log.w(TAG, "设置系统栏颜色失败: " + error);
        }
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(!dark);
        controller.setAppearanceLightNavigationBars(!dark);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
    }

    /**
     * 系统栏占位：加在**容器**的 padding 上，让 WebView 本身变矮变窄 —— 绝不能加在 WebView 身上。
     *
     * 给 WebView 加 padding 时，页面里的 `100dvh` / 固定定位仍按整块 WebView 计算，内容会被
     * 裁掉一截（Android 17 上实测就是「输入框越界」）；而容器 padding 换来的是真正的视口缩小，
     * 桌面 Web 端自己的 `h-[100dvh]` 与输入条就自然落在栏之间。
     *
     * 取值：系统 insets 与「状态栏高度 - 容器在屏幕上的 y」二者取大（ROM 忽略 opt-out、
     * insets 又回 0 时靠后者兜住）；底部取 `max(手势条, 键盘)`。
     */
    private void applyBarInsets(@Nullable WindowInsetsCompat insets) {
        if (contentRoot == null) return;
        int systemBars = WindowInsetsCompat.Type.systemBars();

        int[] screenPos = new int[2];
        contentRoot.getLocationOnScreen(screenPos);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int statusBar = systemDimen("status_bar_height");
        int navBar = systemDimen("navigation_bar_height");

        // 以**实测位置**为准：容器已经在状态栏下面（decor-fit 生效）时，insets 仍可能报一个
        // 非 0 的顶值 —— 那就再加一次，顶栏凭空下移一大截（实测：0.3.4 顶栏上面多出半屏空白）。
        // 实测为 0 就不加，只在容器顶边真的压到状态栏里时才补。
        int measuredTop = Math.max(0, statusBar - screenPos[1]);
        int top = measuredTop == 0 ? 0 : Math.max(measuredTop,
                insets == null ? 0 : insets.getInsets(systemBars | WindowInsetsCompat.Type.displayCutout()).top);

        // 底部一律不补：这个 ROM 上 `navigation_bar_height` 与窗口实际height对不上，
        // 任何反推都会多补一条黑边（0.3.5 实测：输入条下面多出 ~50dp 黑带）。
        // 手势条压住输入条 8dp 是可接受的，页面自己的内边距已经留了余量。
        final int bottom = 0;

        if (contentRoot.getPaddingTop() != top || contentRoot.getPaddingBottom() != bottom) {
            contentRoot.setPadding(0, top, 0, bottom);
        }
    }

    /** 系统资源里的栏高（`status_bar_height` 之类不是公开 API，取不到就当 0）。 */
    private int systemDimen(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id == 0 ? 0 : getResources().getDimensionPixelSize(id);
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        // 旋转 / 分屏后位置全变了，重新量一次（insets 不一定会再发）
        if (contentRoot != null) {
            contentRoot.post(() -> applyBarInsets(ViewCompat.getRootWindowInsets(contentRoot)));
        }
    }

    /** 接受 `#rrggbb` 与 `rgb()/rgba()`（页面取到的是 computed style）。 */
    private static int parseColor(String value, int fallback) {
        if (value == null) return fallback;
        String text = value.trim();
        if (text.isEmpty() || "transparent".equals(text) || text.startsWith("rgba(0, 0, 0, 0")) return fallback;
        try {
            if (text.startsWith("#")) return Color.parseColor(text);
            int open = text.indexOf('(');
            int close = text.indexOf(')');
            if (open > 0 && close > open) {
                String[] parts = text.substring(open + 1, close).split(",");
                if (parts.length >= 3) {
                    int r = (int) Double.parseDouble(parts[0].trim());
                    int g = (int) Double.parseDouble(parts[1].trim());
                    int b = (int) Double.parseDouble(parts[2].trim());
                    double a = parts.length >= 4 ? Double.parseDouble(parts[3].trim()) : 1d;
                    if (a <= 0.01) return fallback;
                    return Color.argb((int) Math.round(a * 255), r, g, b);
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /**
     * 注入的探测脚本：读页面底色/主题，回报一次；之后监听主题切换（不靠定时器刷）。
     * 页面不一定有 `data-theme`（桌面 Web 端有自己的主题来源），所以以计算后的背景色为准。
     */
    private static final String THEME_PROBE =
            "(function(){"
            + "if(window.__pideckShellWatch)return;"
            + "window.__pideckShellWatch=true;"
            + "function pick(){var el=document.body||document.documentElement;"
            + "var bg=getComputedStyle(el).backgroundColor;"
            + "if(!bg||bg==='transparent'||bg.indexOf('rgba(0, 0, 0, 0)')===0){bg=getComputedStyle(document.documentElement).backgroundColor;}"
            + "return bg;}"
            + "function report(){try{var bg=pick();var m=bg&&bg.match(/rgba?\\((\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)/);"
            + "var dark=true;"
            + "if(m){dark=(0.299*(+m[1])+0.587*(+m[2])+0.114*(+m[3]))<140;}"
            + "else{var t=(document.documentElement.getAttribute('data-theme')||'');dark=t.indexOf('dark')>=0;}"
            + "if(window.PiDeckShell&&window.PiDeckShell.setTheme)window.PiDeckShell.setTheme(dark,bg||'');"
            + "}catch(e){}}"
            + "report();"
            + "try{new MutationObserver(report).observe(document.documentElement,{attributes:true,attributeFilter:['data-theme','class','style']});}catch(e){}"
            + "try{new MutationObserver(report).observe(document.body,{attributes:true,attributeFilter:['class','style']});}catch(e){}"
            + "try{window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change',report);}catch(e){}"
            + "document.addEventListener('visibilitychange',report);"
            + "})();";

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
