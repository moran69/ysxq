package com.ysxq.app.data.sync

import android.util.Log
import com.ysxq.app.data.NetworkModule
import com.ysxq.app.data.auth.AuthRepository
import com.ysxq.app.data.database.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class ProfileSyncRepository {
    private val api = NetworkModule.cloudBaseDatabaseService

    companion object {
        private const val TAG = "ProfileSync"
        private const val MODEL_NAME = "user_profiles"
    }

    suspend fun saveAvatarToCloud(avatarUrl: String) {
        withContext(Dispatchers.IO) {
            val token = AuthRepository.getAccessToken() ?: return@withContext
            val uid = AuthRepository.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return@withContext
            try {
                val filter = buildStringEqFilter("uid" to uid)
                val create = buildJsonObject {
                    put("uid", uid)
                    put("avatarUrl", avatarUrl)
                }
                val update = buildJsonObject {
                    put("avatarUrl", avatarUrl)
                }
                val response = api.upsert(
                    "Bearer $token",
                    MODEL_NAME,
                    CloudBaseDbUpsertRequest(filter = filter, create = create, update = update)
                )
                val errMsg = response.getErrorMessage()
                if (errMsg != null) {
                    Log.e(TAG, "云端保存头像失败: $errMsg")
                }
            } catch (e: Exception) {
                Log.w(TAG, "云端保存头像失败: ${e.message}")
            }
        }
    }

    suspend fun saveDisplayNameToCloud(displayName: String) {
        withContext(Dispatchers.IO) {
            val token = AuthRepository.getAccessToken() ?: return@withContext
            val uid = AuthRepository.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return@withContext
            try {
                val filter = buildStringEqFilter("uid" to uid)
                val create = buildJsonObject {
                    put("uid", uid)
                    put("displayName", displayName)
                }
                val update = buildJsonObject {
                    put("displayName", displayName)
                }
                val response = api.upsert(
                    "Bearer $token",
                    MODEL_NAME,
                    CloudBaseDbUpsertRequest(filter = filter, create = create, update = update)
                )
                val errMsg = response.getErrorMessage()
                if (errMsg != null) {
                    Log.e(TAG, "云端保存昵称失败: $errMsg")
                }
            } catch (e: Exception) {
                Log.w(TAG, "云端保存昵称失败: ${e.message}")
            }
        }
    }

    suspend fun loadProfileFromCloud(): Pair<String?, String?>? {
        return withContext(Dispatchers.IO) {
            val token = AuthRepository.getAccessToken() ?: return@withContext null
            val uid = AuthRepository.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return@withContext null
            try {
                val response = api.list(
                    "Bearer $token",
                    MODEL_NAME,
                    CloudBaseDbListRequest(
                        filter = CloudBaseDbFilter(
                            where = mapOf("uid" to buildJsonObject { put("\$eq", uid) })
                        ),
                        pageSize = 1,
                        pageNumber = 1,
                        getCount = false
                    )
                )
                val errMsg = response.getErrorMessage()
                if (errMsg != null) {
                    Log.e(TAG, "从云端加载头像失败: $errMsg")
                    return@withContext null
                }
                val record = response.data?.records?.firstOrNull() ?: return@withContext null
                val avatarUrl = record["avatarUrl"]?.jsonPrimitive?.contentOrNull
                val displayName = record["displayName"]?.jsonPrimitive?.contentOrNull
                Pair(avatarUrl, displayName)
            } catch (e: Exception) {
                Log.w(TAG, "从云端加载头像失败: ${e.message}")
                null
            }
        }
    }
}
