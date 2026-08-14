package com.momo.app.data.database

import kotlinx.serialization.json.JsonObject
import retrofit2.http.*

interface CloudBaseDatabaseApi {

    @POST("v1/model/prod/{modelName}/create")
    suspend fun create(
        @Header("Authorization") authorization: String,
        @Path("modelName") modelName: String,
        @Body body: JsonObject
    ): CloudBaseDbCreateResponse

    @POST("v1/model/prod/{modelName}/list")
    suspend fun list(
        @Header("Authorization") authorization: String,
        @Path("modelName") modelName: String,
        @Body request: CloudBaseDbListRequest
    ): CloudBaseDbListResponse

    @POST("v1/model/prod/{modelName}/upsert")
    suspend fun upsert(
        @Header("Authorization") authorization: String,
        @Path("modelName") modelName: String,
        @Body request: CloudBaseDbUpsertRequest
    ): CloudBaseDbSimpleResponse

    @DELETE("v1/model/prod/{modelName}/{recordId}/delete")
    suspend fun deleteById(
        @Header("Authorization") authorization: String,
        @Path("modelName") modelName: String,
        @Path("recordId") recordId: String
    ): CloudBaseDbSimpleResponse

    @POST("v1/model/prod/{modelName}/delete")
    suspend fun deleteByFilter(
        @Header("Authorization") authorization: String,
        @Path("modelName") modelName: String,
        @Body request: CloudBaseDbDeleteByFilterRequest
    ): CloudBaseDbSimpleResponse

    @PUT("v1/model/prod/{modelName}/update")
    suspend fun updateByFilter(
        @Header("Authorization") authorization: String,
        @Path("modelName") modelName: String,
        @Body request: CloudBaseDbUpdateByFilterRequest
    ): CloudBaseDbSimpleResponse
}
