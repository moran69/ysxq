package com.ysxq.app.data.sync

import android.util.Log
import com.ysxq.app.data.NetworkModule
import com.ysxq.app.data.auth.AuthRepository
import com.ysxq.app.data.database.*
import com.ysxq.app.data.local.WatchHistoryEntry
import com.ysxq.app.data.local.WatchHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class WatchHistorySyncRepository(
    private val historyStore: WatchHistoryStore
) {
    private val api = NetworkModule.cloudBaseDatabaseService
    private val json = Json { ignoreUnknownKeys = true }
    private val syncMutex = Mutex()

    companion object {
        private const val TAG = "HistorySync"
        private const val MODEL_NAME = "watch_history"
    }

    suspend fun upsertToCloud(entry: WatchHistoryEntry) {
        withContext(Dispatchers.IO) {
            val token = AuthRepository.getAccessToken() ?: return@withContext
            val uid = AuthRepository.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return@withContext
            try {
                // Step 1: Query for existing record by uid + videoId
                val existingRecord = findExistingRecord(token, uid, entry.videoId)

                if (existingRecord != null) {
                    // Step 2a: Update existing record using _id filter
                    val recordId = existingRecord["_id"]?.jsonPrimitive?.contentOrNull
                    if (recordId != null) {
                        val updateData = buildJsonObject {
                            put("videoName", entry.videoName)
                            put("pic", entry.pic)
                            put("typeName", entry.typeName)
                            put("remarks", entry.remarks)
                            put("episodeIndex", entry.episodeIndex)
                            put("episodeName", entry.episodeName)
                            put("positionMs", entry.positionMs)
                            put("durationMs", entry.durationMs)
                        }
                        val response = api.updateByFilter(
                            "Bearer $token",
                            MODEL_NAME,
                            CloudBaseDbUpdateByFilterRequest(
                                filter = CloudBaseDbFilter(
                                    where = mapOf("_id" to buildJsonObject { put("\$eq", recordId) })
                                ),
                                data = updateData
                            )
                        )
                        val errMsg = response.getErrorMessage()
                        if (errMsg != null) {
                            Log.e(TAG, "云端更新历史失败: $errMsg")
                        }
                    }
                } else {
                    // Step 2b: Create new record — CloudBase create API requires data wrapped in "data" field
                    val fields = buildJsonObject {
                        put("uid", uid)
                        put("videoId", entry.videoId)
                        put("videoName", entry.videoName)
                        put("pic", entry.pic)
                        put("typeName", entry.typeName)
                        put("remarks", entry.remarks)
                        put("episodeIndex", entry.episodeIndex)
                        put("episodeName", entry.episodeName)
                        put("positionMs", entry.positionMs)
                        put("durationMs", entry.durationMs)
                    }
                    val createBody = buildJsonObject { put("data", fields) }
                    val response = api.create("Bearer $token", MODEL_NAME, createBody)
                    val errMsg = response.getErrorMessage()
                    if (errMsg != null) {
                        Log.e(TAG, "云端同步历史失败: $errMsg")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "云端同步历史失败: ${e.message}")
            }
        }
    }

    private suspend fun findExistingRecord(token: String, uid: String, videoId: Int): JsonObject? {
        return try {
            val response = api.list(
                "Bearer $token",
                MODEL_NAME,
                CloudBaseDbListRequest(
                    filter = CloudBaseDbFilter(
                        where = mapOf(
                            "uid" to buildJsonObject { put("\$eq", uid) },
                            "videoId" to buildJsonObject { put("\$eq", videoId) }
                        )
                    ),
                    pageSize = 1,
                    pageNumber = 1,
                    getCount = false
                )
            )
            val records = response.data?.records ?: return null
            records.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "查询云端历史失败: ${e.message}")
            null
        }
    }

    suspend fun deleteFromCloud(videoId: Int) {
        syncMutex.withLock {
            withContext(Dispatchers.IO) {
                val token = AuthRepository.getAccessToken() ?: return@withContext
                val uid = AuthRepository.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return@withContext
                try {
                    val filter = CloudBaseDbDeleteByFilterRequest(
                        filter = CloudBaseDbFilter(
                            where = mapOf(
                                "uid" to buildJsonObject { put("\$eq", uid) },
                                "videoId" to buildJsonObject { put("\$eq", videoId) }
                            )
                        )
                    )
                    val response = api.deleteByFilter("Bearer $token", MODEL_NAME, filter)
                    val errMsg = response.getErrorMessage()
                    if (errMsg != null) {
                        Log.e(TAG, "云端删除历史失败: $errMsg")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "云端删除历史失败: ${e.message}")
                }
            }
        }
    }

    suspend fun clearCloud() {
        syncMutex.withLock {
            withContext(Dispatchers.IO) {
                val token = AuthRepository.getAccessToken() ?: return@withContext
                val uid = AuthRepository.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return@withContext
                try {
                    val filter = CloudBaseDbDeleteByFilterRequest(
                        filter = CloudBaseDbFilter(
                            where = mapOf("uid" to buildJsonObject { put("\$eq", uid) })
                        )
                    )
                    val response = api.deleteByFilter("Bearer $token", MODEL_NAME, filter)
                    val errMsg = response.getErrorMessage()
                    if (errMsg != null) {
                        Log.e(TAG, "云端清空历史失败: $errMsg")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "云端清空历史失败: ${e.message}")
                }
            }
        }
    }

    suspend fun pullFromCloud(): Result<Boolean> {
        return syncMutex.withLock {
            withContext(Dispatchers.IO) {
                val token = AuthRepository.getAccessToken()
                if (token == null) {
                    return@withContext Result.success(false)
                }
                val uid = AuthRepository.currentUser?.uid?.takeIf { it.isNotBlank() }
                if (uid == null) {
                    return@withContext Result.success(false)
                }
                try {
                    val response = api.list(
                        "Bearer $token",
                        MODEL_NAME,
                        CloudBaseDbListRequest(
                            filter = CloudBaseDbFilter(
                                where = mapOf("uid" to buildJsonObject { put("\$eq", uid) })
                            ),
                            orderBy = listOf(buildJsonObject { put("updatedAt", "desc") }),
                            pageSize = 100,
                            pageNumber = 1,
                            getCount = false
                        )
                    )
                    val listErr = response.getErrorMessage()
                    if (listErr != null) {
                        Log.e(TAG, "从云端拉取历史失败: $listErr")
                        return@withContext Result.failure(Exception(listErr))
                    }
                    val records = response.data?.records ?: return@withContext Result.success(true)
                    val cloudEntries = records.mapNotNull { obj ->
                        try {
                            WatchHistoryEntry(
                                videoId = obj["videoId"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                                videoName = obj["videoName"]?.jsonPrimitive?.contentOrNull ?: "",
                                pic = obj["pic"]?.jsonPrimitive?.contentOrNull ?: "",
                                typeName = obj["typeName"]?.jsonPrimitive?.contentOrNull ?: "",
                                remarks = obj["remarks"]?.jsonPrimitive?.contentOrNull ?: "",
                                episodeIndex = obj["episodeIndex"]?.jsonPrimitive?.intOrNull ?: 0,
                                episodeName = obj["episodeName"]?.jsonPrimitive?.contentOrNull ?: "",
                                positionMs = obj["positionMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                                durationMs = obj["durationMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                                updatedAt = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                            )
                        } catch (_: Exception) { null }
                    }

                    val localEntries = historyStore.history.first()
                    val merged = mergeHistory(localEntries, cloudEntries)
                    historyStore.replaceAll(merged)
                    Result.success(true)
                } catch (e: Exception) {
                    Log.w(TAG, "从云端拉取历史失败: ${e.message}")
                    Result.failure(e)
                }
            }
        }
    }

    private fun mergeHistory(
        local: List<WatchHistoryEntry>,
        cloud: List<WatchHistoryEntry>
    ): List<WatchHistoryEntry> {
        val cloudMap = cloud.associateBy { it.videoId }
        val merged = mutableListOf<WatchHistoryEntry>()

        for (localEntry in local) {
            val cloudEntry = cloudMap[localEntry.videoId]
            if (cloudEntry != null) {
                val newer = if (cloudEntry.updatedAt > localEntry.updatedAt) cloudEntry else localEntry
                merged.add(newer)
            } else {
                merged.add(localEntry)
            }
        }

        val localIds = local.map { it.videoId }.toSet()
        for (cloudEntry in cloud) {
            if (cloudEntry.videoId !in localIds) {
                merged.add(cloudEntry)
            }
        }

        return merged.sortedByDescending { it.updatedAt }
    }
}
