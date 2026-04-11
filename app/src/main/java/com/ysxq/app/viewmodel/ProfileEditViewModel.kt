package com.ysxq.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ysxq.app.data.auth.AuthRepository
import com.ysxq.app.data.auth.AuthState
import com.ysxq.app.data.auth.User
import com.ysxq.app.data.local.userPreferences
import com.ysxq.app.data.storage.CloudBaseStorageHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileEditUiState(
    val user: User? = null,
    val editNickname: String = "",
    val localAvatarUri: Uri? = null,
    val cloudAvatarUrl: String? = null,
    val isUploadingAvatar: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saveSuccess: Boolean = false
)

class ProfileEditViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs by lazy { application.userPreferences() }
    private val storageHelper by lazy { CloudBaseStorageHelper(application) }

    val authState: StateFlow<AuthState> = AuthRepository.authStateChanges()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AuthState.Loading)

    private val _uiState = MutableStateFlow(ProfileEditUiState())
    val uiState: StateFlow<ProfileEditUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authState.collect { state ->
                when (state) {
                    is AuthState.Authenticated -> {
                        val user = state.user
                        _uiState.value = _uiState.value.copy(
                            user = user,
                            editNickname = user.displayName,
                            cloudAvatarUrl = user.photoUrl
                        )
                    }
                    AuthState.Unauthenticated -> {
                        _uiState.value = _uiState.value.copy(user = null)
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
    }

    fun onNicknameChange(name: String) {
        _uiState.value = _uiState.value.copy(editNickname = name, error = null)
    }

    fun onAvatarPicked(uri: Uri) {
        _uiState.value = _uiState.value.copy(localAvatarUri = uri, error = null)
        uploadAvatarToCloud(uri)
    }

    private fun uploadAvatarToCloud(uri: Uri) {
        val uid = AuthRepository.currentUser?.uid ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUploadingAvatar = true, error = null)
            val result = storageHelper.uploadAvatar(uri, uid)
            if (result.isSuccess) {
                val downloadUrl = result.getOrNull() ?: return@launch
                val updateResult = AuthRepository.updateProfile(photoUri = Uri.parse(downloadUrl))
                if (updateResult.isSuccess) {
                    val user = updateResult.getOrNull()
                    if (user != null) {
                        prefs.saveUserLogin(user, AuthRepository.getAccessToken(), AuthRepository.getRefreshToken())
                    }
                    _uiState.value = _uiState.value.copy(
                        cloudAvatarUrl = downloadUrl,
                        isUploadingAvatar = false,
                        localAvatarUri = null
                    )
                    prefs.clearLocalAvatarUri()
                } else {
                    _uiState.value = _uiState.value.copy(
                        isUploadingAvatar = false,
                        error = updateResult.exceptionOrNull()?.message ?: "头像上传后更新失败"
                    )
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    isUploadingAvatar = false,
                    error = result.exceptionOrNull()?.message ?: "头像上传失败"
                )
            }
        }
    }

    fun saveProfile() {
        val nickname = _uiState.value.editNickname.trim()
        if (nickname.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "昵称不能为空")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)
            val result = AuthRepository.updateProfile(displayName = nickname)
            if (result.isSuccess) {
                val user = result.getOrNull()
                if (user != null) {
                    prefs.saveUserLogin(user, AuthRepository.getAccessToken(), AuthRepository.getRefreshToken())
                }
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveSuccess = true
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = result.exceptionOrNull()?.message ?: "保存失败"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
