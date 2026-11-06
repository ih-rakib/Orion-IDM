# Simple Downloader

A simple IDM-style download manager for Android — MVP.

Paste or share a **direct file link**, and it downloads in the background with
progress, using Android's system DownloadManager.

## Features (MVP)

- Paste a link, or Share a link from Chrome / Telegram / WhatsApp to the app
- Auto file-name detection from the link (editable before downloading)
- Background downloads with system notification progress
- Automatic pause/resume when the network drops and returns
- Queue: add many links, they are kept in a history list
- Live in-app list: size, % done, state (Downloading / Paused / Done / Failed)
- Tap a finished file to open it, long-press to remove it and delete the file
- Files are saved to the phone's **Downloads** folder

Works for direct file links: PDF, ZIP, APK, MP4, MP3, documents, etc.

Not in the MVP (possible v2): multi-connection speed boost, torrent,
built-in browser link grabbing, video-site extraction.

## Install

Download `SimpleDownloader.apk` from the Releases/assets of this repo (or build
it yourself, below), open it on your phone, and allow installing from that
source when Android asks. Tested target: Android 15 (OnePlus Nord CE5);
minimum: Android 8 (API 26).

## Build

No Gradle needed — the build uses only the Android SDK command-line tools:

```bash
# Needs JDK 17+ and Android SDK platform android-34 + build-tools 34.0.0
./build.sh
# Output: build/SimpleDownloader.apk  (debug-signed)
```

## How it works

One Activity (`MainActivity.java`), no external dependencies. Downloads are
enqueued with `DownloadManager`, which is why background running, notification
progress and network resume work reliably without a foreground service.
Download IDs are remembered in `SharedPreferences` to render the history list.

## License

MIT
