# Changes, round 6: faster checks, clipboard, yt-dlp 2026.10.21, new logo

- **yt-dlp 2026.10.21:** installed automatically on first start (from yt-dlp's official GitHub release) before any media job runs; the bundled library version is from late 2025. Settings shows the installed version tag.
- **Link box:** the newest copied link fills in each time the Media downloader comes to the front, and is checked at once.
- **Faster:** quality and audio buttons work immediately (the exact list loads in the background); YouTube/Vimeo titles and thumbnails appear in about a second; video and playlist are checked in parallel; results are cached for 15 minutes; a copied video link is looked up in the background as soon as VoltFetch sees it.
- **New logo and app icon:** flat lightning-bolt download arrow on a blue-violet gradient; adaptive, round and themed (monochrome) icons, splash icon, in-app logo.
- **Release signing:** a keystore is provided separately; add its 4 values as GitHub secrets so every build installs over the previous one.

# Changes, round 5: browser that stays open

- **Home button** in the browser's top bar returns to the main screen; the browser stays open behind it (page, history, scroll position). The main screen's **Browser** button brings it back exactly where you left it.
- **Back on the first page** (or a **long-press on Back**) also returns to the main screen and keeps the page. **Close browser** in the menu ends the session; **Start page** shows the browser's start page.
- **Restored if closed:** if Android closes the browser in the background, the page and its history come back; after an app restart the last page reopens.
- **Desktop site** toggle and **Share page** in the browser menu.
- **Back on the main screen** sends VoltFetch to the background instead of closing it, so a minimised browser isn't lost.

# Changes, round 4: convenience

- **Share menu "Save video" / "Save audio":** queues the link at once with your last-used choices (quality, MP3 or original audio, subtitles, SponsorBlock, browser login). No screen opens; the real title replaces the placeholder name once the download starts.
- **Automatic retry:** downloads that fail because of the network retry by themselves after 30 s, 2 min and 5 min, and immediately when the connection comes back. No failure alert while a retry is pending; the list shows "Connection problem · retrying by itself". Pausing cancels it.
- **Media downloader:** a pasted link is checked automatically; opening it with a video link on the clipboard fills it in; the last quality, MP3 bitrate and audio type are remembered (also used for playlists).
- **Home-screen shortcuts** (long-press the icon): Paste link, Media downloader, Browser.
- **Share button** on "Download complete" notifications.

# Changes, round 3: more sites and media options

- **Many more sites:** the Media downloader accepts links from Instagram, Facebook, X, Vimeo, Reddit, SoundCloud, TikTok, Dailymotion and any other site yt-dlp supports. Shared and copied links from known sites open it directly; a web page added to the normal downloader offers it too.
- **Browser login:** optionally uses the sign-in from VoltFetch's Browser tab for sites that need one. Only that site's cookies are used, via a temporary file.
- **Original audio:** keeps the site's own track without re-encoding (usually M4A).
- **SponsorBlock (YouTube):** optionally cuts sponsor, self-promotion, "subscribe" and non-music parts.
- **Clips:** download only from a start time to an end time.
- **Playlist sync:** follow a playlist (or "Keep in sync" while downloading it); new videos are queued every night at 3:00. Manage it in Settings › Followed playlists.
- **Clipboard:** a copied video link from a known site offers the Media downloader.
- **Thumbnails** in the download list and notifications. Each active download has its own notification with a **Pause** button.
- **Fixed:** the local proxy listens on 127.0.0.1 (Android's loopback default is IPv6), which caused "Connection refused". Wrong "needs an account" hint fixed; widescreen qualities labelled 4K/2K/Full HD; phone-only APKs.

# Changes: media queue fixes and improvements

## Build blockers fixed
- `Job` had no `cancelAction`. It now has `onCancel { }`, which runs immediately if the job was already cancelled. A watchdog also keeps killing the yt-dlp process if a cancel arrives before the process has registered.
- The handler now uses the library's real `execute(request, processId) { progress, eta, line -> }` API. The old two-argument `DownloadProgressCallback` didn't exist.
- `tools/local-build.sh` now stops with a clear message, because it can't include the media library.

## Bugs fixed
- **Temporary files:** yt-dlp working files are deleted on remove, on "Download again", after success, and at start-up for tasks that no longer exist.
- **Shared links:** sharing a YouTube link opens the Media downloader instead of saying downloads aren't allowed.
- **Start-up:** yt-dlp/FFmpeg start on a background thread. Media jobs wait for them and show a readable error if start-up fails.
- **Progress:** one bar rises steadily across video, audio, merging and converting, and shows the current stage.
- **Speed and time left:** real speed and ETA come from yt-dlp's output.
- **Speed limit:** yt-dlp runs through a local proxy that shares the global limiter, so the limit covers all downloads together and changes apply immediately. The user's HTTP proxy setting now applies to media too.
- **Quality list:** sizes now include the audio track, and the codec shown matches what will be downloaded.
- **File names:** the stored name is the one Android actually used (e.g. "name (1).mp4"), and the " [videoId]" suffix is removed.
- **Secrets and backups:** site passwords and cookies are encrypted with the Android Keystore, and the download list is excluded from backups.
- **Release builds:** R8 keep rules added for youtubedl-android, Jackson and Commons Compress.
- **Speed limiter accuracy:** it charges for bytes actually received; before, it charged a full buffer per read and ran slower than the set limit.
- **Durations:** shown as h:mm:ss.
- **Details screen:** YouTube tasks show a Format row instead of login, checksum and resume options that don't apply. "Change address" only accepts YouTube links for them.
- **One YouTube check:** a single `YouTube` helper is shared by every screen and the handler.
- **Error messages:** yt-dlp errors are reduced to the `ERROR:` line, with a hint (update the engine, check the connection, account needed).
- **Process IDs:** each run gets a unique ID; the library refuses duplicate IDs.

## New
- **Settings › Media engine:** shows the yt-dlp version and has an "Update media engine" button. A weekly automatic check runs on Wi-Fi only and never during a media job.
- **Better formats:** `-S res:<h>,vcodec:h264,acodec:aac` with `--merge-output-format mp4`.
- **MP3 extras:** cover art and tags embedded.
- **Subtitles (optional):** the phone's language plus English, embedded in the MP4. If subtitles, cover art or tags fail, the download is retried once without them.
- **Playlists:** each video becomes its own queue item, at "best up to" a chosen quality or as MP3. Videos already in the list are skipped.
- **Queue picker** on the Media screen.
- **Smaller APKs:** one per processor type (arm64-v8a, armeabi-v7a, x86_64, x86) plus a universal APK, and CI publishes all of them.
- **Android 15:** compiled against API 35. If Android's daily limit on background downloads runs out, downloads pause cleanly with a notification. targetSdk stays 34 until the edge-to-edge layout change is checked on a device.
- **Tests:** `EngineSelfTest` has 96 checks, including media tasks (with a stand-in for yt-dlp), progress parsing, the local proxy (HTTP, CONNECT, chaining, speed limit) and encrypted secrets.
