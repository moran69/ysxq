# AGENTS.md — YsxqApp ("EI Psy Cloud")

Android Kotlin app — single `:app` module, Jetpack Compose, MVVM.

## Quick Reference

| What | Command |
|---|---|
| Build debug APK | `./gradlew assembleDebug` |
| Build release APK | `./gradlew assembleRelease` |
| Run unit tests | `./gradlew testDebugUnitTest` |
| Android lint | `./gradlew lintDebug` |
| Full verify | `./gradlew build` |
| Install on device | `./gradlew installDebug` |

No ktlint, detekt, or formatter plugin is configured. `kotlin.code.style=official` is set in `gradle.properties` but not enforced by a build step.

## Stack

- Kotlin 2.1.0, Gradle 8.11.1, AGP 8.7.3
- Jetpack Compose (BOM 2024.12.01), Material 3, Navigation Compose
- Retrofit 2.11.0 + OkHttp 4.12.0 + kotlinx-serialization
- Coil 3, Media3 ExoPlayer (HLS), DataStore Preferences
- DLNA: upnpcast + NanoHTTPD HLS proxy server
- compileSdk/targetSdk 36, minSdk 24, Java 11 target

**JDK**: Gradle is configured to use `/opt/android-studio/jbr`. If builds fail with JDK errors, verify this path exists or update `gradle.properties`.

## Architecture

```
com.ysxq.app/
  App.kt                    # Real Application class (Coil, DLNA init, session restore)
  YsxqApp.kt                # UNUSED stub — do not reference
  ui/
    MainActivity.kt         # Single activity, edge-to-edge Compose
    nav/                    # Screen routes (sealed class) + AppNavHost
    screens/                # 12 Compose screens
    components/             # Shared composables
    theme/                  # Dark-only theme, sakura pink
  viewmodel/                # MVVM ViewModels — each exposes StateFlow<State>
  data/
    NetworkModule.kt        # Singleton Retrofit/OkHttp + token refresh
    ApiService.kt           # Video CMS API (cj.lziapi.com)
    ApiModels.kt            # Data classes + parsePlaySources()
    AppCache.kt             # In-memory cache singleton
    auth/                   # AuthRepository (singleton), CloudBase auth
    database/               # CloudBase database API
    local/                  # DataStore-backed stores (prefs, favorites, history)
    sync/                   # Bidirectional cloud sync (favorites + watch history)
    proxy/                  # DLNA proxy server (NanoHTTPD + HLS + session management)
    storage/                # Avatar upload helper
```

### Key Patterns (non-obvious)

- **No DI framework.** `AuthRepository`, `NetworkModule`, `AppCache` are Kotlin `object` singletons. ViewModels create dependencies directly — no Hilt/Koin.
- **Referer spoofing required.** All video API requests need fake `Referer` and `User-Agent` headers. `NetworkModule` configures this on OkHttp. Do not remove these interceptors.
- **DataStore only — no Room/SQLite.** Favorites, watch history, and user preferences are JSON-serialized into DataStore Preferences. There is no local database.
- **Auth tokens in plain DataStore.** Not using EncryptedSharedPreferences. The app also has `usesCleartextTraffic="true"` in the manifest.
- **Chinese UI strings are hardcoded.** User-facing text is inline in Compose screens, not in `strings.xml`. Do not try to externalize them unless asked.
- **DLNA proxy is a local NanoHTTPD server** that re-serves HLS segments on the LAN with spoofed headers. The flow is: app → proxy server (on device) → upstream CDN → DLNA renderer. See `data/proxy/` for the full pipeline.

## Testing

- 2 unit test files in `app/src/test/`, covering the DLNA proxy layer only:
  - `data/proxy/M3u8ParserTest.kt`
  - `data/proxy/ProxySessionManagerTest.kt`
- Framework: JUnit 4 + kotlinx-coroutines-test
- No instrumented tests, no coverage tooling

## Signing

Release builds use `keystore.properties` + `release.keystore` at the repo root. Both are git-ignored but must exist for `./gradlew assembleRelease` to succeed. If missing, only debug builds will work.

## Things to Avoid

- Do not reference `YsxqApp.kt` — `App.kt` is the real Application class.
- Do not add Room/SQLite without discussion — the project intentionally uses DataStore only.
- Do not add Hilt/Koin without discussion — the singleton pattern is deliberate.
- Do not remove OkHttp interceptors in `NetworkModule` — the APIs require spoofed headers.
- `app/src/main/res/xml/network_security_config.xml` should block cleartext, but the manifest overrides it with `usesCleartextTraffic="true"`. Be aware of this conflict when working on network security.
