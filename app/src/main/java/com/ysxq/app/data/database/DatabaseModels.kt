package com.ysxq.app.data.database

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.ExperimentalSerializationApi

@Serializable
data class CloudBaseDbFilter(
    @SerialName("where") val where: Map<String, JsonElement>
)

fun buildEqFilter(vararg pairs: Pair<String, JsonElement>): CloudBaseDbFilter {
    val where = pairs.associate { (key, value) ->
        key to buildJsonObject { put("\$eq", value) }
    }
    return CloudBaseDbFilter(where)
}

fun buildStringEqFilter(vararg pairs: Pair<String, String>): CloudBaseDbFilter =
    buildEqFilter(*pairs.map { (k, v) -> k to JsonPrimitive(v) }.toTypedArray())

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CloudBaseDbListRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("filter") val filter: CloudBaseDbFilter? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("select") val select: JsonObject? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("orderBy") val orderBy: List<JsonObject>? = null,
    @SerialName("pageSize") val pageSize: Int = 100,
    @SerialName("pageNumber") val pageNumber: Int = 1,
    @SerialName("getCount") val getCount: Boolean = true
)

@Serializable
data class CloudBaseDbListResponse(
    @SerialName("data") val data: CloudBaseDbListData? = null,
    @SerialName("requestId") val requestId: String? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("message") val message: String? = null
) {
    fun getErrorMessage(): String? = when {
        !errorDescription.isNullOrBlank() -> errorDescription
        !error.isNullOrBlank() -> error
        !message.isNullOrBlank() && code != null && code != "OK" -> message
        else -> null
    }
}

@Serializable
data class CloudBaseDbListData(
    @SerialName("records") val records: List<JsonObject> = emptyList(),
    @SerialName("total") val total: Int = 0
)

@Serializable
data class CloudBaseDbUpsertRequest(
    @SerialName("filter") val filter: CloudBaseDbFilter,
    @SerialName("create") val create: JsonObject,
    @SerialName("update") val update: JsonObject
)

@Serializable
data class CloudBaseDbDeleteByFilterRequest(
    @SerialName("filter") val filter: CloudBaseDbFilter
)

@Serializable
data class CloudBaseDbUpdateRequest(
    @SerialName("data") val data: JsonObject
)

@Serializable
data class CloudBaseDbUpdateByFilterRequest(
    @SerialName("filter") val filter: CloudBaseDbFilter,
    @SerialName("data") val data: JsonObject
)

@Serializable
data class CloudBaseDbCreateResponse(
    @SerialName("data") val data: CloudBaseDbCreateData? = null,
    @SerialName("requestId") val requestId: String? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("message") val message: String? = null
) {
    fun getErrorMessage(): String? = when {
        !errorDescription.isNullOrBlank() -> errorDescription
        !error.isNullOrBlank() -> error
        !message.isNullOrBlank() && code != null && code != "OK" -> message
        else -> null
    }
}

@Serializable
data class CloudBaseDbCreateData(
    @SerialName("id") val id: String? = null
)

@Serializable
data class CloudBaseDbSimpleResponse(
    @SerialName("data") val data: JsonObject? = null,
    @SerialName("requestId") val requestId: String? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("message") val message: String? = null
) {
    fun getErrorMessage(): String? = when {
        !errorDescription.isNullOrBlank() -> errorDescription
        !error.isNullOrBlank() -> error
        !message.isNullOrBlank() && code != null && code != "OK" -> message
        else -> null
    }
}
