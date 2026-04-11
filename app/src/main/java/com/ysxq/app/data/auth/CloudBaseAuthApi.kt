package com.ysxq.app.data.auth

import retrofit2.http.*

interface CloudBaseAuthApi {

    @POST("auth/v1/verification")
    suspend fun sendVerificationCode(
        @Header("x-device-id") deviceId: String,
        @Body request: CloudBaseSendCodeRequest
    ): CloudBaseSendCodeResponse

    @POST("auth/v1/verification/verify")
    suspend fun verifyCode(
        @Body request: CloudBaseVerifyCodeRequest
    ): CloudBaseVerificationResponse

    @POST("auth/v1/signin")
    suspend fun signInWithToken(
        @Header("x-device-id") deviceId: String,
        @Body request: CloudBaseSignInRequest
    ): CloudBaseAuthResponse

    @POST("auth/v1/signin")
    suspend fun signInWithPassword(
        @Header("x-device-id") deviceId: String,
        @Body request: CloudBaseSignInWithPasswordRequest
    ): CloudBaseAuthResponse

    @POST("auth/v1/signup")
    suspend fun signUp(
        @Header("x-device-id") deviceId: String,
        @Body request: CloudBaseSignUpRequest
    ): CloudBaseAuthResponse

    @POST("auth/v1/user/signout")
    suspend fun signOut(
        @Header("Authorization") authorization: String
    ): CloudBaseSimpleResponse

    @POST("auth/v1/token")
    suspend fun refreshToken(
        @Body request: CloudBaseRefreshTokenRequest
    ): CloudBaseAuthResponse

    @GET("auth/v1/user/me")
    suspend fun getUserProfile(
        @Header("Authorization") authorization: String
    ): CloudBaseUserProfileResponse

    @POST("auth/v1/user/basic/edit")
    suspend fun updateProfile(
        @Header("Authorization") authorization: String,
        @Header("x-device-id") deviceId: String,
        @Body request: CloudBaseUpdateProfileRequest
    ): CloudBaseSimpleResponse
}
