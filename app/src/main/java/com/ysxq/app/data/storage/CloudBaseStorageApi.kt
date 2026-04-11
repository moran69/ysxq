package com.ysxq.app.data.storage

import kotlinx.serialization.Serializable
import retrofit2.http.*

interface CloudBaseStorageApi {

    @POST("v1/storages/get-objects-download-info")
    suspend fun getDownloadUrls(
        @Header("Authorization") authorization: String,
        @Body fileIds: List<String>
    ): List<DownloadInfoItem>
}

@Serializable
data class DownloadInfoItem(
    val cloudObjectId: String? = null,
    val downloadUrl: String? = null,
    val downloadUrlEncoded: String? = null,
    val code: String? = null,
    val message: String? = null
)
