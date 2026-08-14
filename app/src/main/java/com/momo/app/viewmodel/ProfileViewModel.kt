package com.momo.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.momo.app.data.auth.AuthRepository
import com.momo.app.data.auth.AuthState
import com.momo.app.data.auth.User
import com.momo.app.data.local.userPreferences

import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val user: User? = null,
    val isGuest: Boolean = false,
    val showLogoutDialog: Boolean = false,
    val showEditProfileDialog: Boolean = false,
    val editNickname: String = "",
    val localAvatarUri: Uri? = null,
    val isUpdating: Boolean = false,
    val updateError: String? = null,
    val cacheSize: String = "",
    val isCacheCleared: Boolean = false,
    val sessionExpiredMessage: String? = null,
)

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs by lazy { application.userPreferences() }

    val authState: StateFlow<AuthState> = AuthRepository.authStateChanges()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AuthState.Loading)

    val isGuest: StateFlow<Boolean> = prefs.isGuest
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val resolvedAvatarUrl: StateFlow<String?> = prefs.resolvedAvatarUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authState.collect { state ->
                when (state) {
                    is AuthState.Authenticated -> {
                        val user = state.user
                        _uiState.value = _uiState.value.copy(
                            user = user,
                            isGuest = false,
                            editNickname = user.displayName
                        )
                    }
                    AuthState.Unauthenticated -> {
                        val reason = AuthRepository.consumeLogoutReason()
                        _uiState.value = _uiState.value.copy(
                            user = null,
                            sessionExpiredMessage = reason
                        )
                    }
                    AuthState.Loading -> {}
                }
            }
        }
        viewModelScope.launch {
            prefs.localAvatarUri.collect { uri ->
                _uiState.value = _uiState.value.copy(localAvatarUri = uri)
            }
        }
        viewModelScope.launch {
            isGuest.collect { guest ->
                _uiState.value = _uiState.value.copy(isGuest = guest)
            }
        }
    }

    fun showLogoutDialog() {
        _uiState.value = _uiState.value.copy(showLogoutDialog = true)
    }

    fun dismissLogoutDialog() {
        _uiState.value = _uiState.value.copy(showLogoutDialog = false)
    }

    fun signOut() {
        viewModelScope.launch {
            AuthRepository.signOut()
            _uiState.value = _uiState.value.copy(showLogoutDialog = false)
        }
    }

    fun showEditProfileDialog() {
        _uiState.value = _uiState.value.copy(
            showEditProfileDialog = true,
            editNickname = _uiState.value.user?.displayName ?: "",
            updateError = null
        )
    }

    fun dismissEditProfileDialog() {
        _uiState.value = _uiState.value.copy(showEditProfileDialog = false, isUpdating = false, updateError = null)
    }

    fun onNicknameChange(name: String) {
        _uiState.value = _uiState.value.copy(editNickname = name)
    }

    fun updateNickname() {
        val nickname = _uiState.value.editNickname.trim()
        if (nickname.isBlank()) {
            _uiState.value = _uiState.value.copy(updateError = "昵称不能为空")
            return
        }
        if (nickname.length < 2) {
            _uiState.value = _uiState.value.copy(updateError = "昵称至少需要2个字符")
            return
        }
        if (nickname.length > 48) {
            _uiState.value = _uiState.value.copy(updateError = "昵称不能超过48个字符")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUpdating = true, updateError = null)
            AuthRepository.updateProfile(displayName = nickname)
                .onSuccess { user ->
                    prefs.saveUserLogin(user, AuthRepository.getAccessToken(), AuthRepository.getRefreshToken())
                    _uiState.value = _uiState.value.copy(
                        showEditProfileDialog = false,
                        isUpdating = false
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isUpdating = false,
                        updateError = it.message ?: "更新失败"
                    )
                }
        }
    }

    fun updateAvatar(uri: Uri) {
        viewModelScope.launch {
            prefs.saveLocalAvatarUri(uri)
            _uiState.value = _uiState.value.copy(localAvatarUri = uri)
        }
    }

    fun calculateCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            val cacheDir = getApplication<Application>().cacheDir
            val externalCacheDir = getApplication<Application>().externalCacheDir
            var totalSize = 0L

            cacheDir?.walkTopDown()?.filter { it.isFile }?.forEach { totalSize += it.length() }
            externalCacheDir?.walkTopDown()?.filter { it.isFile }?.forEach { totalSize += it.length() }

            val formatted = when {
                totalSize >= 1024 * 1024 * 1024 -> "%.1f GB".format(totalSize / (1024.0 * 1024 * 1024))
                totalSize >= 1024 * 1024 -> "%.1f MB".format(totalSize / (1024.0 * 1024))
                totalSize >= 1024 -> "%.1f KB".format(totalSize / 1024.0)
                else -> "$totalSize B"
            }

            _uiState.update { it.copy(cacheSize = formatted) }
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()

            val imageLoader = SingletonImageLoader.get(context)
            imageLoader.memoryCache?.clear()
            imageLoader.diskCache?.clear()

            context.cacheDir.deleteRecursively()
            context.externalCacheDir?.deleteRecursively()

            calculateCacheSize()

            _uiState.update { it.copy(isCacheCleared = true) }
        }
    }

    fun dismissCacheCleared() {
        _uiState.update { it.copy(isCacheCleared = false) }
    }

    fun dismissSessionExpired() {
        _uiState.update { it.copy(sessionExpiredMessage = null) }
    }

    fun updatePersistedAvatarUrl(url: String) {
        viewModelScope.launch {
            prefs.saveResolvedAvatarUrl(url)
        }
    }
}
