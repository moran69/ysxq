package com.ysxq.app.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.ysxq.app.data.auth.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Uploads files to CloudBase Storage via a cloud function proxy.
 *
 * Why cloud function? The storage bucket permission is set to
 * "仅创建者及管理员可读写" on the free tier (cannot be changed).
 * The V1 HTTP API get-objects-upload-info is subject to these rules
 * and returns empty/null upload URLs for normal users.
 *
 * Cloud functions run with admin privileges and bypass all storage
 * security rules — this is the officially recommended workaround
 * per CloudBase FAQ.
 *
 * Flow:
 *   Android (Base64 image) → HTTP API → Cloud Function → node-sdk uploadFile() → Cloud Storage
 *
 * The cloud function "uploadAvatar" must be deployed in the CloudBase console.
 */
class CloudBaseStorageHelper(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    companion object {
        private const val TAG = "CloudBaseStorage"
        private const val ENV_ID = "yingshi-8gu7ost293ff515a"
        private const val API_GATEWAY = "https://$ENV_ID.api.tcloudbasegateway.com"
        private const val FUNCTION_NAME = "uploadAvatar"

        /** Max avatar dimension before compression */
        private const val MAX_AVATAR_DIMENSION = 800
        /** JPEG quality for compressed avatar */
        private const val JPEG_QUALITY = 80
        /** Max avatar file size: 5 MB */
        private const val MAX_FILE_SIZE = 5 * 1024 * 1024
    }

    /**
     * Upload avatar image to CloudBase Storage via cloud function.
     *
     * The image is compressed, converted to Base64, and sent to the
     * "uploadAvatar" cloud function which uploads it with admin privileges.
     *
     * @param fileUri local URI of the selected image
     * @param uid user ID used as part of the cloud storage path
     * @return Result with the download URL on success
     */
    suspend fun uploadAvatar(fileUri: Uri, uid: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val accessToken = AuthRepository.getAccessToken()
                ?: return@withContext Result.failure(Exception("未登录"))

            // Step 1: Compress and convert to Base64
            val compressed = compressImage(fileUri)
                ?: return@withContext Result.failure(Exception("图片处理失败"))

            if (compressed.size > MAX_FILE_SIZE) {
                return@withContext Result.failure(
                    Exception("图片过大 (${compressed.size / 1024}KB)，最大支持 ${MAX_FILE_SIZE / 1024 / 1024}MB")
                )
            }

            val base64Data = Base64.encodeToString(compressed, Base64.NO_WRAP)
            val fileName = "avatar_${System.currentTimeMillis()}.jpg"

            Log.d(TAG, "调用云函数上传头像: uid=$uid, fileName=$fileName, size=${compressed.size}")

            // Step 2: Call cloud function via HTTP API
            val downloadUrl = invokeCloudFunction(accessToken, base64Data, fileName, uid)

            Log.d(TAG, "头像上传成功: $downloadUrl")
            Result.success(downloadUrl)
        } catch (e: Exception) {
            Log.e(TAG, "上传头像失败", e)
            Result.failure(e)
        }
    }

    suspend fun uploadDefaultAvatar(uid: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val accessToken = AuthRepository.getAccessToken()
                ?: return@withContext Result.failure(Exception("未登录"))

            val inputStream = context.resources.openRawResource(com.ysxq.app.R.raw.default_avatar)
            val bytes = inputStream.readBytes()
            inputStream.close()

            val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val fileName = "avatar_default_${uid}.jpg"

            Log.d(TAG, "上传默认头像: uid=$uid")

            val downloadUrl = invokeCloudFunction(accessToken, base64Data, fileName, uid)

            Log.d(TAG, "默认头像上传成功: $downloadUrl")
            Result.success(downloadUrl)
        } catch (e: Exception) {
            Log.e(TAG, "上传默认头像失败", e)
            Result.failure(e)
        }
    }

    /**
     * Invoke the "uploadAvatar" cloud function via HTTP API.
     *
     * POST {API_GATEWAY}/v1/functions/{FUNCTION_NAME}
     * Authorization: Bearer <access_token>
     * Body: { "base64Data": "...", "fileName": "...", "uid": "..." }
     *
     * Cloud function returns:
     * { "code": 0, "data": { "downloadURL": "...", "fileID": "...", "cloudPath": "..." } }
     * or
     * { "code": -1, "message": "error description" }
     */
    private fun invokeCloudFunction(
        accessToken: String,
        base64Data: String,
        fileName: String,
        uid: String
    ): String {
        val requestJson = """
            {
                "base64Data": ${json.encodeToString(kotlinx.serialization.serializer<String>(), base64Data)},
                "fileName": ${json.encodeToString(kotlinx.serialization.serializer<String>(), fileName)},
                "uid": ${json.encodeToString(kotlinx.serialization.serializer<String>(), uid)}
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("$API_GATEWAY/v1/functions/$FUNCTION_NAME")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        try {
            val body = response.body?.string()
                ?: throw Exception("云函数响应为空")

            Log.d(TAG, "云函数响应 (${response.code}): ${body.take(500)}")

            if (!response.isSuccessful) {
                throw Exception("云函数调用失败: HTTP ${response.code}")
            }

            // Parse the response
            val result: CloudFunctionResponse = json.decodeFromString(body)

            if (result.code != 0) {
                throw Exception(result.message ?: "云函数返回错误 (code=${result.code})")
            }

            val data = result.data
                ?: throw Exception("云函数返回数据为空")

            return data.downloadURL
                ?: throw Exception("云函数未返回下载链接")
        } finally {
            response.close()
        }
    }

    /**
     * Compress image from URI to JPEG byte array.
     * Scales down to MAX_AVATAR_DIMENSION and compresses with JPEG_QUALITY.
     */
    private fun compressImage(uri: Uri): ByteArray? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream) ?: return null
            inputStream.close()

            // Scale down if needed
            val ratio = minOf(
                MAX_AVATAR_DIMENSION.toFloat() / bitmap.width,
                MAX_AVATAR_DIMENSION.toFloat() / bitmap.height,
                1f
            )
            val scaled = if (ratio < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt(),
                    (bitmap.height * ratio).toInt(),
                    true
                ).also { bitmap.recycle() }
            } else {
                bitmap
            }

            // Compress to JPEG
            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            scaled.recycle()

            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "图片压缩失败", e)
            null
        }
    }

    // ========== Cloud Function Response Models ==========

    @Serializable
    data class CloudFunctionResponse(
        val code: Int = -1,
        val message: String? = null,
        val data: CloudFunctionData? = null
    )

    @Serializable
    data class CloudFunctionData(
        val fileID: String? = null,
        val downloadURL: String? = null,
        val cloudPath: String? = null
    )
}
