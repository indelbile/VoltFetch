# VoltFetch

VoltFetch is a native Android download manager with queues, resumable multi-connection HTTP downloads, a built-in browser, a site grabber, and an **on-device YouTube video and audio downloader**.

## On-device media downloads

The media downloader embeds yt-dlp and FFmpeg through Android libraries, so users do not need a PC, companion server, Python installation, Termux, or another downloader app.

- **Videos and Shorts:** pick a resolution. The best stream up to that height is chosen, preferring H.264 + AAC for compatibility and falling back to VP9/AV1 at 1440p and above. Video and audio are merged into MP4.
- **MP3:** 128/192/256/320 kbps, with cover art and title/artist tags embedded.
- **Subtitles (optional):** in the phone's language and English, embedded in the MP4. Auto-generated captions are used when there are no others. If subtitles, cover art or tags fail, the download is retried without them rather than failing.
- **Playlists:** each video is added to the queue as its own item, at a chosen maximum quality or as MP3.
- **Sharing:** sharing a YouTube link to VoltFetch opens the Media downloader.
- Finished files are saved to `Download/VoltFetch/Video` or `Download/VoltFetch/Music`.

### Unified queue

YouTube tasks go into the same persistent queue as regular downloads. They share the concurrency settings, pause/resume/retry/remove controls, history, foreground service and notifications. Progress runs as one steady bar across every stage (video, audio, merging, converting), with the current stage, real speed and time left. Pausing stops the yt-dlp process; resuming continues from its partial files. Removing a task deletes its temporary files, and leftovers are cleaned at start-up.

yt-dlp's traffic goes through a small local proxy inside the app. This means the global speed limit and speed schedule apply to media downloads together with regular ones (changes take effect immediately), and the user's HTTP proxy setting is honoured.

### Keeping yt-dlp current

YouTube changes often. **Settings › Media engine › Update media engine** downloads the latest stable yt-dlp release. A weekly automatic check runs on Wi-Fi only and never while a media job is running. The engine starts in the background, so first launch is not blocked while the Python runtime unpacks.

### Compatibility

- Android 10+ (minSdk 29). Compiled against API 35; targetSdk is 34.
- Two phone APKs are built: `arm64-v8a` (almost every phone) and `armeabi-v7a` (older 32-bit phones).
- Some videos are inaccessible because of age, region, account, or platform restrictions.
- Download only content you own, have permission to save, or are otherwise legally entitled to download. Follow applicable copyright law and platform terms. VoltFetch does not attempt DRM circumvention.

## Privacy

Site passwords and browser cookies saved with downloads are encrypted with a key held in the Android Keystore. The download list, temporary media files and crash logs are excluded from Android backups; settings are still backed up.

## Build

Open the repository in Android Studio and build the `app` module, or run `./gradlew :app:assembleDebug` in an environment with the Android SDK and Maven access. The first build downloads the Android libraries, including the embedded media engine. `tools/local-build.sh` cannot build the media engine and refuses to run.

The engine has a JVM self-test that runs without Android:

```
kotlinc tools/EngineSelfTest.kt app/src/main/java/com/voltfetch/engine/*.kt -include-runtime -d /tmp/t.jar && java -jar /tmp/t.jar
```

It covers segmented downloads, queues, schedules, speed limits, media tasks (with a stand-in for yt-dlp), progress parsing, the local proxy and encrypted secrets.
