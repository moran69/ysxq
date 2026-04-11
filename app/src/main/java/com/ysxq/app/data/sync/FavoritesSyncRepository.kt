package com.ysxq.app.data.sync

import android.util.Log
import com.ysxq.app.data.NetworkModule
import com.ysxq.app.data.auth.AuthRepository
import com.ysxq.app.data.database.*
import com.ysxq.app.data.local.FavoriteItem
import com.ysxq.app.data.local.FavoritesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class FavoritesSyncRepository(
    private val favoritesStore: FavoritesStore
) {
    private val api = NetworkModule.cloudBaseDatabaseService

    companion object {
        private const val TAG = "FavoritesSync"
        private const val MODEL_NAME = "favorites"
    }

    suspend fun upsertToCloud(item: FavoriteItem) {
        withContext(Dispatchers.IO) {
            val token = AuthRepository.getAccessToken() ?: return@withContext
            val uid = AuthRepository.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return@withContext
            try {
                // Step 1: Query for existing record by uid + videoId
                val existingRecord = findExistingRecord(token, uid, item.id)

                if (existingRecord != null) {
                    // Step 2a: Update existing record using _id filter
                    val recordId = existingRecord["_id"]?.jsonPrimitive?.contentOrNull
                    if (recordId != null) {
                        val updateData = buildJsonObject {
                            put("name", item.name)
                            put("pic", item.pic)
                            put("typeName", item.typeName)
                            put("remarks", item.remarks)
                            put("year", item.year)
                            put("area", item.area)
                            put("addedAt", item.addedAt)
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
                            Log.e(TAG, "云端更新收藏失败: $errMsg")
                        }
                    }
                } else {
                    // Step 2b: Create new record — CloudBase create API requires data wrapped in "data" field
                    val fields = buildJsonObject {
                        put("uid", uid)
                        put("id", item.id)
                        put("name", item.name)
                        put("pic", item.pic)
                        put("typeName", item.typeName)
                        put("remarks", item.remarks)
                        put("year", item.year)
                        put("area", item.area)
                        put("addedAt", item.addedAt)
                    }
                    val createBody = buildJsonObject { put("data", fields) }
                    val response = api.create("Bearer $token", MODEL_NAME, createBody)
                    val errMsg = response.getErrorMessage()
                    if (errMsg != null) {
                        Log.e(TAG, "云端同步收藏失败: $errMsg")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "云端同步收藏失败: ${e.message}")
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
                            "id" to buildJsonObject { put("\$eq", videoId) }
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
            Log.w(TAG, "查询云端收藏失败: ${e.message}")
            null
        }
    }

    suspend fun deleteFromCloud(videoId: Int) {
        withContext(Dispatchers.IO) {
            val token = AuthRepository.getAccessToken() ?: return@withContext
            val uid = AuthRepository.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return@withContext
            try {
                val filter = CloudBaseDbDeleteByFilterRequest(
                    filter = CloudBaseDbFilter(
                        where = mapOf(
                            "uid" to buildJsonObject { put("\$eq", uid) },
                            "id" to buildJsonObject { put("\$eq", videoId) }
                        )
                    )
                )
                val response = api.deleteByFilter("Bearer $token", MODEL_NAME, filter)
                val errMsg = response.getErrorMessage()
                if (errMsg != null) {
                    Log.e(TAG, "云端删除收藏失败: $errMsg")
                }
            } catch (e: Exception) {
                Log.w(TAG, "云端删除收藏失败: ${e.message}")
            }
        }
    }

    suspend fun pullFromCloud() {
        withContext(Dispatchers.IO) {
            val token = AuthRepository.getAccessToken() ?: return@withContext
            val uid = AuthRepository.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return@withContext
            try {
                val response = api.list(
                    "Bearer $token",
                    MODEL_NAME,
                    CloudBaseDbListRequest(
                        filter = CloudBaseDbFilter(
                            where = mapOf("uid" to buildJsonObject { put("\$eq", uid) })
                        ),
                        orderBy = listOf(buildJsonObject { put("addedAt", "desc") }),
                        pageSize = 200,
                        pageNumber = 1,
                        getCount = false
                    )
                )
                val listErr = response.getErrorMessage()
                if (listErr != null) {
                    Log.e(TAG, "从云端拉取收藏失败: $listErr")
                    return@withContext
                }
                val records = response.data?.records ?: return@withContext
                val cloudItems = records.mapNotNull { obj ->
                    try {
                        FavoriteItem(
                            id = obj["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            pic = obj["pic"]?.jsonPrimitive?.contentOrNull ?: "",
                            typeName = obj["typeName"]?.jsonPrimitive?.contentOrNull ?: "",
                            remarks = obj["remarks"]?.jsonPrimitive?.contentOrNull ?: "",
                            year = obj["year"]?.jsonPrimitive?.contentOrNull ?: "",
                            area = obj["area"]?.jsonPrimitive?.contentOrNull ?: "",
                            addedAt = obj["addedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                        )
                    } catch (_: Exception) { null }
                }

                val localItems = favoritesStore.favorites.first()
                val localIds = localItems.map { it.id }.toSet()
                for (cloudItem in cloudItems) {
                    if (cloudItem.id !in localIds) {
                        favoritesStore.addFavorite(cloudItem)
                    }
                }

                val cloudIds = cloudItems.map { it.id }.toSet()
                for (localItem in localItems) {
                    if (localItem.id !in cloudIds) {
                        upsertToCloud(localItem)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "从云端拉取收藏失败: ${e.message}")
            }
        }
    }
}
