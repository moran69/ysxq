package com.momo.app.data.auth

import android.net.Uri
import com.momo.app.App
import com.momo.app.data.NetworkModule
import com.momo.app.data.local.favoritesStore
import com.momo.app.data.local.userPreferences
import com.momo.app.data.local.watchHistoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 认证仓库 —— 对接苹果CMS 会员体系（JWT）。
 *
 * - 登录：POST /api.php/auth/jwt（用户名+密码 -> JWT）
 * - 注册：POST /api.php/user/register
 * - 用户信息：GET /api.php/auth/me（Bearer token）
 * - 修改昵称：POST /api.php/user/update_info
 *
 * 与网站共用 mac_user 表，同一套认证；token 有效期 7 天，无 refresh token。
 */
object AuthRepository {

    private val api = NetworkModule.macCmsAuthService
    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Launch a coroutine that survives ViewModel destruction (e.g., avatar upload after navigation).
     */
    fun launchInBackground(block: suspend CoroutineScope.() -> Unit) {
        backgroundScope.launch(block = block)
    }

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: User? get() = _currentUser.value

    val isLoggedIn: Boolean get() = _currentUser.value != null

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    private val accessTokenHolder = MutableStateFlow<String?>(null)
    @Volatile
    private var tokenExpiresAt: Long = 0L

    private val _logoutReason = MutableStateFlow<String?>(null)
    val logoutReason: Flow<String?> = _logoutReason.asStateFlow()

    /**
     * Set when session expires unexpectedly (e.g. token expired).
     * Read by AuthScreen to show a message. Cleared after display.
     */
    @Volatile
    var sessionExpiredMessage: String? = null
        private set

    fun consumeSessionExpiredMessage(): String? {
        val msg = sessionExpiredMessage
        sessionExpiredMessage = null
        return msg
    }

    fun consumeLogoutReason(): String? {
        val reason = _logoutReason.value
        _logoutReason.value = null
        return reason
    }

    fun authStateChanges(): Flow<AuthState> = _authState.asStateFlow()

    fun restoreSession(user: User, accessToken: String, refreshToken: String?) {
        _currentUser.value = user
        accessTokenHolder.value = accessToken
        _authState.value = AuthState.Authenticated(user)
    }

    /**
     * 用户名+密码登录，换取 JWT 并拉取用户信息。
     */
    suspend fun signInWithPassword(username: String, password: String): Result<User> {
        return try {
            val response = api.jwtLogin(userName = username, userPwd = password)
            if (response.code != 1) {
                return Result.failure(Exception(mapMacCmsError(response.code, response.msg)))
            }
            val info = response.info
                ?: return Result.failure(Exception("未获取到令牌"))
            val token = info.accessToken
            if (token.isBlank()) {
                return Result.failure(Exception("未获取到访问令牌"))
            }
            accessTokenHolder.value = token
            val ttl = if (info.expiresIn > 0) info.expiresIn else 604800L
            tokenExpiresAt = System.currentTimeMillis() + ttl * 1000L
            fetchUserProfile()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(mapError(e))
        }
    }

    /**
     * 用网站 Cookie 会话换取 JWT 并登录。
     * APP WebView 完成 L站等第三方 OAuth 授权后，网站端已写入登录 Cookie
     * （user_id / user_name / user_check），调用 /api.php/auth/oauth_jwt 换 JWT。
     */
    suspend fun signInWithCookie(cookie: String): Result<User> {
        if (cookie.isBlank()) {
            return Result.failure(Exception("未获取到登录状态"))
        }
        return try {
            val response = api.oauthJwt(cookie = cookie)
            if (response.code != 1) {
                return Result.failure(Exception(response.msg.ifBlank { "登录失败" }))
            }
            val info = response.info
                ?: return Result.failure(Exception("未获取到令牌"))
            val token = info.accessToken
            if (token.isBlank()) {
                return Result.failure(Exception("未获取到访问令牌"))
            }
            accessTokenHolder.value = token
            val ttl = if (info.expiresIn > 0) info.expiresIn else 604800L
            tokenExpiresAt = System.currentTimeMillis() + ttl * 1000L
            fetchUserProfile()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(mapError(e))
        }
    }

    /**
     * 注册新用户（与网站同一 mac_user 表），注册成功后自动登录。
     */
    suspend fun register(username: String, password: String): Result<User> {
        return try {
            val response = api.register(
                userName = username,
                userPwd = password,
                userPwd2 = password
            )
            if (response.code != 1) {                return Result.failure(Exception(mapMacCmsError(response.code, response.msg)))
            }
            signInWithPassword(username, password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(mapError(e))
        }
    }

    /**
     * 重新拉取当前用户信息（启动恢复 / 登录成功后）。
     */
    suspend fun reloadUser(): Result<User> {
        return try {
            val token = accessTokenHolder.value
                ?: return Result.failure(Exception("用户未登录"))
            val response = api.getMe(authorization = "Bearer $token")
            if (response.code != 1) {
                return Result.failure(Exception(response.msg.ifBlank { "获取用户信息失败" }))
            }
            val info = response.info
                ?: return Result.failure(Exception("获取用户信息失败"))
            if (info.isLogin != 1) {
                return Result.failure(Exception("用户未登录"))
            }
            val user = info.toDomainUser()
            _currentUser.value = user
            _authState.value = AuthState.Authenticated(user)
            persistUser(user)
            Result.success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(mapError(e))
        }
    }

    /**
     * 更新个人资料。昵称同步到 MacCMS；头像仅本地保存（MacCMS 无头像 API）。
     */
    suspend fun updateProfile(displayName: String? = null, photoUri: Uri? = null): Result<User> {
        return try {
            val token = accessTokenHolder.value
                ?: return Result.failure(Exception("用户未登录"))
            if (!displayName.isNullOrBlank()) {
                val response = api.updateInfo(authorization = "Bearer $token", nickName = displayName)
                if (response.code != 1) {
                    return Result.failure(Exception("更新资料失败: ${response.msg}"))
                }
            }
            val currentUser = _currentUser.value ?: return Result.failure(Exception("用户未登录"))
            val updatedUser = currentUser.copy(
                displayName = displayName ?: currentUser.displayName,
                photoUrl = photoUri?.toString() ?: currentUser.photoUrl
            )
            _currentUser.value = updatedUser
            _authState.value = AuthState.Authenticated(updatedUser)
            persistUser(updatedUser)
            Result.success(updatedUser)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(mapError(e))
        }
    }

    suspend fun signOut(reason: String? = null) {
        _currentUser.value = null
        accessTokenHolder.value = null
        tokenExpiresAt = 0L
        _authState.value = AuthState.Unauthenticated
        if (reason != null) {
            _logoutReason.value = reason
            sessionExpiredMessage = reason
        }
        val app = App.instance
        if (app != null) {
            try {
                app.userPreferences().clearAll()
                app.favoritesStore().clearAll()
                app.watchHistoryStore().clearAll()
            } catch (_: Exception) { }
        }
    }

    fun getAccessToken(): String? = accessTokenHolder.value
    fun getRefreshToken(): String? = null

    private val refreshMutex = Mutex()

    /**
     * Get a valid access token, proactively refreshing if near expiry.
     * JWT 无 refresh token：未过期直接返回；已过期则登出。
     */
    suspend fun getValidAccessToken(): String? {
        val current = accessTokenHolder.value
        if (current != null && !isTokenExpiringSoon()) return current
        return refreshAccessToken()
    }

    suspend fun refreshAccessToken(): String? = refreshMutex.withLock {
        // Double-check after acquiring lock
        val current = accessTokenHolder.value
        if (current != null && !isTokenExpiringSoon()) return current
        if (current != null) {
            // JWT 过期，需要重新登录
            signOut(reason = "登录已过期，请重新登录")
        }
        null
    }

    private fun isTokenExpiringSoon(): Boolean {
        if (tokenExpiresAt == 0L) return true
        return System.currentTimeMillis() >= tokenExpiresAt - 60_000L
    }

    private suspend fun fetchUserProfile(): Result<User> {
        val token = accessTokenHolder.value
            ?: return Result.failure(Exception("未获取到访问令牌"))
        return try {
            val response = api.getMe(authorization = "Bearer $token")
            if (response.code != 1) {
                return Result.failure(Exception(response.msg.ifBlank { "获取用户信息失败" }))
            }
            val info = response.info
                ?: return Result.failure(Exception("获取用户信息失败"))
            if (info.isLogin != 1) {
                return Result.failure(Exception("用户未登录"))
            }
            val user = info.toDomainUser()
            _currentUser.value = user
            _authState.value = AuthState.Authenticated(user)
            persistUser(user)
            Result.success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(mapError(e))
        }
    }

    private suspend fun persistUser(user: User) {
        val app = App.instance ?: return
        try {
            val prefs = app.userPreferences()
            prefs.saveUserLogin(user, accessTokenHolder.value, null)
        } catch (_: Exception) { }
    }

    /**
     * 将 MacCMS 用户信息映射为 APP 领域模型。
     * group_id >= 3 且未过期视为 VIP。
     */
    private fun MacCmsUserInfo.toDomainUser(): User {
        val displayName = nickName.ifBlank { userName.ifBlank { "用户" } }
        val nowSec = System.currentTimeMillis() / 1000
        val isVip = groupId >= 3 && vipExpireTime > nowSec
        return User(
            uid = userId.toString(),
            email = "",
            phone = "",
            displayName = displayName,
            photoUrl = userPortrait.ifBlank { null },
            isEmailVerified = true,
            isVip = isVip,
            vipExpireTime = if (vipExpireTime > 0) vipExpireTime * 1000L else 0L
        )
    }

    private fun mapMacCmsError(code: Int, msg: String): String {
        // 1003 在登录场景 = 用户不存在或密码错误（MacCMS 统一返回 not_found）
        if (code == 1003) return "用户名或密码错误"
        return when {
            msg.isNotBlank() -> msg
            code == 1005 -> "该账号已被注册"
            code == 1009 -> "今日注册次数已达上限"
            code == 1401 -> "请先登录"
            else -> "操作失败（$code）"
        }
    }

    private fun mapError(e: Exception): Exception {
        val message = e.message ?: return Exception("未知错误")
        return when {
            message.contains("ConnectException", ignoreCase = true) ||
            message.contains("SocketTimeoutException", ignoreCase = true) ||
            message.contains("Unable to resolve host", ignoreCase = true) ||
            message.contains("Failed to connect", ignoreCase = true) ->
                Exception("网络连接失败，请检查网络")
            message.contains("timeout", ignoreCase = true) ->
                Exception("网络超时，请重试")
            message.contains("401", ignoreCase = true) ->
                Exception("认证失败，请重新登录")
            else -> Exception(message)
        }
    }
}
