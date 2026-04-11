package com.ysxq.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import com.yinnho.upnpcast.DLNACast
import com.ysxq.app.data.auth.AuthRepository
import com.ysxq.app.data.local.userPreferences
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
        restoreSession()
    }

    private fun restoreSession() {
        appScope.launch {
            val prefs = userPreferences()
            val user = prefs.userInfo.first()
            val accessToken = prefs.accessToken.first()
            if (user != null && accessToken != null) {
                val refreshToken = prefs.refreshToken.first()
                AuthRepository.restoreSession(user, accessToken, refreshToken)
                val result = AuthRepository.reloadUser()
                if (result.isFailure) {
                    AuthRepository.signOut(reason = "登录已过期，请重新登录")
                }
            }
        }
    }
}
