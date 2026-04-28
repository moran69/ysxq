package com.ysxq.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import com.yinnho.upnpcast.DLNACast
import com.ysxq.app.data.AppCache
import com.ysxq.app.data.auth.AuthRepository
import com.ysxq.app.data.download.DownloadManager
import com.ysxq.app.data.download.downloadStore
import com.ysxq.app.data.local.favoritesStore
import com.ysxq.app.data.local.userPreferences
import com.ysxq.app.data.local.watchHistoryStore
import com.ysxq.app.data.sync.FavoritesSyncRepository

import com.ysxq.app.data.sync.WatchHistorySyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class App : Application() {

    companion object {
        var instance: Application? = null
            private set

        fun getImageLoader(context: PlatformContext): ImageLoader {
            return ImageLoader.Builder(context)
                .components {
                    add(
                        OkHttpNetworkFetcherFactory(
                            OkHttpClient.Builder()
                                .addInterceptor { chain ->
                                    val request = chain.request().newBuilder()
                                        .addHeader("Referer", "https://www.ysxq.cc/")
                                        .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                                        .build()
                                    chain.proceed(request)
                                }
                                .followRedirects(true)
                                .followSslRedirects(true)
                                .build()
                        )
                    )
                }
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .build()
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        DLNACast.init(this)
        AppCache.init(this)
        DownloadManager.init(this, this.downloadStore())
        restoreSession()

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                appScope.launch {
                    AuthRepository.getValidAccessToken()
                }
            }
        })
    }

    private fun restoreSession() {
        appScope.launch {
            val prefs = userPreferences()
            val user = prefs.userInfo.first()
            val accessToken = prefs.accessToken.first()
            if (user != null && accessToken != null) {
                val refreshToken = prefs.refreshToken.first()
                AuthRepository.restoreSession(user, accessToken, refreshToken)

                // 尝试验证/恢复会话，但网络错误不应该导致登出
                val result = AuthRepository.reloadUser()
                if (result.isFailure) {
                    // reloadUser 失败可能是 access token 过期或网络问题
                    // 尝试刷新 token
                    val refreshed = AuthRepository.refreshAccessToken()
                    if (refreshed != null) {
                        // 刷新成功，重试
                        AuthRepository.reloadUser()
                    }
                    // 不管刷新成功还是失败，都不主动 signOut
                    // 如果 token 真的过期了，后续 API 调用的 401 会由 OkHttp Authenticator 处理
                    // Authenticator 中的 doRefresh 会在确认 token 无效时触发 signOut
                }

                // 尝试云端同步（失败不影响用户使用）
                try {
                    FavoritesSyncRepository(favoritesStore()).pullFromCloud()
                    WatchHistorySyncRepository(watchHistoryStore()).pullFromCloud()
                } catch (_: Exception) { }
            }
        }
    }
}
