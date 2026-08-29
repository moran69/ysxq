package com.momo.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import com.yinnho.upnpcast.DLNACast
import com.momo.app.data.AppCache
import com.momo.app.data.auth.AuthRepository
import com.momo.app.data.download.DownloadManager
import com.momo.app.data.download.downloadStore
import com.momo.app.data.local.favoritesStore
import com.momo.app.data.local.userPreferences
import com.momo.app.data.local.watchHistoryStore
import com.momo.app.data.sync.FavoritesSyncRepository

import com.momo.app.data.sync.WatchHistorySyncRepository
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
                                        .addHeader("Referer", "http://161.118.252.183:8899/")
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

        // 启动时后台自动检查更新（发现新版本会经全局弹窗提示）
        com.momo.app.data.update.UpdateChecker.autoCheck()

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
            val isLoggedIn = prefs.isLoggedIn.first()
            val isGuest = prefs.isGuest.first()
            if (user != null && (isLoggedIn || isGuest.not())) {
                val accessToken = prefs.accessToken.first()
                if (accessToken != null) {
                    AuthRepository.restoreSession(user, accessToken, null)
                    val result = AuthRepository.reloadUser()
                    if (result.isFailure) {
                        // JWT 失效/过期 -> 登出并提示重新登录
                        AuthRepository.signOut(reason = "登录已过期，请重新登录")
                    }
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
