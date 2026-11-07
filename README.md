# Orion-IDM

**Orion IDM** — an IDM-style download manager for Android. Version 2.6.

Paste or share a **direct file link**, and it downloads in the background with
progress, using Android's system DownloadManager.

## Features

- **Fast engine (v2):** multi-connection downloads — up to 4 parallel HTTP
  Range connections when the server supports them, the classic IDM trick
- **Live speed** in the list and in the notification (MB/s, %, MB done/total)
- **Real pause / resume:** partial segment files are kept, resume continues
  where it stopped — even after the app is closed
- **Built-in browser (v2):** browse a page, tap a file link or the
  "Grab download links" button to pick a file from the page; the download
  carries the site's cookies and user-agent, so logged-in downloads work
- **Proper app icon (v2):** adaptive launcher icon (download arrow + orbit dot)
- **Format chooser (v2.1):** sharing a page link (e.g. from the Facebook app)
  opens a chooser before anything downloads. It scans the loaded page for
  playable streams (og:video, <video> elements, Facebook's embedded
  playable_url / hd / sd stream data), probes each stream's real resolution
  from its metadata, and lists them best-first — e.g. "Video 720p", "Video SD"
  — plus **Audio only**: the smallest video stream is downloaded and its audio
  track is extracted to `.m4a` on-device (`AudioExtractor`, MediaCodec /
  MediaMuxer remux or transcode; stock Android has no MP3 encoder, and M4A/AAC
  plays everywhere). Direct video file links also get a Video / Audio choice.
  If a page hides its streams (login wall), the chooser offers the built-in
  browser instead.
- **Facebook deep-link fix (v2.2):** the Facebook app often shares an
  internal `fb://fullscreen_video/<id>` link instead of a web URL. Orion IDM
  now extracts the video ID and converts it to the public
  `facebook.com/watch/?v=<id>` page, and the chooser automatically tries the
  reel and mobile-watch addresses too if the first page hides its streams.
  The page scan also catches any direct `.mp4` URL embedded in the page.
- **Redirect guard (v2.3):** a real-phone retest showed Facebook's *web* page
  itself bounces the in-app browser to an `fb://` app link (which then dies
  with "Webpage not available"). The chooser and built-in browser now
  intercept any `fb://` navigation, convert it back to the watch page for
  the same video, and ignore other non-web schemes. The main screen shows
  the app version, and the chooser's empty-result message names the exact
  address it scanned, so test screenshots are self-diagnosing.
- **HTTP 403 fix (v2.4):** the next real-phone test got further — the chooser
  found the streams and the user picked Audio only, but Facebook's CDN
  answered the download itself with 403. Browsers send a `Referer` header
  automatically and FB's CDN requires it; the engine now sends the exact
  page the stream came from (captured by the chooser/browser), and defaults
  to `https://www.facebook.com/` for any fbcdn/scontent/facebook URL —
  including resuming entries queued by older versions.
- **Full error-audit pass (v2.5):** instead of fixing one error per release,
  the whole pipeline was audited and hardened in one build — manual redirect
  following across http<->https hops, `Accept-Encoding: identity` so nothing
  can gzip-corrupt Range downloads, HTTP 416 handling, fallback to a single
  stream when a server lies about Range support, plain-language errors
  (403/410 = "link expired, share again"), Downloads name-collision suffixes
  instead of failed saves, long page-title names truncated safely, share
  intake from ClipData and `intent://` fallback links, trailing-punctuation
  trimming on pasted links, chooser Rescan button, CDN-domain cookies,
  browser Grab also scanning players/page meta, blob-stream explanation,
  and a watchdog so audio extraction can never spin forever.
- **In-page fetch for Facebook (v2.6):** a fresh-share test on v2.5 still
  got HTTP 403, proving the refusal is not link expiry and not the Referer —
  Facebook's CDN refuses the plain HTTP client while the same file plays
  inside the WebView. Chooser and built-in-browser picks on Facebook pages
  now fetch the file from inside the page's own WebView session
  (`WebFetcher`: fetch() + streamed JS bridge -> Downloads, optional audio
  extraction), recorded in the same download list. The engine also captures
  debug info on failure now (tap a failed entry -> Copy debug), ending
  guesswork about what the server answered.
- Paste a link, or Share a link from Chrome / Telegram / WhatsApp to the app
- Auto file-name detection from the link (editable before downloading)
- Queue: add many links, they download one after another
- Tap a row: pause / resume / open · long-press: remove (optionally delete file)
- Files are saved to the phone's **Downloads** folder (MediaStore)

Works for direct file links: PDF, ZIP, APK, MP4, MP3, documents, etc.

Still not included: torrent, video-site extraction (YouTube etc.).

## Install

Download `Orion-IDM.apk` (or build
it yourself, below), open it on your phone, and allow installing from that
source when Android asks. Tested target: Android 15 (OnePlus Nord CE5);
minimum: Android 8 (API 26).

Package: `com.rakib.orionidm` · Version: 2.6

## Build

No Gradle needed — the build uses only the Android SDK command-line tools:

```bash
# Needs JDK 17+ and Android SDK platform android-34 + build-tools 34.0.0
./build.sh
# Output: build/Orion-IDM.apk  (debug-signed)
```

## How it works

No external dependencies. `DownloadService` is a foreground service with its
own download engine: a probe request checks the total size and Range support,
then the file is fetched in up to 4 parallel segment files and merged, or as
a single resumable stream. Progress lives in `SharedPreferences` via `Store`,
so the UI, the notification and a restarted service all agree. `BrowserActivity`
is a small WebView browser whose download listener and link scanner hand URLs
(with cookies + user-agent) back to `MainActivity`.

## License

MIT
