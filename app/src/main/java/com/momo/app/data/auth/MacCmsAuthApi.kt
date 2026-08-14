package com.momo.app.data.auth

import retrofit2.http.*

/**
 * 苹果CMS 会员认证 API（JWT 方式，与网站同一套用户体系）
 *
 * 端点：
 *  - POST /api.php/auth/jwt         用户名+密码换取 JWT token
 *  - GET  /api.php/auth/me          用 Bearer token 获取当前用户信息
 *  - POST /api.php/user/register    注册新用户（user_name + user_pwd + user_pwd2）
 */
interface MacCmsAuthApi {

    @POST("api.php/auth/jwt")
    @FormUrlEncoded
    suspend fun jwtLogin(
        @Field("user_name") userName: String,
        @Field("user_pwd") userPwd: String
    ): MacCmsJwtResponse

    @GET("api.php/auth/me")
    suspend fun getMe(
        @Header("Authorization") authorization: String
    ): MacCmsMeResponse

    @POST("api.php/user/register")
    @FormUrlEncoded
    suspend fun register(
        @Field("user_name") userName: String,
        @Field("user_pwd") userPwd: String,
        @Field("user_pwd2") userPwd2: String
    ): MacCmsSimpleResponse

    /**
     * 更新个人资料（昵称/邮箱/手机/QQ/密码），需 Bearer token
     * POST /api.php/user/update_info
     */
    @POST("api.php/user/update_info")
    @FormUrlEncoded
    suspend fun updateInfo(
        @Header("Authorization") authorization: String,
        @Field("user_nick_name") nickName: String?
    ): MacCmsSimpleResponse

    /**
     * Cookie 会话换取 JWT（APP WebView 完成 L站等第三方 OAuth 后调用）
     * GET /api.php/auth/oauth_jwt
     * 请求头：Cookie: user_id=...; user_name=...; user_check=...
     */
    @GET("api.php/auth/oauth_jwt")
    suspend fun oauthJwt(
        @Header("Cookie") cookie: String
    ): MacCmsJwtResponse
}
