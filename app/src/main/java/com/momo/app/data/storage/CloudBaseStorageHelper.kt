package com.momo.app.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.momo.app.data.NetworkModule
import com.momo.app.data.auth.AuthRepository
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

data class AvatarUploadResult(
    val fileId: String,
    val downloadUrl: String
)

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

        private const val MAX_AVATAR_DIMENSION = 800
        private const val JPEG_QUALITY = 80
        private const val MAX_FILE_SIZE = 5 * 1024 * 1024
        const val MAX_RAW_FILE_SIZE = 20L * 1024 * 1024
        private const val MAX_IMAGE_DIMENSION = 4096

        private const val URL_CACHE_DURATION_MS = 24 * 60 * 60 * 1000L

        private val urlCache = mutableMapOf<String, Pair<String, Long>>()

        suspend fun resolveAvatarUrl(photoUrl: String?): String? {
            if (photoUrl.isNullOrBlank()) return null
            if (!photoUrl.startsWith("cloud://")) return photoUrl

            val cached = urlCache[photoUrl]
            if (cached != null && System.currentTimeMillis() < cached.second) {
                return cached.first
            }

            val token = AuthRepository.getAccessToken() ?: return null
            return try {
                val items = NetworkModule.cloudBaseStorageService.getDownloadUrls(
                    "Bearer $token", listOf(DownloadRequestItem(cloudObjectId = photoUrl))
                )
                val url = items.firstOrNull()?.downloadUrl
                if (url != null) {
                    urlCache[photoUrl] = url to (System.currentTimeMillis() + URL_CACHE_DURATION_MS)
                }
                url
            } catch (e: Exception) {
                Log.w(TAG, "解析头像URL失败: ${e.message}")
                cached?.first
            }
        }

        fun cacheResolvedUrl(fileId: String, downloadUrl: String) {
            urlCache[fileId] = downloadUrl to (System.currentTimeMillis() + URL_CACHE_DURATION_MS)
        }

        fun clearCache() {
            urlCache.clear()
        }
    }

    suspend fun uploadAvatar(fileUri: Uri, uid: String): Result<AvatarUploadResult> = withContext(Dispatchers.IO) {
        try {
            val accessToken = AuthRepository.getAccessToken()
                ?: return@withContext Result.failure(Exception("未登录"))

            val compressed = compressImage(fileUri)
                .getOrElse { return@withContext Result.failure(it) }

            if (compressed.size > MAX_FILE_SIZE) {
                return@withContext Result.failure(
                    Exception("图片过大 (${compressed.size / 1024}KB)，最大支持 ${MAX_FILE_SIZE / 1024 / 1024}MB")
                )
            }

            val base64Data = Base64.encodeToString(compressed, Base64.NO_WRAP)
            val fileName = "avatar_${System.currentTimeMillis()}.jpg"

            Log.d(TAG, "调用云函数上传头像: uid=$uid, fileName=$fileName, size=${compressed.size}")

            val result = invokeCloudFunction(accessToken, base64Data, fileName, uid)

            Log.d(TAG, "头像上传成功: fileId=${result.fileId}")
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "上传头像失败", e)
            Result.failure(e)
        }
    }

    suspend fun uploadDefaultAvatar(uid: String): Result<AvatarUploadResult> = withContext(Dispatchers.IO) {
        try {
            val accessToken = AuthRepository.getAccessToken()
                ?: return@withContext Result.failure(Exception("未登录"))

            val inputStream = context.resources.openRawResource(com.momo.app.R.raw.default_avatar)
            val bytes = inputStream.readBytes()
            inputStream.close()

            val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val fileName = "avatar_default_${uid}.jpg"

            Log.d(TAG, "上传默认头像: uid=$uid")

            val result = invokeCloudFunction(accessToken, base64Data, fileName, uid)

            Log.d(TAG, "默认头像上传成功: fileId=${result.fileId}")
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "上传默认头像失败", e)
            Result.failure(e)
        }
    }

    private fun invokeCloudFunction(
        accessToken: String,
        base64Data: String,
        fileName: String,
        uid: String
    ): AvatarUploadResult {
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

            val result: CloudFunctionResponse = json.decodeFromString(body)

            if (result.code != 0) {
                throw Exception(result.message ?: "云函数返回错误 (code=${result.code})")
            }

            val data = result.data
                ?: throw Exception("云函数返回数据为空")

            val fileId = data.fileID
                ?: throw Exception("云函数未返回fileID")
            val downloadUrl = data.downloadURL
                ?: throw Exception("云函数未返回下载链接")

            return AvatarUploadResult(fileId = fileId, downloadUrl = downloadUrl)
        } finally {
            response.close()
        }
    }

    private fun compressImage(uri: Uri): Result<ByteArray> {
        return try {
            val fileSize = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L
                } else -1L
            } ?: -1L

            if (fileSize > MAX_RAW_FILE_SIZE) {
                return Result.failure(Exception("图片文件过大，请选择小于20MB的图片"))
            }

            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOptions)
            }
            if (boundsOptions.outWidth > MAX_IMAGE_DIMENSION || boundsOptions.outHeight > MAX_IMAGE_DIMENSION) {
                return Result.failure(Exception("图片尺寸过大，请选择小于${MAX_IMAGE_DIMENSION}x${MAX_IMAGE_DIMENSION}的图片"))
            }

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(Exception("无法读取图片"))
            val bitmap = BitmapFactory.decodeStream(inputStream)
                ?: return Result.failure(Exception("图片解码失败"))
            inputStream.close()

            val orientation = context.contentResolver.openInputStream(uri)?.use { exifStream ->
                val exif = ExifInterface(exifStream)
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL

            val rotatedBitmap = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f).also { bitmap.recycle() }
                ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f).also { bitmap.recycle() }
                ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f).also { bitmap.recycle() }
                else -> bitmap
            }

            val ratio = minOf(
                MAX_AVATAR_DIMENSION.toFloat() / rotatedBitmap.width,
                MAX_AVATAR_DIMENSION.toFloat() / rotatedBitmap.height,
                1f
            )
            val scaled = if (ratio < 1f) {
                Bitmap.createScaledBitmap(
                    rotatedBitmap,
                    (rotatedBitmap.width * ratio).toInt(),
                    (rotatedBitmap.height * ratio).toInt(),
                    true
                ).also { rotatedBitmap.recycle() }
            } else {
                rotatedBitmap
            }

            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            scaled.recycle()

            Result.success(outputStream.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "图片压缩失败", e)
            Result.failure(e)
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

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
