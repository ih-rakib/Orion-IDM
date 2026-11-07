package com.rakib.orionidm;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in browser for Orion IDM.
 *
 * Browse a site, then either:
 *  - tap a file link / download button on the page (WebView download
 *    listener catches it, including Content-Disposition downloads), or
 *  - tap "Grab download links" to scan the page for downloadable files
 *    and pick one.
 * The chosen link is handed back to MainActivity, which starts the
 * fast download with the page's cookies and user-agent.
 */
public class BrowserActivity extends Activity {

    private WebView web;
    private EditText addressBar;
    private ProgressBar progress;

    private static final String FILE_EXT =
            "(zip|rar|7z|tar|gz|pdf|apk|mp4|mkv|avi|mp3|wav|flac|doc|docx|xls|xlsx|ppt|pptx|jpg|jpeg|png|gif|webp|txt|csv|iso|bin)";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);

        web = findViewById(R.id.web);
        addressBar = findViewById(R.id.addressBar);
        progress = findViewById(R.id.progress);
        Button goBtn = findViewById(R.id.goBtn);
        Button grabBtn = findViewById(R.id.grabBtn);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, Bitmap fav) {
                addressBar.setText(url);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url != null && url.startsWith("fb://")) {
                    String web1 = MainActivity.normalizeSharedUrl(url);
                    if (web1.startsWith("http") && !web1.equals(v.getUrl())) v.loadUrl(web1);
                    return true;
                }
                if (url != null && !url.startsWith("http://") && !url.startsWith("https://")) {
                    return true;
                }
                if (isFileUrl(url)) {
                    handBack(url, URLUtil.guessFileName(url, null, null));
                    return true;
                }
                return false;
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String url, String userAgent,
                                                  String contentDisposition,
                                                  String mimeType, long contentLength) {
                if (url != null && url.startsWith("blob:")) {
                    toast("This site streams in a protected way (blob) - cannot download directly");
                    return;
                }
                handBack(url, URLUtil.guessFileName(url, contentDisposition, mimeType));
            }
        });

        View.OnClickListener go = v -> loadTyped();
        goBtn.setOnClickListener(go);
        addressBar.setOnEditorActionListener((v, actionId, event) -> { loadTyped(); return true; });

        grabBtn.setOnClickListener(v -> grabLinks());

        String start = getIntent().getStringExtra("startUrl");
        web.loadUrl(start == null || start.isEmpty() ? "https://www.google.com" : start);
    }

    private void loadTyped() {
        String t = addressBar.getText().toString().trim();
        if (t.isEmpty()) return;
        if (!t.startsWith("http://") && !t.startsWith("https://")) {
            if (t.contains(".") && !t.contains(" ")) t = "https://" + t;
            else t = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t);
        }
        web.loadUrl(t);
    }

    private boolean isFileUrl(String url) {
        if (url == null) return false;
        String clean = url.split("[?#]")[0].toLowerCase();
        int dot = clean.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = clean.substring(dot + 1);
        return ext.matches(FILE_EXT);
    }

    private void grabLinks() {
        String js = "(function(){var out=[];var seen={};"
                + "function add(h){if(h && !seen[h] && !h.startsWith('blob:')){seen[h]=1;out.push(h);}}"
                + "var as=document.querySelectorAll('a[href]');"
                + "for(var i=0;i<as.length;i++){var h=as[i].href;"
                + "if(/\\." + FILE_EXT + "(\\?|#|$)/i.test(h))add(h);}"
                + "var vs=document.querySelectorAll('video,video source,audio,audio source');"
                + "for(var j=0;j<vs.length;j++){add(vs[j].currentSrc||vs[j].src);}"
                + "var ms=document.querySelectorAll('meta');"
                + "for(var k=0;k<ms.length;k++){var pr=ms[k].getAttribute('property')||'';"
                + "if(pr.indexOf('og:video')===0||pr.indexOf('og:audio')===0)add(ms[k].getAttribute('content'));}"
                + "return JSON.stringify(out);})()";
        web.evaluateJavascript(js, value -> {
            List<String> links = new ArrayList<>();
            try {
                Object parsed = new JSONTokener(value).nextValue();
                JSONArray arr = new JSONArray(parsed.toString());
                for (int i = 0; i < arr.length(); i++) links.add(arr.getString(i));
            } catch (Exception ignored) {}
            if (links.isEmpty()) {
                toast("No download links found on this page");
                return;
            }
            String[] labels = new String[links.size()];
            for (int i = 0; i < links.size(); i++) {
                String fn = URLUtil.guessFileName(links.get(i), null, null);
                labels[i] = (fn == null || fn.isEmpty() ? links.get(i) : fn);
            }
            new AlertDialog.Builder(this)
                    .setTitle(links.size() + " download link(s) found")
                    .setItems(labels, (d, which) ->
                            handBack(links.get(which), labels[which]))
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private void handBack(String url, String filename) {
        String ua = web.getSettings().getUserAgentString();
        String cookie = CookieManager.getInstance().getCookie(url);
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("url", url);
        i.putExtra("name", filename == null ? "" : filename);
        i.putExtra("ua", ua == null ? "" : ua);
        i.putExtra("cookie", cookie == null ? "" : cookie);
        i.putExtra("autostart", true);
        i.putExtra("referer", web.getUrl() == null ? url : web.getUrl());
        startActivity(i);
        finish();
        toast("Download starting: " + filename);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
