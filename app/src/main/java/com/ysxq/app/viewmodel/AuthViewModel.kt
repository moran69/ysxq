package com.ysxq.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ysxq.app.data.auth.AuthRepository
import com.ysxq.app.data.auth.AuthState
import com.ysxq.app.data.auth.UiState
import com.ysxq.app.data.auth.User
import com.ysxq.app.data.local.userPreferences
import com.ysxq.app.data.storage.CloudBaseStorageHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AuthUiState(
    val email: String = "",
    val phone: String = "",
    val verificationCode: String = "",
    val emailError: String? = null,
    val phoneError: String? = null,
    val codeError: String? = null,
    val authMode: AuthMode = AuthMode.EMAIL,
    val codeCountdown: Int = 0,
)

enum class AuthMode { PHONE, EMAIL }

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs by lazy { application.userPreferences() }

    val authState: StateFlow<AuthState> = AuthRepository.authStateChanges()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AuthState.Loading)

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _authResultState = MutableStateFlow<UiState<User>>(UiState.Idle)
    val authResultState: StateFlow<UiState<User>> = _authResultState.asStateFlow()

    private val _sendCodeState = MutableStateFlow<UiState<String>>(UiState.Idle)
    val sendCodeState: StateFlow<UiState<String>> = _sendCodeState.asStateFlow()

    private var pendingVerificationId: String? = null
    private var isExistingUser: Boolean = false
    private var countdownJob: Job? = null

    fun onEmailChange(email: String) {
        _uiState.value = _uiState.value.copy(email = email, emailError = null)
    }

    fun onPhoneChange(phone: String) {
        _uiState.value = _uiState.value.copy(phone = phone, phoneError = null)
    }

    fun onVerificationCodeChange(code: String) {
        _uiState.value = _uiState.value.copy(verificationCode = code, codeError = null)
    }

    fun onAuthModeChange(mode: AuthMode) {
        _uiState.value = _uiState.value.copy(authMode = mode)
        clearStates()
    }

    fun sendVerificationCode() {
        val state = _uiState.value
        viewModelScope.launch {
            _sendCodeState.value = UiState.Loading
            val result = when (state.authMode) {
                AuthMode.PHONE -> {
                    if (!validatePhone(state.phone)) {
                        _sendCodeState.value = UiState.Idle
                        return@launch
                    }
                    AuthRepository.sendPhoneVerificationCode(formatPhone(state.phone))
                }
                AuthMode.EMAIL -> {
                    if (!validateEmail(state.email)) {
                        _sendCodeState.value = UiState.Idle
                        return@launch
                    }
                    AuthRepository.sendEmailVerificationCode(state.email)
                }
            }
            result.onSuccess { sendCodeResult ->
                pendingVerificationId = sendCodeResult.verificationId
                isExistingUser = sendCodeResult.isUser
                _sendCodeState.value = UiState.Success("验证码已发送")
                startCountdown()
            }.onFailure {
                _sendCodeState.value = UiState.Error(it.message ?: "发送失败")
            }
        }
    }

    fun verifyAndProceed() {
        val state = _uiState.value
        if (!validateVerificationCode(state.verificationCode)) return
        val verificationId = pendingVerificationId
            ?: run {
                _uiState.value = _uiState.value.copy(codeError = "请先获取验证码")
                return
            }

        viewModelScope.launch {
            _authResultState.value = UiState.Loading
            val verifyResult = AuthRepository.verifyCode(verificationId, state.verificationCode)
            verifyResult.onSuccess { verificationToken ->
                if (isExistingUser) {
                    AuthRepository.signInWithVerificationToken(verificationToken)
                        .onSuccess { user ->
                            prefs.saveUserLogin(user, AuthRepository.getAccessToken(), AuthRepository.getRefreshToken())
                            if (user.photoUrl.isNullOrBlank()) {
                                uploadDefaultAvatar(user)
                            }
                            _authResultState.value = UiState.Success(user)
                        }
                        .onFailure { _authResultState.value = UiState.Error(it.message ?: "登录失败") }
                } else {
                    val username = generateUsername(state)
                    val signUpResult = when (state.authMode) {
                        AuthMode.EMAIL -> AuthRepository.signUpWithEmail(
                            state.email, username, null, verificationToken
                        )
                        AuthMode.PHONE -> AuthRepository.signUpWithPhone(
                            formatPhone(state.phone), username, null, verificationToken
                        )
                    }
                    signUpResult.onSuccess { user ->
                        val nickname = generateNickname()
                        AuthRepository.updateProfile(displayName = nickname)
                        uploadDefaultAvatar(user)
                        prefs.saveUserLogin(
                            AuthRepository.currentUser ?: user,
                            AuthRepository.getAccessToken(),
                            AuthRepository.getRefreshToken()
                        )
                        _authResultState.value = UiState.Success(user)
                    }.onFailure {
                        _authResultState.value = UiState.Error(it.message ?: "注册失败")
                    }
                }
            }.onFailure {
                _authResultState.value = UiState.Error(it.message ?: "验证码错误")
            }
        }
    }

    fun skipLogin() {
        viewModelScope.launch { prefs.markAsGuest() }
    }

    private suspend fun uploadDefaultAvatar(user: User) {
        if (user.uid.isBlank()) return
        val app = getApplication<Application>()
        try {
            val helper = CloudBaseStorageHelper(app)
            helper.uploadDefaultAvatar(user.uid)
                .onSuccess { result ->
                    AuthRepository.updateProfile(photoUri = Uri.parse(result.fileId))
                    CloudBaseStorageHelper.cacheResolvedUrl(result.fileId, result.downloadUrl)
                }
        } catch (_: Exception) { }
    }

    fun clearStates() {
        _authResultState.value = UiState.Idle
        _sendCodeState.value = UiState.Idle
        _uiState.value = AuthUiState(authMode = _uiState.value.authMode)
        pendingVerificationId = null
        isExistingUser = false
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (i in 60 downTo 1) {
                _uiState.value = _uiState.value.copy(codeCountdown = i)
                delay(1000)
            }
            _uiState.value = _uiState.value.copy(codeCountdown = 0)
        }
    }

    private fun formatPhone(phone: String): String {
        return if (phone.startsWith("+")) phone else "+86 $phone"
    }

    private fun validateEmail(email: String): Boolean {
        if (email.isBlank()) {
            _uiState.value = _uiState.value.copy(emailError = "请输入邮箱")
            return false
        }
        val regex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        if (!regex.matches(email)) {
            _uiState.value = _uiState.value.copy(emailError = "邮箱格式不正确")
            return false
        }
        return true
    }

    private fun validatePhone(phone: String): Boolean {
        val cleaned = phone.replace("\\s".toRegex(), "")
        if (cleaned.isBlank()) {
            _uiState.value = _uiState.value.copy(phoneError = "请输入手机号")
            return false
        }
        val digits = cleaned.replace("^\\+?86".toRegex(), "")
        if (!digits.matches("^1[3-9]\\d{9}$".toRegex())) {
            _uiState.value = _uiState.value.copy(phoneError = "手机号格式不正确")
            return false
        }
        return true
    }

    private fun validateVerificationCode(code: String): Boolean {
        if (code.isBlank() || code.length < 4) {
            _uiState.value = _uiState.value.copy(codeError = "请输入验证码")
            return false
        }
        return true
    }

    /**
     * CloudBase username regex: ^[a-z][0-9a-z_-]{5,24}$
     * - Must start with lowercase letter
     * - Total length 6-25 chars
     * - Only lowercase letters, digits, underscores, hyphens
     */
    private fun generateUsername(state: AuthUiState): String {
        val raw = when (state.authMode) {
            AuthMode.EMAIL -> state.email.substringBefore("@")
                .lowercase()
                .replace(Regex("[^a-z0-9_]"), "")
                .takeIf { it.isNotBlank() } ?: "user"
            AuthMode.PHONE -> "user${state.phone.replace(Regex("\\D"), "").takeLast(4)}"
        }
        // Ensure starts with lowercase letter (prepend 'u' if starts with digit/underscore)
        val safeBase = if (raw.firstOrNull()?.isLetter() == true) raw else "u$raw"
        // Suffix "_NNNN" = 5 chars, max total = 25, so base max = 20
        val truncatedBase = safeBase.take(20)
        val suffix = (1000..9999).random()
        return "${truncatedBase}_$suffix"
    }

    private fun generateNickname(): String {
        val number = (10000000..99999999).random()
        return "用户$number"
    }
}
