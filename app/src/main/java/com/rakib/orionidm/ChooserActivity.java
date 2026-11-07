package com.rakib.orionidm;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Format chooser for page links (Facebook, Instagram, news sites, ...).
 *
 * Loads the shared page in a WebView, then scans the loaded page for
 * playable media: og:video meta tags, <video> elements, and the stream
 * URLs social sites embed in their page data (Facebook's playable_url /
 * browser_native_hd_url / sd_src / hd_src and similar). The user picks:
 *
 *   - a Video stream (downloaded as-is), or
 *   - "Audio only" - the smallest video stream is downloaded and its
 *     audio track is extracted to an .m4a file by the download engine, or
 *   - an original audio stream, when the page has one.
 *
 * If nothing is found (login wall, protected streams), the user can open
 * the page in the built-in browser, log in there, and grab from inside.
 */
public class ChooserActivity extends Activity {

    private WebView web;
    private TextView status;
    private ProgressBar progress;
    private ListView listView;
    private ArrayAdapter<String> adapter;
    private final List<String> labels = new ArrayList<>();
    private final List<Choice> choices = new ArrayList<>();
    private String pageUrl;
    private String pageTitle = "";
    private boolean scanned = false;
    private final List<String> candidates = new ArrayList<>();
    private int candIdx = 0;

    private static class Choice {
        String kind;   // video | audio | audioFromVideo
        String url;
        Choice(String kind, String url) { this.kind = kind; this.url = url; }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chooser);

        pageUrl = MainActivity.normalizeSharedUrl(getIntent().getStringExtra("url"));
        if (pageUrl == null || pageUrl.isEmpty()) { finish(); return; }
        candidates.add(pageUrl);
        String fbId = MainActivity.facebookId(pageUrl);
        if (fbId != null) {
            String reel = "https://www.facebook.com/reel/" + fbId;
            String mob = "https://m.facebook.com/watch/?v=" + fbId;
            if (!candidates.contains(reel)) candidates.add(reel);
            if (!candidates.contains(mob)) candidates.add(mob);
        }

        web = findViewById(R.id.web);
        status = findViewById(R.id.status);
        progress = findViewById(R.id.progress);
        listView = findViewById(R.id.list);
        Button browserBtn = findViewById(R.id.browserBtn);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < choices.size()) pick(choices.get(pos));
        });

        browserBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, BrowserActivity.class);
            i.putExtra("startUrl", pageUrl);
            startActivity(i);
            finish();
        });

        WebSettings s = web.getSettings();
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 13; Pixel 7) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/120.0.0.0 Mobile Safari/537.36");
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url == null) return false;
                if (url.startsWith("fb://")) {
                    // Facebook pages try to bounce the browser into the FB app
                    // with an fb:// link. Convert it back to the web address
                    // for the same video instead of dying on an error page.
                    String web1 = MainActivity.normalizeSharedUrl(url);
                    if (web1.startsWith("http") && !web1.equals(v.getUrl())) {
                        v.loadUrl(web1);
                    }
                    return true;
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return true; // tel:, intent:, fb-messenger: etc. - ignore
                }
                return false;
            }
            @Override public void onPageFinished(WebView v, String url) {
                pageTitle = v.getTitle() == null ? "" : v.getTitle();
                // Give late JS a moment to inject the player data, then scan.
                new Handler().postDelayed(() -> scan(1), 1800);
            }
        });

        web.loadUrl(pageUrl);
    }

    // ---------------------------------------------------------------

    private void scan(int attempt) {
        if (scanned) return;
        String js = buildScanJs();
        web.evaluateJavascript(js, value -> {
            Map<String, String> videos = new LinkedHashMap<>(); // label -> url
            List<String> audios = new ArrayList<>();
            try {
                Object parsed = new JSONTokener(value).nextValue();
                JSONArray arr = new JSONArray(parsed.toString());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String label = o.optString("label");
                    String u = o.optString("url");
                    String kind = o.optString("kind");
                    if (u.isEmpty() || u.startsWith("blob:")) continue;
                    if ("audio".equals(kind)) {
                        if (!audios.contains(u)) audios.add(u);
                    } else {
                        if (!videos.containsValue(u)) videos.put(label, u);
                    }
                }
            } catch (Exception ignored) {}

            if (videos.isEmpty() && audios.isEmpty() && attempt < 2) {
                new Handler().postDelayed(() -> scan(attempt + 1), 2500);
                return;
            }
            scanned = true;
            status.setText("Checking available resolutions…");
            probeResolutions(videos, audios);
        });
    }

    private String buildScanJs() {
        return "(function(){"
                + "var out=[];var seen={};"
                + "function un(s){try{return JSON.parse('\"'+s.replace(/\"/g,'\\\\\"')+'\"');}catch(e){return s.replace(/\\\\\\//g,'/');}}"
                + "function add(label,url,kind){if(!url||seen[url])return;seen[url]=1;out.push({label:label,url:url,kind:kind||'video'});}"
                + "var metas=document.querySelectorAll('meta');"
                + "for(var i=0;i<metas.length;i++){var p=metas[i].getAttribute('property')||metas[i].getAttribute('name')||'';var c=metas[i].getAttribute('content')||'';"
                + "if(p==='og:video'||p==='og:video:url'||p==='og:video:secure_url'||p==='twitter:player:stream')add('Video (page)',c,'video');"
                + "if(p==='og:audio'||p==='og:audio:secure_url')add('Audio (page)',c,'audio');}"
                + "var vs=document.querySelectorAll('video');"
                + "for(var j=0;j<vs.length;j++){var src=vs[j].currentSrc||vs[j].src;if(src)add('Video (player)',src,'video');"
                + "var ss=vs[j].querySelectorAll('source');for(var k=0;k<ss.length;k++){if(ss[k].src)add('Video (player)',ss[k].src,'video');}}"
                + "var as=document.querySelectorAll('audio');"
                + "for(var a=0;a<as.length;a++){if(as[a].currentSrc||as[a].src)add('Audio (player)',as[a].currentSrc||as[a].src,'audio');}"
                + "var html=document.documentElement.innerHTML;"
                + "var keys=[['playable_url_quality_hd','Video HD'],['browser_native_hd_url','Video HD'],['hd_src','Video HD'],['playable_url','Video'],['browser_native_sd_url','Video SD'],['sd_src','Video SD'],['audio_url','Audio']];"
                + "for(var m=0;m<keys.length;m++){var key=keys[m][0];var re=new RegExp(key+'\\\\\\\\?\":\\\\\\\\?\"(https:[^\"]+)','g');var mm;"
                + "while((mm=re.exec(html))!==null){var u=un(mm[1]);add(keys[m][1],u,key==='audio_url'?'audio':'video');}}"
                + "var mp4re=/(https:[^\"\\'\\s]+\\.mp4[^\"\\'\\s]*)/g;"
                + "while((mm=mp4re.exec(html))!==null){add('Video',un(mm[1]),'video');}"
                + "return JSON.stringify(out);})()";
    }

    // ---------------------------------------------------------------

    private int sortKey(Map.Entry<String, String> e, Map<String, int[]> resMap) {
        int[] wh = resMap.get(e.getValue());
        if (wh != null && wh[1] > 0) return wh[1];
        if (e.getKey().contains("HD")) return 719;
        if (e.getKey().contains("SD")) return 360;
        return 480;
    }

    /** Probe each video stream's real resolution inside the page
     *  (loads only its metadata, not the video itself). */
    private void probeResolutions(Map<String, String> videos, List<String> audios) {
        if (videos.isEmpty()) { showResults(videos, audios, new java.util.HashMap<>()); return; }
        JSONArray urls = new JSONArray();
        for (String u : videos.values()) urls.put(u);
        String js = "(function(){var urls=" + urls.toString() + ";var res={};"
                + "var chain=Promise.resolve();"
                + "urls.forEach(function(u){chain=chain.then(function(){return new Promise(function(done){"
                + "var v=document.createElement('video');v.preload='metadata';"
                + "var t=setTimeout(function(){done()},4500);"
                + "v.onloadedmetadata=function(){clearTimeout(t);res[u]={w:v.videoWidth,h:v.videoHeight};done()};"
                + "v.onerror=function(){clearTimeout(t);done()};"
                + "v.src=u;});});});"
                + "return chain.then(function(){return JSON.stringify(res)});})()";
        web.evaluateJavascript(js, value -> {
            Map<String, int[]> resMap = new java.util.HashMap<>();
            try {
                Object parsed = new JSONTokener(value).nextValue();
                JSONObject obj = new JSONObject(parsed.toString());
                java.util.Iterator<String> it = obj.keys();
                while (it.hasNext()) {
                    String u = it.next();
                    JSONObject wh = obj.getJSONObject(u);
                    resMap.put(u, new int[]{wh.optInt("w"), wh.optInt("h")});
                }
            } catch (Exception ignored) {}
            showResults(videos, audios, resMap);
        });
    }

    private void showResults(Map<String, String> videos, List<String> audios,
                             Map<String, int[]> resMap) {
        progress.setVisibility(View.GONE);
        labels.clear();
        choices.clear();

        // Sort streams best-resolution first. Unknown heights fall back to
        // the HD/SD hint from the page so HD still sorts above SD.
        List<Map.Entry<String, String>> sorted = new ArrayList<>(videos.entrySet());
        sorted.sort((a, b) -> sortKey(b, resMap) - sortKey(a, resMap));

        String audioSource = null;
        int bestKey = Integer.MAX_VALUE;
        for (Map.Entry<String, String> e : sorted) {
            int[] wh = resMap.get(e.getValue());
            String label;
            if (wh != null && wh[1] > 0) {
                label = "Video " + wh[1] + "p  (" + wh[0] + "×" + wh[1] + ")";
            } else {
                label = e.getKey();
            }
            labels.add("🎬 " + label + "  —  download video");
            choices.add(new Choice("video", e.getValue()));
            int k = sortKey(e, resMap);
            if (k <= bestKey) { bestKey = k; audioSource = e.getValue(); }
        }
        for (String u : audios) {
            labels.add("🎵 Audio (original)  —  download audio");
            choices.add(new Choice("audio", u));
        }
        if (audioSource != null) {
            labels.add("🎵 Audio only  —  extract audio from the video (.m4a)");
            choices.add(new Choice("audioFromVideo", audioSource));
        }

        if (choices.isEmpty() && candIdx + 1 < candidates.size()) {
            candIdx++;
            scanned = false;
            progress.setVisibility(View.VISIBLE);
            status.setText("Trying another address for this video… ("
                    + (candIdx + 1) + "/" + candidates.size() + ")");
            web.loadUrl(candidates.get(candIdx));
            return;
        }
        if (choices.isEmpty()) {
            status.setText("No video/audio found on this page.\n"
                    + "Scanned: " + candidates.get(candIdx) + "\n"
                    + "The site may need you to log in first, or it protects its streams. "
                    + "Open it in the built-in browser below, log in / press play there, "
                    + "then use Grab links.");
        } else {
            status.setText("Choose a format:");
        }
        adapter.notifyDataSetChanged();
    }

    private void pick(Choice c) {
        String ua = web.getSettings().getUserAgentString();
        String cookie = CookieManager.getInstance().getCookie(pageUrl);
        if (cookie == null) cookie = "";
        String base = pageTitle;
        if (base == null || base.trim().isEmpty()) base = MainActivity.guessFileName(c.url);
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (base.isEmpty()) base = "media";

        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("url", c.url);
        i.putExtra("ua", ua == null ? "" : ua);
        i.putExtra("cookie", cookie);
        i.putExtra("autostart", true);
        String ref = web.getUrl();
        i.putExtra("referer", ref == null || ref.isEmpty() ? pageUrl : ref);
        if ("audioFromVideo".equals(c.kind)) {
            i.putExtra("audioOnly", true);
            i.putExtra("name", stripExt(base) + ".m4a");
            Toast.makeText(this, "Downloading video, then extracting audio…",
                    Toast.LENGTH_SHORT).show();
        } else if ("audio".equals(c.kind)) {
            String fn = MainActivity.guessFileName(c.url);
            i.putExtra("name", fn.isEmpty() ? base + ".mp3" : fn);
        } else {
            String fn = MainActivity.guessFileName(c.url);
            i.putExtra("name", fn.isEmpty() ? base + ".mp4" : fn);
        }
        startActivity(i);
        finish();
    }

    static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0 && name.length() - dot <= 5) ? name.substring(0, dot) : name;
    }
}
