package com.financialflow.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * «Финансовый поток» — нативная обёртка (WebView) для веб-интерфейса.
 * Устройство как у Планера:
 *
 * ГИБРИД + ЕДИНОЕ ХРАНИЛИЩЕ: главная страница всегда открывается под адресом
 * GitHub Pages. shouldInterceptRequest берёт её из сети, а без сети отдаёт
 * офлайн-копию из assets ПОД ТЕМ ЖЕ АДРЕСОМ — localStorage один и тот же.
 *
 * EDGE-TO-EDGE: WebView под статусбаром, в index.html зона закрыта
 * через env(safe-area-inset-top).
 *
 * МОСТ ДЛЯ КОПИИ (window.AndroidBridge):
 *   shareBackup(json, filename)     — системный «Поделиться» (Telegram, Диск, почта)
 *   saveToDownloads(json, filename) — сохранить в «Загрузки»
 *   pickBackupFile()                — выбрать файл копии для восстановления
 * Ответы приходят в window.onNativeBackupResult / window.onNativeImportResult.
 */
public class MainActivity extends Activity {

    private static final String TAG = "FlowMain";
    private static final int REQ_PICK_BACKUP = 2001;

    private static final String PAGES_HOST = "yuriilosiev-png.github.io";
    private static final String PAGES_URL =
        "https://" + PAGES_HOST + BuildConfig.PAGES_PATH + "index.html";

    private WebView webView;
    private ProgressBar loader;
    private volatile boolean retriedMainFrame = false;
    private boolean pageReady = false;
    /** Файл из «Поделиться», пришедший раньше, чем загрузилась страница. */
    private Uri pendingImportUri = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ---- EDGE-TO-EDGE ----
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | (Build.VERSION.SDK_INT >= 23 ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0));
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        int bg = Color.parseColor("#dfe2e8");
        webView = new WebView(this);
        webView.setBackgroundColor(bg);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(bg);
        root.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        loader = new ProgressBar(this);
        loader.setIndeterminate(true);
        int sz = (int) (44 * getResources().getDisplayMetrics().density);
        root.addView(loader, new FrameLayout.LayoutParams(sz, sz, Gravity.CENTER));
        setContentView(root);

        // ---- КЛАВИАТУРА ----
        // При edge-to-edge adjustResize не ужимает окно: клавиатура легла бы поверх
        // полей ввода внизу экрана. Ужимаем сами: нижний отступ = высота клавиатуры.
        final View content = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            boolean open = insets.isVisible(WindowInsetsCompat.Type.ime());
            int kb = open ? insets.getInsets(WindowInsetsCompat.Type.ime()).bottom : 0;
            if (v.getPaddingBottom() != kb) v.setPadding(0, 0, 0, kb);
            return insets;
        });

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(isOnline() ? WebSettings.LOAD_NO_CACHE : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setUserAgentString(s.getUserAgentString() + " FinancialFlowApp/" + appVersionCode());

        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                hideLoader();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                hideLoader();
                pageReady = true;
                if (pendingImportUri != null) {
                    final Uri u = pendingImportUri;
                    pendingImportUri = null;
                    view.postDelayed(() -> readBackupUri(u), 400);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                // главную страницу отдаёт перехват; если и он не смог — один повтор
                if (request != null && request.isForMainFrame()
                        && isPagesIndex(request.getUrl()) && !retriedMainFrame) {
                    retriedMainFrame = true;
                    view.post(() -> view.loadUrl(PAGES_URL));
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request == null || !request.isForMainFrame()) return null;
                if (!"GET".equalsIgnoreCase(request.getMethod())) return null;
                if (!isPagesIndex(request.getUrl())) return null;
                return loadIndexNetworkFirst();
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        if (isOnline()) {
            try { webView.clearCache(true); } catch (Exception e) { Log.w(TAG, "clearCache", e); }
        }
        webView.loadUrl(PAGES_URL);
        handleIncomingFile(getIntent());
    }

    private void hideLoader() {
        if (loader != null && loader.getVisibility() != View.GONE) loader.setVisibility(View.GONE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingFile(intent);
    }

    /** Файл копии из «Поделиться» (SEND) или тапа в Файлах (VIEW). */
    private void handleIncomingFile(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri uri = null;
        try {
            if (Intent.ACTION_SEND.equals(action)) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            else if (Intent.ACTION_VIEW.equals(action)) uri = intent.getData();
        } catch (Exception e) {
            Log.e(TAG, "handleIncomingFile", e);
        }
        if (uri == null) return;
        intent.setAction(null);
        intent.setData(null);
        intent.removeExtra(Intent.EXTRA_STREAM);
        if (pageReady) readBackupUri(uri); else pendingImportUri = uri;
    }

    private boolean isPagesIndex(Uri u) {
        if (u == null || !PAGES_HOST.equalsIgnoreCase(u.getHost())) return false;
        String path = u.getPath();
        String p = BuildConfig.PAGES_PATH;   // "/financial-flow/"
        return (p + "index.html").equals(path) || p.equals(path)
            || p.substring(0, p.length() - 1).equals(path);
    }

    /** Сеть → свежий index.html с Pages; не вышло → офлайн-копия из assets.
     *  Вызывается на фоновом потоке WebView. */
    private WebResourceResponse loadIndexNetworkFirst() {
        if (isOnline()) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(PAGES_URL).openConnection();
                c.setConnectTimeout(3000);
                c.setReadTimeout(5000);
                c.setUseCaches(false);
                c.setRequestProperty("Cache-Control", "no-cache");
                if (c.getResponseCode() == 200) {
                    InputStream is = c.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                    is.close();
                    return new WebResourceResponse("text/html", "utf-8",
                        new ByteArrayInputStream(bos.toByteArray()));
                }
                Log.w(TAG, "Pages HTTP " + c.getResponseCode() + ", serving offline copy");
            } catch (Exception e) {
                Log.w(TAG, "Pages fetch failed, serving offline copy", e);
            } finally {
                if (c != null) c.disconnect();
            }
        }
        try {
            return new WebResourceResponse("text/html", "utf-8", getAssets().open("index.html"));
        } catch (Exception e) {
            Log.e(TAG, "offline copy missing", e);
            return null;
        }
    }

    private void callJs(final String js) {
        if (webView == null) return;
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= 23) {
                Network n = cm.getActiveNetwork();
                if (n == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } else {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                return ni != null && ni.isConnected();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static String jsEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    int appVersionCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return (int) pi.getLongVersionCode();
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // AndroidBridge — JS-интерфейс. В JS вызывать только с проверкой
    // typeof AndroidBridge !== 'undefined'.
    // ─────────────────────────────────────────────────────────────────
    public static class AndroidBridge {
        private final WeakReference<MainActivity> ref;

        AndroidBridge(MainActivity a) { ref = new WeakReference<>(a); }

        @JavascriptInterface
        public boolean hasNativeBackup() { return true; }

        @JavascriptInterface
        public int getAppVersionCode() {
            MainActivity a = ref.get();
            return a == null ? 0 : a.appVersionCode();
        }

        @JavascriptInterface
        public void shareBackup(final String json, final String filename) {
            final MainActivity a = ref.get();
            if (a != null) a.runOnUiThread(() -> a.doShareBackup(json, filename));
        }

        @JavascriptInterface
        public void saveToDownloads(final String json, final String filename) {
            final MainActivity a = ref.get();
            if (a != null) a.runOnUiThread(() -> a.doSaveToDownloads(json, filename));
        }

        @JavascriptInterface
        public void pickBackupFile() {
            final MainActivity a = ref.get();
            if (a != null) a.runOnUiThread(a::doPickBackupFile);
        }
    }

    // ---- «Поделиться»: файл в cache/backups → FileProvider → системный выбор ----
    private void doShareBackup(String json, String filename) {
        try {
            if (filename == null || filename.trim().isEmpty()) filename = "flow-backup.json";
            File dir = new File(getCacheDir(), "backups");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, filename);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(json.getBytes(StandardCharsets.UTF_8));
            fos.close();
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/json");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, filename);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Куда отправить копию"));
            callJs("window.onNativeBackupResult&&onNativeBackupResult('share','ok','')");
        } catch (Exception e) {
            Log.e(TAG, "shareBackup", e);
            callJs("window.onNativeBackupResult&&onNativeBackupResult('share','error','" + jsEscape(e.getMessage()) + "')");
        }
    }

    // ---- «Загрузки»: Android 10+ через MediaStore, ниже — прямая запись ----
    private void doSaveToDownloads(String json, String filename) {
        try {
            if (filename == null || filename.trim().isEmpty()) filename = "flow-backup.json";
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("MediaStore insert failed");
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) throw new Exception("openOutputStream failed");
                os.write(data);
                os.close();
                cv.clear();
                cv.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(uri, cv, null, null);
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                FileOutputStream fos = new FileOutputStream(new File(dir, filename));
                fos.write(data);
                fos.close();
            }
            callJs("window.onNativeBackupResult&&onNativeBackupResult('save','ok','" + jsEscape(filename) + "')");
        } catch (Exception e) {
            Log.e(TAG, "saveToDownloads", e);
            callJs("window.onNativeBackupResult&&onNativeBackupResult('save','error','" + jsEscape(e.getMessage()) + "')");
        }
    }

    // ---- Восстановление: системный выбор файла → JSON в JS (base64) ----
    private void doPickBackupFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/json", "text/plain", "application/octet-stream", "*/*"});
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents", "primary:Download"));
                } catch (Exception ignored) {}
            }
            startActivityForResult(Intent.createChooser(i, "Откуда взять копию?"), REQ_PICK_BACKUP);
        } catch (Exception e) {
            try {
                Intent g = new Intent(Intent.ACTION_GET_CONTENT);
                g.addCategory(Intent.CATEGORY_OPENABLE);
                g.setType("*/*");
                startActivityForResult(Intent.createChooser(g, "Откуда взять копию?"), REQ_PICK_BACKUP);
            } catch (ActivityNotFoundException e2) {
                callJs("window.onNativeImportResult&&onNativeImportResult('error','" + jsEscape(e2.getMessage()) + "','')");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_BACKUP) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            callJs("window.onNativeImportResult&&onNativeImportResult('cancel','','')");
            return;
        }
        readBackupUri(data.getData());
    }

    private void readBackupUri(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) throw new Exception("openInputStream failed");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = is.read(buf)) != -1) {
                total += n;
                if (total > 20 * 1024 * 1024) throw new Exception("Файл слишком большой");
                bos.write(buf, 0, n);
            }
            is.close();
            String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            callJs("window.onNativeImportResult&&onNativeImportResult('ok','','" + b64 + "')");
        } catch (Exception e) {
            Log.e(TAG, "readBackupUri", e);
            callJs("window.onNativeImportResult&&onNativeImportResult('error','" + jsEscape(e.getMessage()) + "','')");
        }
    }

    @Override
    public void onBackPressed() {
        // «назад» сначала спрашивает страницу: закрыть окно или вернуться на экран выше
        webView.evaluateJavascript("window.onNativeBack?onNativeBack():false", value -> {
            if (!"true".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onResume() { super.onResume(); webView.onResume(); }

    @Override
    protected void onPause() { super.onPause(); webView.onPause(); }

    @Override
    protected void onDestroy() { super.onDestroy(); webView.destroy(); }
}
