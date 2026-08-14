package com.momo.app.data.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ========== JWT Login ==========

@Serializable
data class MacCmsJwtResponse(
    val code: Int = 0,
    val msg: String = "",
    val info: MacCmsJwtInfo? = null
)

@Serializable
data class MacCmsJwtInfo(
    @SerialName("token_type") val tokenType: String = "",
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0
)

// ========== User Info (auth/me) ==========

@Serializable
data class MacCmsMeResponse(
    val code: Int = 0,
    val msg: String = "",
    val info: MacCmsUserInfo? = null
)

@Serializable
data class MacCmsUserInfo(
    @SerialName("is_login") val isLogin: Int = 0,
    @SerialName("user_id") val userId: Int = 0,
    @SerialName("user_name") val userName: String = "",
    @SerialName("nick_name") val nickName: String = "",
    @SerialName("group_id") val groupId: Int = 0,
    @SerialName("group_name") val groupName: String = "",
    @SerialName("points") val points: Int = 0,
    @SerialName("user_portrait") val userPortrait: String = "",
    @SerialName("vip_expire_time") val vipExpireTime: Long = 0
)

// ========== Register ==========

@Serializable
data class MacCmsSimpleResponse(
    val code: Int = 0,
    val msg: String = ""
)
