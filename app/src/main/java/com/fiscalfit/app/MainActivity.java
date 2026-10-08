package com.fiscalfit.app;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String JS_BACK =
        "(function(){var o=document.getElementById('ov');if(o&&o.classList.contains('on')){o.click();return 'k'}"
        + "var d=document.querySelector('.nv.on'),a=document.getElementById('app');"
        + "if(a&&!a.hidden&&d&&d.dataset.p!=='dash'){nav('dash');return 'k'}return 'x'})()";

    private WebView web;
    private ProgressBar bar;
    private ValueCallback<Uri[]> chooser;
    private String home;
    private String host;

    private final ActivityResultLauncher<Intent> pick = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), r -> {
            if (chooser == null) return;
            chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.getResultCode(), r.getData()));
            chooser = null;
        });

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        home = getString(R.string.app_url);
        host = Uri.parse(home).getHost();
        getWindow().setStatusBarColor(Color.parseColor("#9CCC3C"));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        root.addView(bar, new FrameLayout.LayoutParams(-1, (int) (4 * getResources().getDisplayMetrics().density), Gravity.TOP));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " FiscalFitApp/1.0");
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, false);
        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sc = u.getScheme();
                if (host.equals(u.getHost()) || "file".equals(sc) || "blob".equals(sc)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) v.loadUrl("file:///android_asset/offline.html");
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                CookieManager.getInstance().flush();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                bar.setProgress(p);
                bar.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = cb;
                try {
                    pick.launch(p.createIntent());
                } catch (Exception e) {
                    chooser = null;
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (url.startsWith("blob:")) {
                web.evaluateJavascript("(function(){var x=new XMLHttpRequest();x.open('GET','" + url
                    + "',true);x.responseType='blob';x.onload=function(){var r=new FileReader();"
                    + "r.onloadend=function(){Android.save(r.result)};r.readAsDataURL(x.response)};x.send()})()", null);
                return;
            }
            try {
                DownloadManager.Request q = new DownloadManager.Request(Uri.parse(url));
                String ck = CookieManager.getInstance().getCookie(url);
                if (ck != null) q.addRequestHeader("Cookie", ck);
                if (ua != null) q.addRequestHeader("User-Agent", ua);
                q.setMimeType(mime);
                q.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                q.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, cd, mime));
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(q);
                Toast.makeText(this, "Downloading to Downloads...", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show();
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                web.evaluateJavascript(JS_BACK, v -> {
                    if (v == null || v.contains("x") || v.equals("null")) finish();
                });
            }
        });

        web.loadUrl(home);
    }

    private void toast(String m) {
        runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
    }

    class Bridge {
        @JavascriptInterface
        public void save(String dataUrl) {
            try {
                int i = dataUrl.indexOf(',');
                String meta = dataUrl.substring(0, i);
                byte[] data = Base64.decode(dataUrl.substring(i + 1), Base64.DEFAULT);
                String mime = meta.substring(5, meta.indexOf(';'));
                String ext = mime.contains("pdf") ? "pdf" : mime.contains("csv") ? "csv" : "bin";
                String name = "FiscalFit-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + "." + ext;
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                    o.write(data);
                }
                toast("Saved to Downloads: " + name);
            } catch (Exception e) {
                toast("Download failed");
            }
        }

        @JavascriptInterface
        public void retry() {
            runOnUiThread(() -> web.loadUrl(home));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
