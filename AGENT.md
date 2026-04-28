# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Architecture

- **Language**: Kotlin + Jetpack Compose
- **Pattern**: MVVM (ViewModel + StateFlow)
- **DI**: Manual singleton / Service Locator — NO Hilt, NO Koin
- **Storage**: DataStore Preferences only — NO Room, NO SQL
- **Network**: OkHttp + Retrofit (two clients: video API + CloudBase API)
- **Min SDK**: 24 | **Target/Compile SDK**: 36

## Critical Coding Rules

1. **代码只能由主 agent 亲自书写和修改** — 子 agent 仅用于研究和探索，不得生成或修改 .kt/.xml/.gradle 文件
2. **不添加新依赖** — 项目已有 OkHttp, Retrofit, Coroutines, DataStore, Coil3, Navigation Compose, Media3 ExoPlayer, NanoHTTPD, UPnPCast。如需新功能，优先使用现有依赖
3. **UI 文本必须为中文** — 所有面向用户的文本（按钮、提示、标题、Toast）使用中文，代码变量名可用英文

## Build & Release

```bash
# Debug build
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Release build + upload (自动递增版本号，上传到蒲公英 + 云存储)
node scripts/release.js ["<updateLog>"]
# 指定版本: node scripts/release.js <versionCode> <versionName> "<updateLog>"
# 回退版本: node scripts/release.js --rollback <versionCode> <versionName> "<apkFileId>"

# Release requires: TCB_SECRET_ID, TCB_SECRET_KEY env vars
# Release signing: keystore.properties in project root
```

## Navigation & Screens

**4 Bottom Tabs**: 首页(Home) | 分类(VideoLibrary) | 下载(PlayCircle) | 我的(Person)

**All Routes** (defined in `ui/nav/Screen.kt`):
- `Splash` → `Auth` (if not guest) → `Home` (start destination)
- `Detail/{videoId}` — video detail with ExoPlayer playback
- `Search` — search with history
- `Category?categoryId={id}` — category browsing
- `Download` — download management (downloading + completed tabs)
- `LocalPlayer/{videoId}?episodeIndex={idx}` — offline video playback
- `Profile` → `ProfileEdit`, `Favorites`, `WatchHistory`, `Feedback`, `About`

**Navigation flow**: All video clicks → `Detail`. Downloaded video clicks → `LocalPlayer`.

## Key APIs

- **Video Source**: `https://cj.lziapi.com/api.php/provide/vod/` (no auth, Referer required)
  - `ApiService.getVideoList(ac="list", t, pg, wd, area, year)` — list/search
  - `ApiService.getVideoDetail(ac="detail", ids)` — detail by id
- **CloudBase**: `https://yingshi-8gu7ost293ff515a.api.tcloudbasegateway.com/`
  - Three Retrofit services: `CloudBaseAuthApi`, `CloudBaseDatabaseApi`, `CloudBaseStorageApi`
  - All use shared `cloudBaseOkHttpClient` with automatic 401 token refresh
- **M3U8 Headers**: `Referer: https://cj.lziapi.com/`, `User-Agent: Mozilla/5.0...`

## Data Layer

```
data/
├── ApiModels.kt          # VideoItem, VideoCategory, Episode, parsePlaySources()
├── ApiService.kt         # Retrofit interface for video API
├── AppCache.kt           # In-memory cache singleton
├── NetworkModule.kt      # Two Retrofit clients (video + CloudBase)
├── auth/                 # CloudBase auth (login, SMS, session restore, token refresh)
├── database/             # CloudBase DB operations (CRUD for cloud sync)
├── download/             # M3U8 segment download engine + TS→MP4 merge
├── local/                # DataStore stores: favorites, watchHistory, userPreferences, searchHistory
├── proxy/                # Local DLNA proxy server (NanoHTTPD), M3U8 parser, PTS fixer
├── storage/              # CloudBase file storage (avatar upload/download)
├── sync/                 # Bidirectional cloud sync (favorites, history, profile)
└── update/               # App update checker (checks CloudBase DB for latest version)
```

**Storage pattern**: All persistence uses DataStore Preferences with extension functions on `Context` (e.g., `context.favoritesStore()`, `context.userPreferences()`).

## Token & Auth

- `access_token` expires in 24h — `refresh_token` expires in 30 days
- OkHttp `Authenticator` on `cloudBaseOkHttpClient` handles 401 → refresh → retry automatically
- `AuthRepository` is singleton with `authStateChanges()` Flow for reactive auth state
- Guests (skipped login) skip cloud sync and update checks
- Session restored in `App.onCreate()` with token validation and fallback refresh

## Foreground Services

- `DlnaProxyService` (type: mediaPlayback) — serves M3U8 proxy for DLNA casting
- `DownloadService` (type: dataSync) — handles background download tasks

## Key Data Structures

```kotlin
// DownloadTask — persisted as JSON in DataStore
data class DownloadTask(
    id, videoId, videoName, videoPic, episodeName, episodeUrl,
    savePath, status: String, progress, downloadedBytes, totalBytes,
    speed, createdAt
)
// status = DownloadStatus enum name: PENDING | DOWNLOADING | PAUSED | COMPLETED | FAILED

// VideoSource / Episode — parsed from vod_play_url field
data class VideoSource(name, episodes: List<Episode>)
data class Episode(name, url)

// AuthState — sealed class for auth observation
sealed class AuthState { Authenticated(user), Unauthenticated, Loading }
```
