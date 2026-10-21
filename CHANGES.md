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
