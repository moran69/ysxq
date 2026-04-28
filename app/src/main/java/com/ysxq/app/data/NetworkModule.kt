package com.ysxq.app.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.ysxq.app.data.auth.AuthRepository
import com.ysxq.app.data.auth.CloudBaseAuthApi
import com.ysxq.app.data.database.CloudBaseDatabaseApi
import com.ysxq.app.data.storage.CloudBaseStorageApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.Retrofit
import com.ysxq.app.App
import android.content.Context
import java.util.concurrent.TimeUnit

object NetworkModule {

    private const val BASE_URL = "https://cj.lziapi.com/"

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

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(refererInterceptor)
        .build()

    val apiService: ApiService = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ApiService::class.java)

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
