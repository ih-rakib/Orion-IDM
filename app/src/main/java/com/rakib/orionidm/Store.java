package com.rakib.orionidm;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Tiny persistent store for download entries, backed by SharedPreferences.
 * Each entry is a JSONObject with fields:
 * id, url, name, ua, cookie, total, done, speed (bytes/s), parts (connections),
 * status (queued|running|paused|done|failed), finalUri, error
 */
public class Store {

    private static final String PREFS = "downloads_v2";
    private static final String KEY = "entries";

    public static synchronized List<JSONObject> all(Context ctx) {
        List<JSONObject> out = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY, "");
        if (raw != null && !raw.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) out.add(arr.getJSONObject(i));
            } catch (JSONException ignored) {}
        }
        return out;
    }

    public static synchronized JSONObject get(Context ctx, long id) {
        for (JSONObject o : all(ctx)) {
            if (o.optLong("id") == id) return o;
        }
        return null;
    }

    public static synchronized void put(Context ctx, JSONObject entry) {
        List<JSONObject> list = all(ctx);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).optLong("id") == entry.optLong("id")) {
                list.set(i, entry);
                replaced = true;
                break;
            }
        }
        if (!replaced) list.add(0, entry);
        while (list.size() > 100) list.remove(list.size() - 1);
        save(ctx, list);
    }

    public static synchronized void remove(Context ctx, long id) {
        List<JSONObject> list = all(ctx);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).optLong("id") == id) list.remove(i);
        }
        save(ctx, list);
    }

    public static synchronized long newEntry(Context ctx, String url, String name,
                                             String ua, String cookie, boolean audioOnly,
                                             String referer) {
        long id = System.currentTimeMillis();
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("url", url);
            o.put("name", name);
            o.put("ua", ua == null ? "" : ua);
            o.put("cookie", cookie == null ? "" : cookie);
            o.put("total", -1);
            o.put("done", 0);
            o.put("speed", 0);
            o.put("parts", 1);
            o.put("status", "queued");
            o.put("audioOnly", audioOnly ? 1 : 0);
            o.put("referer", referer == null ? "" : referer);
            o.put("finalUri", "");
            o.put("error", "");
        } catch (JSONException ignored) {}
        put(ctx, o);
        return id;
    }

    /** Records an already-finished download (used by the in-page fetcher). */
    public static synchronized long addCompleted(Context ctx, String url, String name,
                                                 long size, String uri, boolean audioOnly) {
        long id = System.currentTimeMillis();
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("url", url);
            o.put("name", name);
            o.put("ua", "");
            o.put("cookie", "");
            o.put("total", size);
            o.put("done", size);
            o.put("speed", 0);
            o.put("parts", 1);
            o.put("status", "done");
            o.put("audioOnly", audioOnly ? 1 : 0);
            o.put("referer", "");
            o.put("finalUri", uri == null ? "" : uri);
            o.put("error", "");
            o.put("debug", "");
        } catch (JSONException ignored) {}
        put(ctx, o);
        return id;
    }

    private static void save(Context ctx, List<JSONObject> list) {
        JSONArray arr = new JSONArray();
        for (JSONObject o : list) arr.put(o);
        prefs(ctx).edit().putString(KEY, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
