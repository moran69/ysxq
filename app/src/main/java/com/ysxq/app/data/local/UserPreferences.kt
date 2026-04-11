package com.ysxq.app.data.local

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ysxq.app.data.auth.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 用户偏好存储，基于 DataStore Preferences
 * 持久化登录状态、用户信息、游客标记
 */
class UserPreferences(private val dataStore: DataStore<Preferences>) {

    companion object {
        private val KEY_IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
        private val KEY_IS_GUEST = booleanPreferencesKey("is_guest")
        private val KEY_USER_UID = stringPreferencesKey("user_uid")
        private val KEY_USER_EMAIL = stringPreferencesKey("user_email")
        private val KEY_USER_PHONE = stringPreferencesKey("user_phone")
        private val KEY_USER_NICKNAME = stringPreferencesKey("user_nickname")
        private val KEY_USER_AVATAR_URL = stringPreferencesKey("user_avatar_url")
        private val KEY_USER_EMAIL_VERIFIED = booleanPreferencesKey("user_email_verified")
        private val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val KEY_LOCAL_AVATAR_URI = stringPreferencesKey("local_avatar_uri")
    }

    val isLoggedIn: Flow<Boolean> = dataStore.data.map { it[KEY_IS_LOGGED_IN] ?: false }

    val isGuest: Flow<Boolean> = dataStore.data.map { it[KEY_IS_GUEST] ?: false }

    val userInfo: Flow<User?> = dataStore.data.map { prefs ->
        val uid = prefs[KEY_USER_UID]
        if (uid != null) {
            User(
                uid = uid,
                email = prefs[KEY_USER_EMAIL] ?: "",
                phone = prefs[KEY_USER_PHONE] ?: "",
                displayName = prefs[KEY_USER_NICKNAME] ?: "",
                photoUrl = prefs[KEY_USER_AVATAR_URL],
                isEmailVerified = prefs[KEY_USER_EMAIL_VERIFIED] ?: false
            )
        } else null
    }

    val accessToken: Flow<String?> = dataStore.data.map { it[KEY_ACCESS_TOKEN] }

    val refreshToken: Flow<String?> = dataStore.data.map { it[KEY_REFRESH_TOKEN] }

    val localAvatarUri: Flow<Uri?> = dataStore.data.map { prefs ->
        prefs[KEY_LOCAL_AVATAR_URI]?.let { Uri.parse(it) }
    }

    suspend fun saveUserLogin(user: User, accessToken: String? = null, refreshToken: String? = null) {
        dataStore.edit { prefs ->
            prefs[KEY_IS_LOGGED_IN] = true
            prefs[KEY_IS_GUEST] = false
            prefs[KEY_USER_UID] = user.uid
            prefs[KEY_USER_EMAIL] = user.email
            prefs[KEY_USER_PHONE] = user.phone
            prefs[KEY_USER_NICKNAME] = user.displayName
            if (user.photoUrl != null) {
                prefs[KEY_USER_AVATAR_URL] = user.photoUrl
            } else {
                prefs.remove(KEY_USER_AVATAR_URL)
            }
            prefs[KEY_USER_EMAIL_VERIFIED] = user.isEmailVerified
            accessToken?.let { prefs[KEY_ACCESS_TOKEN] = it }
            refreshToken?.let { prefs[KEY_REFRESH_TOKEN] = it }
        }
    }

    suspend fun markAsGuest() {
        dataStore.edit { prefs ->
            prefs[KEY_IS_GUEST] = true
            prefs[KEY_IS_LOGGED_IN] = false
        }
    }

    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    suspend fun saveLocalAvatarUri(uri: Uri) {
        dataStore.edit { prefs ->
            prefs[KEY_LOCAL_AVATAR_URI] = uri.toString()
        }
    }

    suspend fun clearLocalAvatarUri() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_LOCAL_AVATAR_URI)
        }
    }
}

/**
 * DataStore 单例，跟随 Application 生命周期
 */
private val Context.userDataStore by preferencesDataStore(name = "user_prefs")

/**
 * 获取 UserPreferences 实例
 */
fun Context.userPreferences(): UserPreferences {
    return UserPreferences(userDataStore)
}