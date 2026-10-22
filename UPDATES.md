# Publishing VoltFetch updates (in-app auto-update)

The app updates itself from this repository's **GitHub Releases**. You only publish a release; phones find it,
download the APK for their processor and install it.

## One-time setup

1. **Make the repository public.** GitHub → repository → Settings → General → Danger Zone → *Change visibility* → Public.
   (The app reads releases without logging in, which only works for public repositories.)
2. **Signing secrets must be set** (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).
   Android only installs an update signed with the same key as the installed app.
3. **Workflow permissions:** GitHub → Settings → Actions → General → *Workflow permissions* →
   **Read and write permissions** → Save. (Lets the workflow create releases.)
4. Install one build made by the workflow **after** this update. From then on the app knows its repository
   (it's built in from `GITHUB_REPOSITORY`; you can also set it in the app: Settings → App updates → Update source).

## Every time you want to publish an update

1. Upload/commit your changes to the `main` branch as usual. Wait for the **Actions** build to turn green.
2. Go to the repository → **Releases** → **Draft a new release**.
3. **Choose a tag** → type a new version like `v1.3.0` → *Create new tag: v1.3.0 on publish*. Target: `main`.
4. Title: e.g. `VoltFetch 1.3.0`. Write what changed (shown in the app's update dialog). Click **Publish release**.
5. The workflow starts by itself (Actions tab, ~10 minutes) and attaches to that release:
   `VoltFetch-1.3.0-arm64-v8a.apk`, `VoltFetch-1.3.0-armeabi-v7a.apk` and `version.json`.
6. Done. Phones pick it up within a day (or immediately with Settings → App updates → **Check for updates now**).

### Rules
- Always use a **higher** version than last time: v1.3.0 → v1.3.1 → v1.4.0 … (tags must start with `v`).
- Don't delete the `version.json` file from a release; the app needs it.
- Don't mark the release as *pre-release*; the app ignores those (useful for testing builds privately).
- Don't rename the workflow file or rewrite git history; version codes are counted from the commits.

### If an update doesn't arrive
- App: Settings → App updates shows the last result (e.g. "no release found", "signed with a different key").
- Check the release has the two APKs **and** `version.json`.
- "Signed with a different key": the build ran without the signing secrets. Fix the secrets and publish a new version.
