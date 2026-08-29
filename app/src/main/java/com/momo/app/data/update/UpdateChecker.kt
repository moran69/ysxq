package com.momo.app.data.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.momo.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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
        private const val UPDATE_URL = "http://161.118.252.183/update.json"

        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        // ===== 自动更新：启动时后台检查，结果经 pendingUpdate 驱动全局弹窗 =====
        private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pendingUpdate = MutableStateFlow<AppVersionInfo?>(null)
        @Volatile private var dismissedVersionName = ""

        /** App 启动时调用：后台检查更新，发现新版本（且未被用户点过"稍后"）则置入 pendingUpdate */
        fun autoCheck() {
            updateScope.launch {
                val info = checkForUpdate() ?: return@launch
                if (info.versionName == dismissedVersionName) return@launch
                Log.i(TAG, "自动检查发现新版本: ${info.versionName}")
                pendingUpdate.value = info
            }
        }

        /** 用户点"稍后"：本次运行不再提示（同版本） */
        fun dismissUpdate() {
            pendingUpdate.value?.let { dismissedVersionName = it.versionName }
            pendingUpdate.value = null
        }

        suspend fun checkForUpdate(): AppVersionInfo? = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(UPDATE_URL)
                    .header("Cache-Control", "no-cache")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    response.close()
                    Log.w(TAG, "更新检查失败: HTTP ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                response.close()

                val json = JSONObject(body)
                val serverVersionCode = json.optInt("versionCode", 0)
                val currentVersionCode = BuildConfig.VERSION_CODE

                if (serverVersionCode <= currentVersionCode) {
                    Log.i(TAG, "已是最新版本: ${BuildConfig.VERSION_NAME} (code=$currentVersionCode)")
                    return@withContext null
                }

                val versionName = json.optString("versionName", "").trim()
                val downloadUrl = json.optString("apkUrl", "").trim()
                val updateLog = json.optString("updateLog", "").trim()
                val forceUpdate = json.optBoolean("forceUpdate", false)

                if (versionName.isEmpty() || downloadUrl.isEmpty()) {
                    Log.w(TAG, "更新信息不完整")
                    return@withContext null
                }

                Log.i(TAG, "发现新版本: $versionName (code=$serverVersionCode)")
                AppVersionInfo(
                    versionName = versionName,
                    apkDownloadUrl = downloadUrl,
                    forceUpdate = forceUpdate,
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
