package com.ysxq.app.data.auth

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 用户数据模型
 */
data class User(
    val uid: String = "",
    val email: String = "",
    val phone: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    val isEmailVerified: Boolean = false
)

/**
 * 认证状态
 */
sealed class AuthState {
    data object Loading : AuthState()
    data class Authenticated(val user: User) : AuthState()
    data object Unauthenticated : AuthState()
}

/**
 * UI 请求状态
 */
sealed class UiState<out T> {
    data object Idle : UiState<Nothing>()
    data object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}

data class SendCodeResult(
    val verificationId: String,
    val isUser: Boolean
)

// ========== CloudBase API Request/Response Models ==========

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CloudBaseSendCodeRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("phone_number") val phoneNumber: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("email") val email: String? = null,
    @SerialName("target") val target: String = "ANY"
)

@Serializable
data class CloudBaseVerifyCodeRequest(
    @SerialName("verification_id") val verificationId: String,
    @SerialName("verification_code") val verificationCode: String
)

@Serializable
data class CloudBaseSignInRequest(
    @SerialName("verification_token") val verificationToken: String
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CloudBaseSignUpRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("email") val email: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("phone_number") val phoneNumber: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("password") val password: String? = null,
    @SerialName("verification_token") val verificationToken: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("username") val username: String? = null
)

@Serializable
data class CloudBaseSignInWithPasswordRequest(
    @SerialName("username") val username: String,
    @SerialName("password") val password: String
)

@Serializable
data class CloudBaseRefreshTokenRequest(
    @SerialName("grant_type") val grantType: String = "refresh_token",
    @SerialName("refresh_token") val refreshToken: String
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CloudBaseUpdateProfileRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("nickname") val nickName: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("avatar_url") val avatarUrl: String? = null
)

@Serializable
data class CloudBaseAuthResponse(
    @SerialName("error") val error: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("user") val user: CloudBaseUserInfo? = null
) {
    fun getErrorMessage(): String? = when {
        !errorDescription.isNullOrBlank() -> errorDescription
        !error.isNullOrBlank() -> error
        else -> null
    }
}

@Serializable
data class CloudBaseUserInfo(
    @SerialName("sub") val sub: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("picture") val picture: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("phone_number") val phoneNumber: String? = null,
    @SerialName("email_verified") val emailVerified: Boolean? = null,
    @SerialName("username") val username: String? = null,
    @SerialName("has_password") val hasPassword: Boolean? = null,
    @SerialName("status") val status: String? = null
)

@Serializable
data class CloudBaseSendCodeResponse(
    @SerialName("error") val error: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("verification_id") val verificationId: String? = null,
    @SerialName("is_user") val isUser: Boolean? = null,
    @SerialName("expires_in") val expiresIn: Int? = null
) {
    fun getErrorMessage(): String? = when {
        !errorDescription.isNullOrBlank() -> errorDescription
        !error.isNullOrBlank() -> error
        else -> null
    }
}

@Serializable
data class CloudBaseVerificationResponse(
    @SerialName("error") val error: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("verification_token") val verificationToken: String? = null
) {
    fun getErrorMessage(): String? = when {
        !errorDescription.isNullOrBlank() -> errorDescription
        !error.isNullOrBlank() -> error
        else -> null
    }
}

@Serializable
data class CloudBaseSimpleResponse(
    @SerialName("error") val error: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    @SerialName("error_description") val errorDescription: String? = null
) {
    fun getErrorMessage(): String? = when {
        !errorDescription.isNullOrBlank() -> errorDescription
        !error.isNullOrBlank() -> error
        else -> null
    }
}

@Serializable
data class CloudBaseUserProfileResponse(
    @SerialName("error") val error: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("sub") val sub: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("picture") val picture: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("phone_number") val phoneNumber: String? = null,
    @SerialName("username") val username: String? = null,
    @SerialName("has_password") val hasPassword: Boolean? = null,
    @SerialName("status") val status: String? = null
) {
    fun toUserInfo(): CloudBaseUserInfo? {
        val uid = sub ?: userId ?: return null
        return CloudBaseUserInfo(
            sub = uid,
            userId = userId,
            name = name,
            picture = picture,
            email = email,
            phoneNumber = phoneNumber,
            username = username,
            hasPassword = hasPassword,
            status = status
        )
    }

    fun getErrorMessage(): String? = when {
        !errorDescription.isNullOrBlank() -> errorDescription
        !error.isNullOrBlank() -> error
        else -> null
    }
}
