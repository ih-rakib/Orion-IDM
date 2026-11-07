package com.rakib.orionidm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Orion IDM fast download engine.
 *
 * - Multi-connection downloads (up to 4 parallel Range connections) when the
 *   server supports HTTP ranges, exactly the IDM trick.
 * - Real pause / resume: partial segment files are kept, resume continues
 *   from the bytes already on disk.
 * - Live speed: progress (bytes + bytes/s) is written to the Store about
 *   twice a second; the UI and the notification both read it.
 * - One download runs at a time; the rest wait as "queued".
 * - Finished files are published into the public Downloads folder
 *   (MediaStore on Android 10+, direct file copy below that).
 */
public class DownloadService extends Service {

    public static final String ACTION_WAKE   = "com.rakib.orionidm.WAKE";
    public static final String ACTION_PAUSE  = "com.rakib.orionidm.PAUSE";
    public static final String ACTION_RESUME = "com.rakib.orionidm.RESUME";
    public static final String ACTION_CANCEL = "com.rakib.orionidm.CANCEL";
    public static final String EXTRA_ID = "id";

    private static final String CHANNEL = "orion_downloads";
    private static final int FG_ID = 7;

    private static final Set<Long> pauseFlags = new HashSet<>();
    private static final Set<Long> cancelFlags = new HashSet<>();

    private Thread worker;
    private long activeId = -1;
    private String curReferer = "";
    private volatile boolean rangesLied = false;

    // ---------------------------------------------------------------

    public static void wake(Context ctx) {
        start(ctx, ACTION_WAKE, -1);
    }

    public static void control(Context ctx, String action, long id) {
        synchronized (pauseFlags) {
            if (ACTION_PAUSE.equals(action)) pauseFlags.add(id);
            if (ACTION_RESUME.equals(action)) pauseFlags.remove(id);
        }
        synchronized (cancelFlags) {
            if (ACTION_CANCEL.equals(action)) cancelFlags.add(id);
        }
        start(ctx, action, id);
    }

    private static void start(Context ctx, String action, long id) {
        Intent i = new Intent(ctx, DownloadService.class);
        i.setAction(action);
        i.putExtra(EXTRA_ID, id);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    private boolean pauseRequested(long id) {
        synchronized (pauseFlags) { return pauseFlags.contains(id); }
    }

    private boolean cancelRequested(long id) {
        synchronized (cancelFlags) { return cancelFlags.contains(id); }
    }

    // ---------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(this::workLoop, "orion-dl-worker");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------------------------------------------------------

    private void workLoop() {
        // Show a foreground notification immediately so Android does not
        // kill the service before the first download starts.
        startForeground(FG_ID, buildNotification("Orion IDM", "Starting…", -1, ""));
        while (true) {
            JSONObject next = null;
            List<JSONObject> all = Store.all(this);
            for (JSONObject o : all) {
                String s = o.optString("status");
                if ("queued".equals(s) || "running".equals(s)) { next = o; break; }
            }
            if (next == null) break;
            activeId = next.optLong("id");
            try {
                process(next);
            } catch (Exception e) {
                setStatus(activeId, "failed", e.getMessage());
            }
            activeId = -1;
        }
        stopForeground(true);
        stopSelf();
    }

    // ---------------------------------------------------------------

    private void process(JSONObject entry) throws Exception {
        long id = entry.optLong("id");
        String url = entry.optString("url");
        String name = entry.optString("name");
        String ua = entry.optString("ua");
        String cookie = entry.optString("cookie");
        curReferer = entry.optString("referer", "");
        if (curReferer.isEmpty()
                && (url.contains("fbcdn") || url.contains("scontent")
                    || url.contains("facebook.com"))) {
            // Facebook's CDN refuses media requests without a Facebook
            // Referer (browsers send it automatically; a raw downloader
            // must add it or it gets HTTP 403).
            curReferer = "https://www.facebook.com/";
        }
        File dir = new File(getFilesDir(), "parts/" + id);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();

        setField(id, "status", "running");

        // ---- Probe: total size + range support ----------------------
        long total = entry.optLong("total", -1);
        boolean ranges = false;
        if (total < 0) {
            HttpURLConnection probe = open(url, ua, cookie, "bytes=0-0");
            int code = probe.getResponseCode();
            if (code == 206) {
                ranges = true;
                String cr = probe.getHeaderField("Content-Range");
                if (cr != null && cr.contains("/")) {
                    try { total = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim()); }
                    catch (NumberFormatException ignored) {}
                }
            } else if (code == 200) {
                total = probe.getContentLengthLong();
                String ar = probe.getHeaderField("Accept-Ranges");
                ranges = ar != null && ar.contains("bytes");
            } else if (code == 416) {
                // Range not satisfiable - usually a zero-byte or odd file.
                String cr = probe.getHeaderField("Content-Range");
                if (cr != null && cr.contains("/")) {
                    try { total = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim()); }
                    catch (NumberFormatException ignored) {}
                }
                ranges = false;
            } else {
                probe.disconnect();
                throw new IOException(friendlyHttp(code));
            }
            probe.disconnect();
            setField(id, "total", total);
        } else {
            // Resuming a known download: assume ranges if it had parts > 1
            ranges = entry.optInt("parts", 1) > 1;
            if (!ranges) {
                // Cheap re-check for single-file resume support
                HttpURLConnection probe = open(url, ua, cookie, "bytes=0-0");
                ranges = probe.getResponseCode() == 206;
                probe.disconnect();
            }
        }

        int parts = 1;
        if (ranges && total >= 2_000_000) parts = 4;
        else if (ranges && total >= 500_000) parts = 2;
        rangesLied = false;
        setField(id, "parts", parts);

        File full = new File(dir, "full.bin");
        boolean ok;
        if (parts > 1) {
            ok = downloadMulti(id, url, ua, cookie, dir, total, parts);
            if (!ok && rangesLied && !pauseRequested(id) && !cancelRequested(id)) {
                // The server claimed Range support but answered a segment
                // with a full 200 response. Fall back to a single stream.
                for (int k = 0; k < parts; k++) new File(dir, "seg" + k).delete();
                setField(id, "parts", 1);
                ok = downloadSingle(id, url, ua, cookie, dir, false);
            }
        }
        else ok = downloadSingle(id, url, ua, cookie, dir, ranges);

        if (cancelRequested(id)) {
            synchronized (cancelFlags) { cancelFlags.remove(id); }
            deleteDir(dir);
            Store.remove(this, id);
            return;
        }
        if (pauseRequested(id)) {
            synchronized (pauseFlags) { pauseFlags.remove(id); }
            setField(id, "speed", 0);
            setStatus(id, "paused", null);
            notifyFinal(entry, "Paused");
            return;
        }
        if (!ok) {
            setField(id, "speed", 0);
            if (!"failed".equals(Store.get(this, id).optString("status"))) {
                setStatus(id, "failed", "Connection dropped - resume to continue");
            }
            notifyFinal(entry, "Failed - tap Resume in the app");
            return;
        }

        if (parts == 1 && total > 0 && new File(dir, "file.part").length() != total) {
            setField(id, "speed", 0);
            setStatus(id, "failed", "Incomplete download - tap Resume to continue");
            notifyFinal(entry, "Incomplete - tap Resume in the app");
            return;
        }

        // ---- Optional: audio-only (extract the audio track) ----------
        File assembled = parts > 1 ? mergeParts(dir, parts, full) : new File(dir, "file.part");
        if (entry.optInt("audioOnly", 0) == 1) {
            File audioOut = new File(dir, "audio.m4a");
            setStatus(id, "running", null);
            notifyFinal(entry, "Extracting audio…");
            if (!AudioExtractor.extract(assembled, audioOut)) {
                setField(id, "speed", 0);
                setStatus(id, "failed", "Audio extraction failed (no usable audio track)");
                notifyFinal(entry, "Audio extraction failed");
                return;
            }
            assembled = audioOut;
        }

        // ---- Publish into the public Downloads folder ----------------
        String uri = publish(assembled, name);
        setField(id, "finalUri", uri);
        JSONObject done = Store.get(this, id);
        if (done != null) setField(id, "done", done.optLong("total", -1) > 0
                ? done.optLong("total") : assembled.length());
        setField(id, "speed", 0);
        setStatus(id, "done", null);
        deleteDir(dir);
        notifyFinal(entry, "Saved to Downloads");
    }

    // ---------------------------------------------------------------

    private boolean downloadSingle(long id, String url, String ua, String cookie,
                                   File dir, boolean ranges) {
        File part = new File(dir, "file.part");
        long existing = part.exists() ? part.length() : 0;
        try {
            String range = (existing > 0 && ranges) ? "bytes=" + existing + "-" : null;
            HttpURLConnection conn = open(url, ua, cookie, range);
            int code = conn.getResponseCode();
            boolean append = existing > 0 && code == 206;
            if (!append) existing = 0;
            if (code != 200 && code != 206) { conn.disconnect(); fail(id, friendlyHttp(code)); return false; }
            InputStream in = conn.getInputStream();
            OutputStream out = new FileOutputStream(part, append);
            byte[] buf = new byte[64 * 1024];
            long done = existing;
            long lastBytes = done;
            long lastTime = System.currentTimeMillis();
            int n;
            while ((n = in.read(buf)) != -1) {
                if (pauseRequested(id) || cancelRequested(id)) {
                    out.flush(); out.close(); in.close(); conn.disconnect();
                    progress(id, done, 0);
                    return true; // handled by caller via flags
                }
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastTime >= 500) {
                    long speed = (done - lastBytes) * 1000 / Math.max(now - lastTime, 1);
                    progress(id, done, speed);
                    lastBytes = done; lastTime = now;
                }
            }
            out.flush(); out.close(); in.close(); conn.disconnect();
            progress(id, done, 0);
            return true;
        } catch (IOException e) {
            fail(id, e.getMessage());
            return false;
        }
    }

    private boolean downloadMulti(long id, String url, String ua, String cookie,
                                  File dir, long total, int parts) {
        long segLen = total / parts;
        Thread[] threads = new Thread[parts];
        final boolean[] segOk = new boolean[parts];
        for (int k = 0; k < parts; k++) {
            final int idx = k;
            final long start = idx * segLen;
            final long end = (idx == parts - 1) ? total - 1 : start + segLen - 1;
            threads[k] = new Thread(() -> {
                File seg = new File(dir, "seg" + idx);
                long have = seg.exists() ? seg.length() : 0;
                long want = end - start + 1;
                if (have > want) { seg.delete(); have = 0; }
                if (have == want) { segOk[idx] = true; return; }
                try {
                    HttpURLConnection conn = open(url, ua, cookie,
                            "bytes=" + (start + have) + "-" + end);
                    int scode = conn.getResponseCode();
                    if (scode == 200) rangesLied = true;
                    if (scode != 206) { conn.disconnect(); return; }
                    InputStream in = conn.getInputStream();
                    OutputStream out = new FileOutputStream(seg, true);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (pauseRequested(id) || cancelRequested(id)) break;
                        out.write(buf, 0, n);
                    }
                    out.flush(); out.close(); in.close(); conn.disconnect();
                    segOk[idx] = seg.length() == want;
                } catch (IOException ignored) {}
            }, "orion-seg-" + idx);
            threads[k].start();
        }
        // Progress updater while segments run
        long lastBytes = currentBytes(dir, parts, true);
        long lastTime = System.currentTimeMillis();
        boolean anyAlive = true;
        while (anyAlive) {
            anyAlive = false;
            for (Thread t : threads) if (t.isAlive()) { anyAlive = true; break; }
            long now = System.currentTimeMillis();
            if (now - lastTime >= 500) {
                long cur = currentBytes(dir, parts, true);
                long speed = (cur - lastBytes) * 1000 / Math.max(now - lastTime, 1);
                progress(id, cur, speed);
                lastBytes = cur; lastTime = now;
            }
            try { Thread.sleep(120); } catch (InterruptedException ignored) {}
        }
        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException ignored) {}
        }
        progress(id, currentBytes(dir, parts, true), 0);
        if (pauseRequested(id) || cancelRequested(id)) return true; // caller handles flags
        for (boolean b : segOk) if (!b) { fail(id, "A connection dropped - resume to continue"); return false; }
        return true;
    }

    private long currentBytes(File dir, int parts, boolean segments) {
        long sum = 0;
        if (segments) {
            for (int k = 0; k < parts; k++) {
                File f = new File(dir, "seg" + k);
                if (f.exists()) sum += f.length();
            }
        }
        return sum;
    }

    private File mergeParts(File dir, int parts, File full) throws IOException {
        OutputStream out = new FileOutputStream(full);
        byte[] buf = new byte[128 * 1024];
        for (int k = 0; k < parts; k++) {
            FileInputStream in = new FileInputStream(new File(dir, "seg" + k));
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
        }
        out.flush(); out.close();
        return full;
    }

    // ---------------------------------------------------------------

    private String publish(File file, String name) throws IOException {
        String mime = guessMime(name);
        String candidate = name;
        for (int attempt = 0; attempt < 20; attempt++) {
            if (attempt > 0) candidate = withSuffix(name, attempt);
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentResolver cr = getContentResolver();
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, candidate);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) continue;
                    OutputStream out = cr.openOutputStream(uri);
                    if (out == null) continue;
                    copy(file, out);
                    out.close();
                    return uri.toString();
                } else {
                    File dest = new File(Environment
                            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            candidate);
                    if (dest.exists()) continue;
                    OutputStream out = new FileOutputStream(dest);
                    copy(file, out);
                    out.close();
                    return Uri.fromFile(dest).toString();
                }
            } catch (RuntimeException e) {
                // Name already taken on this device - try the next suffix.
                if (attempt == 19) throw new IOException("Could not save into Downloads: " + e.getMessage());
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

    private void copy(File file, OutputStream out) throws IOException {
        FileInputStream in = new FileInputStream(file);
        byte[] buf = new byte[128 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        out.flush();
    }

    private String guessMime(String name) {
        String ext = null;
        if (name != null) {
            String clean = name.split("[?#]")[0];
            int dot = clean.lastIndexOf('.');
            int slash = clean.lastIndexOf('/');
            if (dot > slash && dot >= 0 && clean.length() - dot <= 6) {
                ext = clean.substring(dot + 1).toLowerCase();
            }
        }
        String mime = ext == null ? null
                : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime == null && "m4a".equals(ext)) mime = "audio/mp4";
        return mime == null ? "application/octet-stream" : mime;
    }

    // ---------------------------------------------------------------

    static final String DEFAULT_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Mobile Safari/537.36";

    /** Opens a connection and follows redirects manually - the built-in
     *  follower refuses http<->https hops, which media CDNs use a lot. */
    private HttpURLConnection open(String url, String ua, String cookie, String range)
            throws IOException {
        String current = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection conn = rawOpen(current, ua, cookie, range);
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) return rawOpen(current, ua, cookie, range);
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            return conn;
        }
        return rawOpen(current, ua, cookie, range);
    }

    private HttpURLConnection rawOpen(String url, String ua, String cookie, String range)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent",
                ua == null || ua.isEmpty() ? DEFAULT_UA : ua);
        // Never let a proxy/server gzip the body: it corrupts Range offsets.
        conn.setRequestProperty("Accept-Encoding", "identity");
        if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
        if (curReferer != null && !curReferer.isEmpty()) {
            conn.setRequestProperty("Referer", curReferer);
        }
        conn.setRequestProperty("Accept", "*/*");
        if (range != null) conn.setRequestProperty("Range", range);
        return conn;
    }

    // ---------------------------------------------------------------

    private void progress(long id, long done, long speed) {
        JSONObject o = Store.get(this, id);
        if (o == null) return;
        try {
            o.put("done", done);
            o.put("speed", speed);
        } catch (Exception ignored) {}
        Store.put(this, o);
        long total = o.optLong("total", -1);
        int pct = total > 0 ? (int) (done * 100 / total) : -1;
        String text = (pct >= 0 ? pct + "%  •  " : "")
                + human(done) + (total > 0 ? " / " + human(total) : "")
                + (speed > 0 ? "  •  " + human(speed) + "/s" : "");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(FG_ID, buildNotification(o.optString("name"), text, pct, text));
    }

    private void notifyFinal(JSONObject entry, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify((int) (entry.optLong("id") % 100000) + 100,
                    buildNotification(entry.optString("name"), text, -1, text));
        }
    }

    private Notification buildNotification(String title, String text, int pct, String big) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(pct >= 0);
        if (pct >= 0) b.setProgress(100, pct, false);
        return b.build();
    }

    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }

    // ---------------------------------------------------------------

    private void setField(long id, String key, Object value) {
        JSONObject o = Store.get(this, id);
        if (o == null) return;
        try {
            if (value instanceof Long) o.put(key, (Long) value);
            else if (value instanceof Integer) o.put(key, (Integer) value);
            else o.put(key, value);
        } catch (Exception ignored) {}
        Store.put(this, o);
    }

    private void setStatus(long id, String status, String error) {
        JSONObject o = Store.get(this, id);
        if (o == null) return;
        try {
            o.put("status", status);
            if (error != null) o.put("error", error);
        } catch (Exception ignored) {}
        Store.put(this, o);
    }

    static String friendlyHttp(int code) {
        switch (code) {
            case 403: return "Server refused (403) - the link may have expired; share it again";
            case 404: return "File not found (404)";
            case 410: return "Link expired (410) - share it again";
            case 416: return "Server range error (416)";
            case 429: return "Server is busy (429) - try again in a bit";
            default:
                if (code >= 500) return "Server error (" + code + ") - try again later";
                return "Server answered HTTP " + code;
        }
    }

    private void fail(long id, String msg) {
        setStatus(id, "failed", msg == null ? "Error" : msg);
    }

    private void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
}
