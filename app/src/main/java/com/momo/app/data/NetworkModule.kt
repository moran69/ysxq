package com.momo.app.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.momo.app.data.auth.AuthRepository
import com.momo.app.data.auth.CloudBaseAuthApi
import com.momo.app.data.auth.MacCmsAuthApi
import com.momo.app.data.database.CloudBaseDatabaseApi
import com.momo.app.data.storage.CloudBaseStorageApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import com.momo.app.App
import android.content.Context
import java.util.concurrent.TimeUnit

object NetworkModule {

    private const val BASE_URL = "http://161.118.252.183:8899/"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val refererInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("Referer", BASE_URL)
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .build()
        chain.proceed(request)
    }

    /**
     * 为 MacCMS 请求自动附加 JWT（Authorization: Bearer <token>）。
     * 未登录时无 token，不加 header（登录/注册接口本身就是公开的）。
     * 注意：必须用 header()（覆盖）而非 addHeader()（追加），
     * 否则与接口上显式的 @Header("Authorization") 叠加成两个 Authorization 头，
     * nginx 会直接返回 400 Bad Request。
     */
    private val jwtInterceptor = Interceptor { chain ->
        val token = AuthRepository.getAccessToken()
        val request = if (token != null && !token.isBlank()) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        chain.proceed(request)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(refererInterceptor)
        .addInterceptor(jwtInterceptor)
        .build()

    val apiService: ApiService = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ApiService::class.java)

    /**
     * 苹果CMS 会员认证 API（JWT）。
     * 复用 okHttpClient（已带 JWT 拦截器），登录/注册不受影响。
     */
    val macCmsAuthService: MacCmsAuthApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(MacCmsAuthApi::class.java)

    private const val CLOUDBASE_BASE_URL = "https://yingshi-8gu7ost293ff515a.api.tcloudbasegateway.com/"

    val cloudBaseDeviceId: String by lazy {
        val prefs = App.instance?.getSharedPreferences("cloudbase_device", Context.MODE_PRIVATE)
        val existing = prefs?.getString("device_id", null)
        existing ?: java.util.UUID.randomUUID().toString().also {
            prefs?.edit()?.putString("device_id", it)?.apply()
        }
    }

    private val tokenAuthenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (response.priorResponse != null) return null
            val currentToken = AuthRepository.getAccessToken()
            val failedToken = response.request.header("Authorization")
                ?.removePrefix("Bearer ")
            if (currentToken != null && currentToken != failedToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .build()
            }
            val newToken = kotlinx.coroutines.runBlocking {
                AuthRepository.refreshAccessToken()
            } ?: return null
            return response.request.newBuilder()
                .header("Authorization", "Bearer $newToken")
                .build()
        }
    }

    private val cloudBaseOkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()
            chain.proceed(request)
        })
        .authenticator(tokenAuthenticator)
        .build()

    val cloudBaseAuthService: CloudBaseAuthApi = Retrofit.Builder()
        .baseUrl(CLOUDBASE_BASE_URL)
        .client(cloudBaseOkHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(CloudBaseAuthApi::class.java)

    val cloudBaseDatabaseService: CloudBaseDatabaseApi = Retrofit.Builder()
        .baseUrl(CLOUDBASE_BASE_URL)
        .client(cloudBaseOkHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(CloudBaseDatabaseApi::class.java)

    val cloudBaseStorageService: CloudBaseStorageApi = Retrofit.Builder()
        .baseUrl(CLOUDBASE_BASE_URL)
        .client(cloudBaseOkHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(CloudBaseStorageApi::class.java)

}
