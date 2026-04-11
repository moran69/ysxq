package com.ysxq.app.data

import retrofit2.http.GET
import retrofit2.http.Query

interface ApiService {
    @GET("api.php/provide/vod/")
    suspend fun getVideoList(
        @Query("ac") ac: String = "list",
        @Query("t") typeId: Int? = null,
        @Query("pg") page: Int = 1,
        @Query("wd") keyword: String? = null,
        @Query("area") area: String? = null,
        @Query("year") year: String? = null
    ): ApiResponse

    @GET("api.php/provide/vod/")
    suspend fun getVideoDetail(
        @Query("ac") ac: String = "detail",
        @Query("ids") id: Int
    ): ApiResponse
}
