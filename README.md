# VoltFetch

VoltFetch is a native Android download manager with queues, resumable multi-connection HTTP downloads, a built-in browser, a site grabber, and an **on-device YouTube video and audio downloader**.

## On-device media downloads

The media downloader embeds yt-dlp and FFmpeg, so no PC, server, Termux or other app is needed. It works with **YouTube, Instagram, Facebook, X, Vimeo, Reddit, SoundCloud, TikTok, Dailymotion** and the many other sites yt-dlp supports.

- **Videos:** pick a resolution; the best stream up to that height is chosen (H.264 + AAC preferred for compatibility), merged into MP4.
- **Audio:** *Original audio* copies the site's own track without re-encoding (best quality, usually M4A), or MP3 at 128–320 kbps. Cover art and tags are embedded.
- **Clips:** download only part of a video by giving a start and end time (e.g. 1:20 to 2:45).
- **SponsorBlock (YouTube):** optionally cut sponsor segments, self-promotion, "subscribe" reminders and non-music parts.
- **Subtitles (optional):** phone's language and English, embedded in the MP4.
- **Browser login:** sites that only show videos to signed-in users use the login from VoltFetch's own Browser tab. Only that site's cookies are passed to yt-dlp, in a temporary file deleted right after.
- **Playlists:** each item is queued separately. **Keep in sync / Follow** checks a playlist every night at 3:00 (and on the next app start if a night was missed) and queues new videos. Manage followed playlists in Settings.
- **Sharing:** sharing a link from a known site opens the Media downloader; copying one offers it on the main screen. The share menu also has **Save video** and **Save audio**, which queue the link at once with your last-used choices.
- **Automatic retry:** network failures are retried by themselves (30 s, 2 min, 5 min) and right away when the connection returns.
- **Shortcuts:** long-press the app icon for Paste link, Media downloader and Browser.
- **Ad blocker (browser):** blocks ad and tracker servers, YouTube video ads and ad boxes; Facebook/Instagram "Sponsored" posts are hidden. The shield in the address bar turns it off, or allows ads on one site.
- **Browser tools:** the download button always uses the video that's playing (formats are fetched in the background) and opens a quick quality list; bookmarks, find in page, text size, save as PDF, background play.
- **Browser stays open:** its Home button (or Back on the first page) returns to the main screen without closing the page; the Browser button brings it back where you left it. Close it from its menu.
- Finished files are saved to `Download/VoltFetch/Video` or `Download/VoltFetch/Music`.

### Unified queue

YouTube tasks go into the same persistent queue as regular downloads. They share the concurrency settings, pause/resume/retry/remove controls, history, foreground service and notifications. Progress runs as one steady bar across every stage (video, audio, merging, converting), with the current stage, real speed and time left. Media downloads show their thumbnail in the list, and every active download gets its own notification with a Pause button. Pausing stops the yt-dlp process; resuming continues from its partial files. Removing a task deletes its temporary files, and leftovers are cleaned at start-up.

yt-dlp's traffic goes through a small local proxy inside the app. This means the global speed limit and speed schedule apply to media downloads together with regular ones (changes take effect immediately), and the user's HTTP proxy setting is honoured.

### Keeping yt-dlp current

YouTube changes often. On first start the app installs yt-dlp **2026.10.21** (the bundled library ships an older one). **Settings › Media engine › Update media engine** downloads the latest stable yt-dlp release. A weekly automatic check runs on Wi-Fi only and never while a media job is running. The engine starts in the background, so first launch is not blocked while the Python runtime unpacks.

### Compatibility

- Android 10+ (minSdk 29). Compiled against API 35; targetSdk is 34.
- Two phone APKs are built: `arm64-v8a` (almost every phone) and `armeabi-v7a` (older 32-bit phones).
- Some videos are inaccessible because of age, region, account, or platform restrictions.
- Download only content you own, have permission to save, or are otherwise legally entitled to download. Follow applicable copyright law and platform terms. VoltFetch does not attempt DRM circumvention.

## App updates

VoltFetch updates itself from this repository's GitHub Releases (Settings › App updates). See **UPDATES.md** for how to publish a release.

## Privacy

Site passwords and browser cookies saved with downloads are encrypted with a key held in the Android Keystore. The download list, temporary media files and crash logs are excluded from Android backups; settings are still backed up.

## Build

Open the repository in Android Studio and build the `app` module, or run `./gradlew :app:assembleDebug` in an environment with the Android SDK and Maven access. The first build downloads the Android libraries, including the embedded media engine. `tools/local-build.sh` cannot build the media engine and refuses to run.

The engine has a JVM self-test that runs without Android:

```
kotlinc tools/EngineSelfTest.kt app/src/main/java/com/voltfetch/engine/*.kt -include-runtime -d /tmp/t.jar && java -jar /tmp/t.jar
```

It covers segmented downloads, queues, schedules, speed limits, media tasks (with a stand-in for yt-dlp), progress parsing, the local proxy and encrypted secrets.
