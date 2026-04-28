package com.ysxq.app.data.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.ysxq.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class AppVersionInfo(
    val versionName: String,
    val apkDownloadUrl: String,
    val forceUpdate: Boolean,
    val updateLog: String
)

class UpdateChecker {
    companion object {
        private const val TAG = "UpdateChecker"
        private const val PGYER_API_KEY = "110c85680267a820f9ba5b6ff12a05aa"
        private const val PGYER_APP_KEY = "d14c6ce22562476b86fd46f4c6cf15b0"
        private const val PGYER_CHECK_URL = "https://api.pgyer.com/apiv2/app/check"

        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        suspend fun checkForUpdate(): AppVersionInfo? = withContext(Dispatchers.IO) {
            try {
                val formBody = FormBody.Builder()
                    .add("_api_key", PGYER_API_KEY)
                    .add("appKey", PGYER_APP_KEY)
                    .add("buildVersion", BuildConfig.VERSION_NAME)
                    .build()

                val request = Request.Builder()
                    .url(PGYER_CHECK_URL)
                    .post(formBody)
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    Log.w(TAG, "蒲公英 API 返回 ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                response.close()

                val json = JSONObject(body)
                if (json.optInt("code", -1) != 0) {
                    Log.w(TAG, "蒲公英 API 错误: ${json.optString("message", "unknown")}")
                    return@withContext null
                }

                val data = json.optJSONObject("data") ?: return@withContext null

                if (!data.optBoolean("buildHaveNewVersion", false)) {
                    return@withContext null
                }

                val newVersionName = data.optString("buildVersion", "").trim()
                val downloadUrl = data.optString("downloadURL", "").trim()
                val updateLog = data.optString("buildUpdateDescription", "").trim()

                if (newVersionName.isEmpty() || downloadUrl.isEmpty()) {
                    Log.w(TAG, "蒲公英返回数据不完整")
                    return@withContext null
                }

                Log.i(TAG, "发现新版本: $newVersionName")
                AppVersionInfo(
                    versionName = newVersionName,
                    apkDownloadUrl = downloadUrl,
                    forceUpdate = data.optBoolean("needForceUpdate", false),
                    updateLog = updateLog
                )
            } catch (e: Exception) {
                Log.w(TAG, "版本检查失败: ${e.message}")
                null
            }
        }

        suspend fun downloadApk(
            context: Context,
            downloadUrl: String,
            onProgress: (Float) -> Unit
        ): File? = withContext(Dispatchers.IO) {
            try {
                val downloadClient = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder().url(downloadUrl).build()
                val response = downloadClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    response.close()
                    return@withContext null
                }

                val body = response.body ?: return@withContext null
                val contentLength = body.contentLength()

                val apkFile = File(context.cacheDir, "update_${System.currentTimeMillis()}.apk")

                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Long = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            bytesRead += read
                            if (contentLength > 0) {
                                onProgress(bytesRead.toFloat() / contentLength)
                            }
                        }
                    }
                }

                response.close()
                apkFile
            } catch (e: Exception) {
                Log.e(TAG, "下载APK失败: ${e.message}")
                null
            }
        }

        fun installApk(context: Context, apkFile: File) {
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "安装APK失败: ${e.message}")
            }
        }
    }
}
