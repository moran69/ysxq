# AGENTS.md — YsxqApp

> 影视资源聚合 Android 应用，Jetpack Compose + Material3 暗色二次元主题，DLNA 投屏。

---

## 快速参考

| 项目 | 值 |
|---|---|
| 包名 | `com.ysxq.app` |
| 模块 | 单模块 `:app` |
| minSdk / targetSdk | 24 / 36 |
| Kotlin | 2.1.0 |
| Compose BOM | 2024.12.01 |
| JDK | `/opt/android-studio/jbr`（gradle.properties 已配置） |
| 构建 | `./gradlew assembleDebug` / `assembleRelease` |
| 签名 | `release.keystore`，凭据硬编码在 `app/build.gradle.kts` |

---

## 架构

**MVVM + 手动单例**（无 Hilt/Koin/DI 框架）。ViewModel 通过 `object` 单例获取 Repository。

### 数据流

```
Screen (Composable)
  → collectAsState() ← ViewModel (StateFlow)
    → Repository (object 单例)
      → Retrofit ApiService / DataStore / CloudBase API
```

### 双后端

| 后端 | Base URL | 用途 |
|---|---|---|
| 视频 CMS | `https://cj.lziapi.com/` | 影视资源搜索、分类、详情、播放源 |
| CloudBase | `https://yingshi-8gu7ost293ff515a.api.tcloudbasegateway.com/` | 用户认证、收藏/历史云同步、头像上传 |

---

## 目录结构

```
app/src/main/java/com/ysxq/app/
├── App.kt                  # Application：DLNA 初始化、auth 恢复、Coil ImageLoader
├── YsxqApp.kt              # ⚠️ 死代码（未使用的 Application 子类）
├── data/
│   ├── NetworkModule.kt    # Retrofit 工厂（单例）
│   ├── ApiService.kt       # 视频 CMS API 接口定义
│   ├── ApiModels.kt        # CMS 数据模型
│   ├── AppCache.kt         # 内存缓存（单例）
│   ├── auth/               # 认证：AuthRepository, AuthModels, CloudBaseAuthApi
│   ├── local/              # 本地存储：UserPreferences, FavoritesStore, WatchHistoryStore（均 DataStore Preferences）
│   ├── database/           # 云数据库：CloudBaseDatabaseApi, DatabaseModels
│   ├── sync/               # 双向同步：FavoritesSyncRepository, WatchHistorySyncRepository
│   ├── proxy/              # DLNA 投屏代理（最复杂的子系统）
│   │   ├── DlnaProxyServer.kt      # NanoHTTPD 本地代理服务器
│   │   ├── DlnaProxyService.kt     # 前台 Service
│   │   ├── M3u8Parser.kt           # HLS M3U8 解析（含 seek 支持）
│   │   └── ProxySessionManager.kt  # 代理会话管理
│   └── storage/            # CloudBaseStorageHelper（头像上传）
├── viewmodel/
│   ├── AuthViewModel.kt
│   ├── CategoryViewModel.kt
│   ├── DetailViewModel.kt
│   ├── HomeViewModel.kt
│   ├── ProfileEditViewModel.kt
│   ├── ProfileViewModel.kt
│   ├── SearchViewModel.kt
│   └── SplashViewModel.kt
└── ui/
    ├── MainActivity.kt     # 单 Activity
    ├── nav/
    │   ├── AppNavHost.kt   # Compose Navigation 图
    │   └── Screen.kt       # 路由 sealed class
    ├── screens/            # 11 个 Composable 页面
    ├── components/         # 可复用 UI 组件
    └── theme/              # Material3 暗色主题（SakuraPrimary, SkyBlue, Lavender）
```

---

## 关键子系统

### DLNA 投屏

本地 NanoHTTPD 代理服务器，为 DLNA 渲染器提供 HLS 内容：

1. `M3u8Parser` 解析 M3U8 → 获取分片 URL 列表
2. `DlnaProxyServer` 在 LAN 上代理 HLS 分片，注入 Referer 头
3. `ProxySessionManager` 管理多设备投屏会话
4. `DlnaProxyService` 作为前台 Service 保持代理运行
5. `AndroidManifest.xml` 启用 cleartext traffic（LAN 通信需要）

### 云同步

- `FavoritesSyncRepository` / `WatchHistorySyncRepository`
- 双向同步策略：本地 DataStore ↔ CloudBase 云数据库
- 登录时拉取云端数据合并，本地变更实时上传

### 认证

- `AuthRepository` 单例管理登录状态
- 支持游客模式（跳过认证）
- CloudBase 云函数认证

---

## 测试

**现状**：极简覆盖，仅 `data/proxy/` 有测试。

| 文件 | 测试数 | 框架 |
|---|---|---|
| `M3u8ParserTest.kt` | 6 | JUnit 4 |
| `ProxySessionManagerTest.kt` | 5 | JUnit 4 + kotlinx-coroutines-test |

- 手写 fake（无 MockK/Mockito）
- 无 `androidTest/`，无 Espresso，无 Compose UI 测试
- 无 testOptions 配置

运行：`./gradlew test`

---

## 构建说明

### Debug

```bash
./gradlew assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk
```

### Release

```bash
./gradlew assembleRelease
# 输出：app/build/outputs/apk/release/app-release.apk
# ProGuard + shrink 已启用
```

### 注意事项

- 无 version catalog（`libs.versions.toml`），所有依赖版本内联在 `app/build.gradle.kts`
- ProGuard 规则仅覆盖 kotlinx.serialization
- 无 CI/CD、无 lint 配置、无 pre-commit hooks、无 `.editorconfig`
- `util/` 目录存在但为空
- `YsxqApp.kt` 是死代码，可安全删除

---

## 技术栈

| 类别 | 技术 |
|---|---|
| UI | Jetpack Compose (BOM 2024.12.01), Material3 |
| 导航 | Compose Navigation |
| 网络 | Retrofit + OkHttp |
| 图片 | Coil 3 |
| 播放器 | Media3 ExoPlayer |
| 本地存储 | DataStore Preferences（无 Room） |
| 投屏 | 自建 DLNA/UPnP + NanoHTTPD 代理 |
| 分页 | Accompanist Pager |
| 序列化 | kotlinx.serialization |
| 后端 | 腾讯云 CloudBase（云函数 + 云数据库 + 云存储） |
