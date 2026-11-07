package com.rakib.orionidm;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.MimeTypeMap;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Fetches a media URL from inside the page's own WebView session.
 *
 * Some sites (notably Facebook's CDN) refuse downloads from a plain HTTP
 * client even with the right cookies/UA/Referer, while the very same URL
 * plays fine in the WebView - the request has to come from the browser's
 * own network stack. This class runs fetch() in the page, streams the
 * bytes back through a JS bridge in small chunks, then saves the file to
 * Downloads and records it in the same Store the download engine uses.
 */
public class WebFetcher {

    public interface Listener {
        void onProgress(long done, long total);
        void onDone(String savedName);
        void onError(String message);
    }

    private final Activity activity;
    private final WebView web;
    private final Listener listener;

    private File tmpFile;
    private OutputStream out;
    private long downloaded;
    private boolean finished;
    private String srcUrl;
    private String targetName;
    private boolean audioOnly;

    public WebFetcher(Activity activity, WebView web, Listener listener) {
        this.activity = activity;
        this.web = web;
        this.listener = listener;
    }

    public void start(String url, String name, boolean audioOnly) {
        this.srcUrl = url;
        this.audioOnly = audioOnly;
        this.targetName = MainActivity.sanitizeName(name, audioOnly);
        this.downloaded = 0;
        this.finished = false;
        tmpFile = new File(activity.getCacheDir(), "webfetch.bin");
        //noinspection ResultOfMethodCallIgnored
        tmpFile.delete();
        web.addJavascriptInterface(new Bridge(), "OrionFetch");
        String js = "(function(){"
                + "var url=" + JSONObject.quote(url) + ";"
                + "function enc(bytes){var s='';var CH=32768;"
                + "for(var i=0;i<bytes.length;i+=CH){"
                + "s+=String.fromCharCode.apply(null, bytes.subarray(i,i+CH));}"
                + "return btoa(s);}"
                + "fetch(url,{credentials:'include'}).then(function(r){"
                + "if(!r.ok){OrionFetch.err('HTTP '+r.status);return;}"
                + "OrionFetch.start(parseInt(r.headers.get('Content-Length')||'0',10));"
                + "var rd=r.body.getReader();"
                + "(function pump(){rd.read().then(function(res){"
                + "if(res.done){OrionFetch.done();return;}"
                + "OrionFetch.chunk(enc(res.value));pump();"
                + "}).catch(function(e){OrionFetch.err('read: '+e);});})();"
                + "}).catch(function(e){OrionFetch.err('fetch: '+e);});"
                + "})()";
        activity.runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private class Bridge {
        @JavascriptInterface
        public void start(long total) {
            try {
                out = new FileOutputStream(tmpFile);
            } catch (IOException e) {
                err("open: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void chunk(String b64) {
            if (out == null || finished) return;
            try {
                byte[] b = Base64.decode(b64, Base64.DEFAULT);
                out.write(b);
                downloaded += b.length;
                listener.onProgress(downloaded, -1);
            } catch (Exception e) {
                err("write: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void done() {
            if (finished) return;
            finished = true;
            new Thread(() -> finishSave()).start();
        }

        @JavascriptInterface
        public void err(String msg) {
            if (finished) return;
            finished = true;
            closeQuietly();
            activity.runOnUiThread(() -> listener.onError(msg));
        }
    }

    private void finishSave() {
        closeQuietly();
        try {
            File src = tmpFile;
            if (audioOnly) {
                File audio = new File(activity.getCacheDir(), "webfetch_audio.m4a");
                if (!AudioExtractor.extract(tmpFile, audio)) {
                    activity.runOnUiThread(() ->
                            listener.onError("No audio track could be extracted"));
                    return;
                }
                src = audio;
            }
            String uri = publish(src, targetName);
            Store.addCompleted(activity, srcUrl, targetName, src.length(), uri, audioOnly);
            activity.runOnUiThread(() -> listener.onDone(targetName));
        } catch (Exception e) {
            activity.runOnUiThread(() ->
                    listener.onError("save: " + e.getMessage()));
        }
    }

    private void closeQuietly() {
        if (out != null) {
            try { out.close(); } catch (IOException ignored) {}
            out = null;
        }
    }

    private String publish(File file, String name) throws IOException {
        String mime = guessMime(name);
        String candidate = name;
        for (int attempt = 0; attempt < 20; attempt++) {
            if (attempt > 0) candidate = withSuffix(name, attempt);
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentResolver cr = activity.getContentResolver();
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, candidate);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) continue;
                    OutputStream o = cr.openOutputStream(uri);
                    if (o == null) continue;
                    copy(file, o);
                    o.close();
                    return uri.toString();
                } else {
                    File dest = new File(Environment
                            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            candidate);
                    if (dest.exists()) continue;
                    OutputStream o = new FileOutputStream(dest);
                    copy(file, o);
                    o.close();
                    return Uri.fromFile(dest).toString();
                }
            } catch (RuntimeException e) {
                if (attempt == 19) throw new IOException("Could not save into Downloads");
            }
        }
        throw new IOException("Could not create file in Downloads");
    }

    private String withSuffix(String name, int n) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && name.length() - dot <= 6) {
            return name.substring(0, dot) + " (" + n + ")" + name.substring(dot);
        }
        return name + " (" + n + ")";
    }

    private String guessMime(String name) {
        String ext = null;
        int dot = name.lastIndexOf('.');
        if (dot > 0 && name.length() - dot <= 6) ext = name.substring(dot + 1).toLowerCase();
        String mime = ext == null ? null
                : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime == null && "m4a".equals(ext)) mime = "audio/mp4";
        return mime == null ? "application/octet-stream" : mime;
    }

    private void copy(File in, OutputStream out) throws IOException {
        InputStream is = new FileInputStream(in);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
        is.close();
        out.flush();
    }
}
