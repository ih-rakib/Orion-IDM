package com.rakib.orionidm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.webkit.URLUtil;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Orion IDM - v2.
 *
 * Fast multi-connection downloads with real pause/resume and live speed
 * (DownloadService), a built-in browser that grabs download links from
 * pages (BrowserActivity), plus paste / share intake as in v1.
 *
 * List interactions: tap = pause / resume / open (depends on state),
 * long-press = remove.
 */
public class MainActivity extends Activity {

    private EditText urlInput;
    private EditText nameInput;
    private ListView listView;
    private ArrayAdapter<String> adapter;
    private final List<String> rows = new ArrayList<>();
    private final List<Long> rowIds = new ArrayList<>();

    private final Handler handler = new Handler();
    private boolean polling = false;
    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            refreshList();
            if (polling) handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.urlInput);
        nameInput = findViewById(R.id.nameInput);
        listView = findViewById(R.id.list);
        Button pasteBtn = findViewById(R.id.pasteBtn);
        Button downloadBtn = findViewById(R.id.downloadBtn);
        Button browserBtn = findViewById(R.id.browserBtn);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        listView.setAdapter(adapter);

        urlInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (nameInput.getText().toString().trim().isEmpty()) {
                    String guess = guessFileName(s.toString().trim());
                    if (!guess.isEmpty()) nameInput.setText(guess);
                }
            }
        });

        pasteBtn.setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip() != null
                    && cb.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = cb.getPrimaryClip().getItemAt(0).getText();
                if (text != null) {
                    urlInput.setText(text.toString().trim());
                    toast("Pasted");
                } else toast("Clipboard is empty");
            } else toast("Clipboard is empty");
        });

        downloadBtn.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            String name = nameInput.getText().toString().trim();
            String found = findUrl(url);
            if (found == null) { toast("Paste a link first"); return; }
            if (isVideoFile(found)) {
                // Choose the format before downloading
                new AlertDialog.Builder(this)
                        .setTitle("Choose format")
                        .setItems(new String[]{
                                "🎬 Video (original file)",
                                "🎵 Audio only (.m4a, extracted from the video)"},
                                (d, which) -> {
                                    if (enqueue(found, name, "", "", which == 1, "")) {
                                        urlInput.setText("");
                                        nameInput.setText("");
                                    }
                                })
                        .setNegativeButton("Cancel", null)
                        .show();
            } else if (!isDirectFile(found)) {
                // A page link (Facebook post, Instagram, ...) - find its formats
                openChooser(found);
            } else if (enqueue(found, name, "", "", false, "")) {
                urlInput.setText("");
                nameInput.setText("");
            }
        });

        browserBtn.setOnClickListener(v ->
                startActivity(new Intent(this, BrowserActivity.class)));

        listView.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos >= rowIds.size()) return;
            long dlId = rowIds.get(pos);
            JSONObject o = Store.get(this, dlId);
            if (o == null) return;
            String status = o.optString("status");
            if ("failed".equals(status) && !o.optString("debug").isEmpty()) {
                showFailedDialog(o);
                return;
            }
            switch (status) {
                case "running":
                case "queued":
                    setStatusLocal(o, "paused");
                    DownloadService.control(this, DownloadService.ACTION_PAUSE, dlId);
                    toast("Pausing…");
                    break;
                case "paused":
                case "failed":
                    setStatusLocal(o, "queued");
                    DownloadService.control(this, DownloadService.ACTION_RESUME, dlId);
                    DownloadService.wake(this);
                    toast("Resuming…");
                    break;
                case "done":
                    openDownload(o);
                    break;
                default:
                    break;
            }
            refreshList();
        });

        listView.setOnItemLongClickListener((parent, view, pos, id) -> {
            if (pos >= rowIds.size()) return true;
            long dlId = rowIds.get(pos);
            JSONObject o = Store.get(this, dlId);
            if (o == null) return true;
            new AlertDialog.Builder(this)
                    .setTitle("Remove download?")
                    .setMessage(o.optString("name")
                            + "\n\nRemoves the entry and deletes partial data."
                            + " A finished file already saved in Downloads is kept unless you pick 'Remove + delete file'.")
                    .setPositiveButton("Remove", (d, w) -> {
                        DownloadService.control(this, DownloadService.ACTION_CANCEL, dlId);
                        Store.remove(this, dlId);
                        refreshList();
                    })
                    .setNeutralButton("Remove + delete file", (d, w) -> {
                        DownloadService.control(this, DownloadService.ACTION_CANCEL, dlId);
                        String uri = o.optString("finalUri");
                        if (uri != null && !uri.isEmpty()) {
                            try {
                                getContentResolver().delete(Uri.parse(uri), null, null);
                            } catch (Exception ignored) {}
                        }
                        Store.remove(this, dlId);
                        refreshList();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });

        handleIncomingIntent(getIntent());
        askNotificationPermission();
        refreshList();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        polling = true;
        handler.post(pollTask);
        // Restart the engine if a previous run left something queued/running
        // (e.g. after a reboot or the service being killed).
        for (JSONObject o : Store.all(this)) {
            String s = o.optString("status");
            if ("queued".equals(s) || "running".equals(s)) {
                if ("running".equals(s)) setStatusLocal(o, "queued");
                DownloadService.wake(this);
                break;
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        polling = false;
        handler.removeCallbacks(pollTask);
    }

    // ------------------------------------------------------------------

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;

        // From the built-in browser: start right away with its cookies/UA.
        if (intent.getBooleanExtra("autostart", false)) {
            String url = intent.getStringExtra("url");
            if (url != null && !url.isEmpty()) {
                enqueue(url, intent.getStringExtra("name"),
                        intent.getStringExtra("ua"), intent.getStringExtra("cookie"),
                        intent.getBooleanExtra("audioOnly", false),
                        intent.getStringExtra("referer"));
                refreshList();
            }
            return;
        }

        String shared = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            CharSequence t = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null) shared = findUrl(t.toString());
            if (shared == null && intent.getClipData() != null
                    && intent.getClipData().getItemCount() > 0) {
                CharSequence ct = intent.getClipData().getItemAt(0).getText();
                if (ct != null) shared = findUrl(ct.toString());
            }
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null) shared = normalizeSharedUrl(data.toString());
        }
        if (shared != null && !shared.isEmpty()) {
            if (!isDirectFile(shared)) {
                // Page link (e.g. a Facebook share) - let the user pick a format
                openChooser(shared);
                return;
            }
            urlInput.setText(shared);
            String guess = guessFileName(shared);
            if (!guess.isEmpty()) nameInput.setText(guess);
            toast("Link received - tap Download");
        }
    }

    private boolean enqueue(String url, String name, String ua, String cookie,
                            boolean audioOnly, String referer) {
        if (url == null || url.trim().isEmpty()) { toast("Paste a link first"); return false; }
        url = findUrl(url.trim());
        if (url == null) { toast("No http(s) link found"); return false; }
        url = normalizeSharedUrl(url);
        if (!url.startsWith("http")) { toast("That link type is not supported"); return false; }
        if (name == null || name.trim().isEmpty()) name = guessFileName(url);
        if (name == null || name.isEmpty()) name = "download.bin";
        name = sanitizeName(name, audioOnly);
        Store.newEntry(this, url, name, ua, cookie, audioOnly, referer);
        DownloadService.wake(this);
        toast("Download queued: " + name);
        refreshList();
        return true;
    }

    private void openChooser(String url) {
        Intent i = new Intent(this, ChooserActivity.class);
        i.putExtra("url", normalizeSharedUrl(url));
        startActivity(i);
    }

    static boolean isDirectFile(String url) {
        return extOf(url) != null;
    }

    static boolean isVideoFile(String url) {
        String e = extOf(url);
        return e != null && (e.equals("mp4") || e.equals("mkv") || e.equals("webm")
                || e.equals("mov") || e.equals("avi") || e.equals("3gp"));
    }

    private static String extOf(String url) {
        if (url == null) return null;
        String clean = url.split("[?#]")[0].toLowerCase();
        int slash = clean.lastIndexOf('/');
        int dot = clean.lastIndexOf('.');
        if (dot < 0 || dot < slash || clean.length() - dot > 6) return null;
        String e = clean.substring(dot + 1);
        String[] known = {"zip","rar","7z","tar","gz","pdf","apk","mp4","mkv","webm","mov",
                "avi","3gp","mp3","m4a","wav","flac","ogg","doc","docx","xls","xlsx","ppt",
                "pptx","jpg","jpeg","png","gif","webp","txt","csv","iso","bin","dmg","exe","msi"};
        for (String k : known) if (k.equals(e)) return e;
        return null;
    }

    private void showFailedDialog(JSONObject o) {
        String debug = o.optString("debug");
        final String msg = o.optString("error") + "\n\n— Debug info —\n" + debug;
        new AlertDialog.Builder(this)
                .setTitle("Download failed")
                .setMessage(msg)
                .setPositiveButton("Resume", (d, w) -> {
                    setStatusLocal(o, "queued");
                    DownloadService.control(this, DownloadService.ACTION_RESUME, o.optLong("id"));
                    DownloadService.wake(this);
                    refreshList();
                })
                .setNeutralButton("Copy debug", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("Orion IDM debug",
                                "URL: " + o.optString("url") + "\n" + msg));
                    }
                    toast("Debug info copied - paste it in our chat");
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** Shared name cleanup: strip bad chars/newlines, cap the length, and
     *  swap the extension for audio-only downloads. */
    public static String sanitizeName(String name, boolean audioOnly) {
        if (name == null) name = "download";
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("[\\n\\r\\t]+", " ").trim();
        if (name.length() > 90) {
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.length() - dot <= 6) ext = name.substring(dot);
            name = name.substring(0, 90 - ext.length()) + ext;
        }
        if (audioOnly) name = ChooserActivity.stripExt(name) + ".m4a";
        return name;
    }

    private void openDownload(JSONObject o) {
        String uri = o.optString("finalUri");
        if (uri == null || uri.isEmpty()) { toast("File not found"); return; }
        try {
            Intent open = new Intent(Intent.ACTION_VIEW);
            open.setDataAndType(Uri.parse(uri), mimeFor(o.optString("name")));
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(open);
        } catch (Exception e) {
            toast("No app can open this file");
        }
    }

    private String mimeFor(String name) {
        String ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(name);
        String mime = ext == null ? null : android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext.toLowerCase());
        return mime == null ? "*/*" : mime;
    }

    // ------------------------------------------------------------------

    private void refreshList() {
        List<JSONObject> all = Store.all(this);
        rows.clear();
        rowIds.clear();
        for (JSONObject o : all) {
            rows.add(describe(o));
            rowIds.add(o.optLong("id"));
        }
        if (rows.isEmpty()) rows.add("No downloads yet");
        adapter.notifyDataSetChanged();
    }

    private String describe(JSONObject o) {
        String name = o.optString("name", "file");
        String status = o.optString("status", "");
        long done = o.optLong("done", 0);
        long total = o.optLong("total", -1);
        long speed = o.optLong("speed", 0);
        int parts = o.optInt("parts", 1);

        String size;
        if (total > 0) {
            size = DownloadService.human(done) + " / " + DownloadService.human(total)
                    + "  (" + (done * 100 / Math.max(total, 1)) + "%)";
        } else {
            size = DownloadService.human(done);
        }

        String state;
        switch (status) {
            case "running":
                state = "Downloading" + (parts > 1 ? "  ×" + parts + " connections" : "")
                        + (speed > 0 ? "  •  " + DownloadService.human(speed) + "/s" : "")
                        + "  - tap to pause";
                break;
            case "queued":  state = "Waiting…"; break;
            case "paused":  state = "Paused - tap to resume"; break;
            case "done":    state = "Done - tap to open"; break;
            case "failed":
                String err = o.optString("error", "");
                state = "Failed" + (err.isEmpty() ? "" : ": " + err) + " - tap to resume";
                break;
            default:        state = status; break;
        }
        return name + "\n" + size + "  -  " + state;
    }

    private void setStatusLocal(JSONObject o, String status) {
        try { o.put("status", status); } catch (Exception ignored) {}
        Store.put(this, o);
    }

    // ------------------------------------------------------------------

    static String guessFileName(String url) {
        String name = URLUtil.guessFileName(url, null, null);
        if (name == null) return "";
        try { name = URLDecoder.decode(name, "UTF-8"); }
        catch (UnsupportedEncodingException ignored) {}
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    static String findUrl(String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.startsWith("http://") || text.startsWith("https://")
                || text.startsWith("fb://") || text.startsWith("intent://")) {
            int sp = text.indexOf(' ');
            return trimTail(sp > 0 ? text.substring(0, sp) : text);
        }
        for (String word : text.split("\\s+")) {
            if (word.startsWith("http://") || word.startsWith("https://")
                    || word.startsWith("fb://") || word.startsWith("intent://")) {
                return trimTail(word);
            }
        }
        return null;
    }

    private static String trimTail(String u) {
        while (u.length() > 8) {
            char c = u.charAt(u.length() - 1);
            if (c == ')' || c == '.' || c == ',' || c == ';' || c == '!'
                    || c == '"' || c == '\'' || c == '>' || c == ']') {
                u = u.substring(0, u.length() - 1);
            } else break;
        }
        return u;
    }

    /**
     * Facebook's app often shares its internal deep link instead of a web
     * link, e.g. fb://fullscreen_video/1927124748252382?loop=false&wtsid=...
     * The long number is the video ID - turn it into the public watch URL.
     */
    static String normalizeSharedUrl(String url) {
        if (url == null) return null;
        url = url.trim();
        if (url.startsWith("intent://")) {
            // Chrome-style intent links carry the real address in the
            // browser_fallback_url parameter.
            int i = url.indexOf("S.browser_fallback_url=");
            if (i >= 0) {
                String rest = url.substring(i + "S.browser_fallback_url=".length());
                int semi = rest.indexOf(';');
                if (semi >= 0) rest = rest.substring(0, semi);
                try {
                    String decoded = URLDecoder.decode(rest, "UTF-8");
                    if (decoded.startsWith("http")) return decoded;
                } catch (Exception ignored) {}
            }
            return url;
        }
        if (url.startsWith("fb://")) {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("(\\d{6,})").matcher(url);
            if (m.find()) return "https://www.facebook.com/watch/?v=" + m.group(1);
            return url;
        }
        return url;
    }

    /** The video/post ID inside a Facebook URL, or null. */
    static String facebookId(String url) {
        if (url == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[?&]v=(\\d{6,})|/(?:reel|videos|watch)/(\\d{6,})")
                .matcher(url);
        if (m.find()) return m.group(1) != null ? m.group(1) : m.group(2);
        return null;
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
