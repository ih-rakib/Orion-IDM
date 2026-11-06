package com.rakib.orionidm;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Orion IDM - MVP.
 *
 * A tiny IDM-style download manager built on Android's system DownloadManager:
 *  - paste or share a direct file link
 *  - background downloads with system notification progress
 *  - automatic pause/resume on network loss (handled by the system downloader)
 *  - queue: add as many links as you like, they download in order added
 *  - history list: tap a finished file to open it, long-press to remove it
 */
public class MainActivity extends Activity {

    private EditText urlInput;
    private EditText nameInput;
    private ListView listView;
    private ArrayAdapter<String> adapter;
    private final List<String> rows = new ArrayList<>();
    private final List<Long> rowIds = new ArrayList<>();

    private DownloadManager dm;
    private SharedPreferences prefs;
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

        dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        prefs = getSharedPreferences("history", MODE_PRIVATE);

        urlInput = findViewById(R.id.urlInput);
        nameInput = findViewById(R.id.nameInput);
        listView = findViewById(R.id.list);
        Button pasteBtn = findViewById(R.id.pasteBtn);
        Button downloadBtn = findViewById(R.id.downloadBtn);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        listView.setAdapter(adapter);

        // Auto-fill the file name from the link while the name box is empty.
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
                } else {
                    toast("Clipboard is empty");
                }
            } else {
                toast("Clipboard is empty");
            }
        });

        downloadBtn.setOnClickListener(v -> startDownload());

        listView.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos < rowIds.size()) openDownload(rowIds.get(pos));
        });

        listView.setOnItemLongClickListener((parent, view, pos, id) -> {
            if (pos < rowIds.size()) {
                long dlId = rowIds.get(pos);
                new AlertDialog.Builder(this)
                        .setTitle("Remove download?")
                        .setMessage("This removes the entry and deletes the downloaded file.")
                        .setPositiveButton("Remove", (d, w) -> {
                            dm.remove(dlId);
                            removeId(dlId);
                            refreshList();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            }
            return false;
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
        String shared = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())
                && "text/plain".equals(intent.getType())) {
            CharSequence t = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (t != null) shared = findUrl(t.toString());
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null) shared = data.toString();
        }
        if (shared != null && !shared.isEmpty()) {
            urlInput.setText(shared);
            String guess = guessFileName(shared);
            if (!guess.isEmpty()) nameInput.setText(guess);
            toast("Link received - tap Download");
        }
    }

    private void startDownload() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) { toast("Paste a link first"); return; }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("Link must start with http:// or https://");
            return;
        }
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) name = guessFileName(url);
        if (name.isEmpty()) name = "download.bin";
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");

        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(name);
            req.setDescription(url);
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            req.setAllowedOverMetered(true);
            req.setAllowedOverRoaming(true);
            String ext = MimeTypeMap.getFileExtensionFromUrl(url);
            if (ext != null) {
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
                if (mime != null) req.setMimeType(mime);
            }
            long id = dm.enqueue(req);
            saveId(id);
            toast("Download started: " + name);
            urlInput.setText("");
            nameInput.setText("");
            refreshList();
        } catch (Exception e) {
            toast("Could not start: " + e.getMessage());
        }
    }

    private void openDownload(long id) {
        try {
            DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
            Cursor c = dm.query(q);
            int status = -1;
            if (c != null) {
                if (c.moveToFirst()) {
                    status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                }
                c.close();
            }
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                toast("Not finished yet");
                return;
            }
            Uri uri = dm.getUriForDownloadedFile(id);
            if (uri == null) { toast("File not found (was it deleted?)"); return; }
            Intent open = new Intent(Intent.ACTION_VIEW);
            open.setDataAndType(uri, dm.getMimeTypeForDownloadedFile(id));
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(open);
        } catch (Exception e) {
            toast("No app can open this file");
        }
    }

    // ------------------------------------------------------------------

    private void refreshList() {
        List<Long> ids = loadIds();
        rows.clear();
        rowIds.clear();
        for (long id : ids) {
            rows.add(describe(id));
            rowIds.add(id);
        }
        if (rows.isEmpty()) rows.add("No downloads yet");
        adapter.notifyDataSetChanged();
    }

    private String describe(long id) {
        DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
        Cursor c = dm.query(q);
        String out;
        if (c == null || !c.moveToFirst()) {
            out = "Download #" + id + " - record gone";
            if (c != null) c.close();
            return out;
        }
        String title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
        int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        long done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
        long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
        c.close();
        if (title == null || title.isEmpty()) title = "file";

        String size = total > 0
                ? String.format("%.1f / %.1f MB  (%d%%)", done / 1048576.0, total / 1048576.0,
                        (int) (done * 100 / Math.max(total, 1)))
                : String.format("%.1f MB", done / 1048576.0);

        String state;
        switch (status) {
            case DownloadManager.STATUS_RUNNING:    state = "Downloading"; break;
            case DownloadManager.STATUS_PAUSED:     state = "Paused (will auto-resume)"; break;
            case DownloadManager.STATUS_PENDING:    state = "Waiting"; break;
            case DownloadManager.STATUS_SUCCESSFUL: state = "Done - tap to open"; break;
            case DownloadManager.STATUS_FAILED:     state = "Failed - long-press to remove, then retry"; break;
            default:                                state = "Unknown"; break;
        }
        return title + "\n" + size + "  -  " + state;
    }

    // ------------------------------------------------------------------

    private void saveId(long id) {
        List<Long> ids = loadIds();
        ids.add(0, id);
        while (ids.size() > 100) ids.remove(ids.size() - 1);
        writeIds(ids);
    }

    private void removeId(long id) {
        List<Long> ids = loadIds();
        ids.remove(Long.valueOf(id));
        writeIds(ids);
    }

    private List<Long> loadIds() {
        List<Long> ids = new ArrayList<>();
        String raw = prefs.getString("ids", "");
        if (raw != null && !raw.isEmpty()) {
            for (String p : raw.split(",")) {
                try { ids.add(Long.parseLong(p.trim())); } catch (NumberFormatException ignored) {}
            }
        }
        return ids;
    }

    private void writeIds(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        prefs.edit().putString("ids", sb.toString()).apply();
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
        for (String word : text.split("\\s+")) {
            if (word.startsWith("http://") || word.startsWith("https://")) return word;
        }
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

    // Referenced so the compiler keeps the import list honest on older toolchains.
    @SuppressWarnings("unused")
    private ClipData unusedClip() { return null; }

    // Keep View import used (listener signatures reference it in lambdas on some compilers).
    @SuppressWarnings("unused")
    private View unusedView() { return null; }
}
