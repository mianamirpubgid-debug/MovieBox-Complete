# Merged APK — Netflix Ke Pitaji + MovieBox Data Layer

This is the user's own working APK (`com.amir.netflixclone` v1.9 → upgraded v1.9.1,
versionCode 11) with **MovieBox Hindi-dubbed data merged in as an additive layer**.

**Zero changes to the original app logic, UI or style.** Everything below is additive:

## What was added

| Layer | File | What it does |
|---|---|---|
| Java | `src/.../MovieBoxApi.java` | MovieBox signed API client (visitor-login, tab-operating home, search/v2, subject detail) — cluster rotation + token refresh |
| Java | `src/.../MBStreams.java` | Full stream resolution: play-info v2/v1 → resource/v2 → trap recovery → detail detectors → UGC fallback (h264-first for WebView) |
| Java | `src/.../MainActivity.java` | Original wrapper 1:1 + NEW: `NFApp.mbHome/mbSearch/mbPlay` JS bridge methods + `/mbp?u=` streaming proxy (Range-aware, m3u8 playlist rewriting) |
| JS | `assets/www/moviebox.js` (new) | MovieBox home rows + merged search results + HTML5 player overlay (hls.js for m3u8, native controls, server switch, season/episode pills) |
| JS | `assets/www/hls.light.min.js` (new) | HLS playback engine (v1.5.20 light build) |
| JS | `assets/www/netflix-ui.js` (3 hooks) | Calls `MB.renderHomeRows`, `MB.searchAppend`, MB back-button handling — purely additive try/catch hooks |
| JS | `assets/www/index.html` (+2 script tags) | loads hls + moviebox layer |

## Behavior

- Home feed: original TMDB rows untouched; MovieBox rows (Hindi movies + series sections) appear below with a red **MOVIEBOX** badge.
- Search: original IMDb/TMDB results untouched; MovieBox Hindi-dubbed results merge below (or fill the page when old data returns nothing).
- Playback: original vidsrc/2embed iframe players untouched for TMDB items. MovieBox items play in a native dark overlay player using MovieBox CDN streams (mp4/HLS through the app's internal `/mbp` proxy — supports seeking via Range headers).
- Intro video, profiles (Mian Amir/Atif/Aqib/Abdullah/Kids), ad-blocking, popup-traps, fullscreen handling — all 100% original.

## Build

```bash
bash build.sh   # aapt2 + javac + d8 + apksigner (no Gradle needed)
```

Output: `Netflix-KePitaji-MovieBox.apk` (~6.1 MB, signed debug keystore).

**Install note:** different signing key than the NETFLIX-signed original, so uninstall
the old app once before installing this one.
