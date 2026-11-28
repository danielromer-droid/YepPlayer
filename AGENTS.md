# AGENTS.md — YepPlayer (Android)

Instructions for any AI coding agent (Claude, Antigravity, Codex, Copilot…) working in this repo.

## What this project is
YepPlayer: an Android media player app (`com.yepplayer.app`) for streams from the user's own, authorised sources (Xtream-style accounts).
TODO for the owner: add a 2–3 line description and whether it targets phones, TV or both.

## Setup
- Android app in Kotlin, Gradle (`app/`, `build.gradle.kts`). minSdk 26, targetSdk 35. `versionName` in `app/build.gradle.kts` (currently 0.6.6); `versionCode` comes from the GitHub run number.
- **GitHub Actions** (`.github/workflows/android.yml`) builds the APK on every push to `main` and on pull requests. The release signing key comes from the GitHub secret `YEP_KEYSTORE_BASE64`.
- `cloudflare-worker/`: optional small Worker `yepplayer-service` (`/health`, `/config`), public metadata only.

## Project rules
- **Never send the user's account credentials or video through the Worker or any server.** Credentials stay on the device.
- Authorised sources only. Don't add built-in channel lists or links to pirated content.
- Bump `versionName` when releasing a user-visible change.

## Rules for all of Daniel's projects (the same in every repo)
- **Free only.** Use free plans and free services. Ask the owner before adding anything paid.
- **No secrets in the repo.** API keys, passwords and tokens go in Cloudflare or GitHub secrets.
- **Keep the owner's private email address out** of the code and the website.
- **Don't break links.** Keep existing URLs, page titles and sitemaps working (Google ranking).
- **`main` is live.** In most repos a push to `main` publishes the site or builds a new app release. Only push work that builds and has been checked.
- **Explain changes simply.** Give a short, plain-language summary of what changed and how to check it.
- **Keep this file current** when the setup changes. Claude reads it through `CLAUDE.md`. Antigravity, Codex and Copilot read `AGENTS.md` directly.
