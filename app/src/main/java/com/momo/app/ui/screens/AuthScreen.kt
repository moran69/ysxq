package com.momo.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.momo.app.data.auth.AuthRepository
import com.momo.app.data.auth.UiState
import com.momo.app.ui.theme.*
import com.momo.app.viewmodel.AuthViewModel
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
) {
    val viewModel: AuthViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val authResultState by viewModel.authResultState.collectAsState()

    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 登录/注册模式切换
    var isLoginMode by remember { mutableStateOf(true) }
    // L 站 OAuth 授权页
    var showLzOAuth by remember { mutableStateOf(false) }

    LaunchedEffect(authResultState) {
        if (authResultState is UiState.Success) {
            onAuthSuccess()
        }
    }

    LaunchedEffect(Unit) {
        val expiredMsg = AuthRepository.consumeSessionExpiredMessage()
        if (expiredMsg != null) {
            Toast.makeText(context, expiredMsg, Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Logo
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Movie,
                contentDescription = "App Icon",
                tint = SakuraPrimary,
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = "EI Psy Cloud",
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary
            )
            Text(
                text = "追剧从未如此简单",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // VIP 提示
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = SakuraPrimary.copy(alpha = 0.12f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "登录后可同步收藏与观看记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SakuraPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 输入区
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // 用户名
            OutlinedTextField(
                value = uiState.username,
                onValueChange = { viewModel.onUsernameChange(it) },
                label = { Text("用户名") },
                leadingIcon = {
                    Icon(Icons.Outlined.Person, "Username", tint = TextTertiary)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors(),
                shape = RoundedCornerShape(12.dp),
                isError = uiState.usernameError != null
            )
            if (uiState.usernameError != null) {
                ErrorText(uiState.usernameError!!)
            }

            // 密码
            OutlinedTextField(
                value = uiState.password,
                onValueChange = { viewModel.onPasswordChange(it) },
                label = { Text("密码") },
                leadingIcon = {
                    Icon(Icons.Outlined.Lock, "Password", tint = TextTertiary)
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors(),
                shape = RoundedCornerShape(12.dp),
                isError = uiState.passwordError != null
            )
            if (uiState.passwordError != null) {
                ErrorText(uiState.passwordError!!)
            }

            // 主按钮
            ActionButton(
                text = if (isLoginMode) "登录" else "注册",
                loading = authResultState is UiState.Loading,
                enabled = authResultState !is UiState.Loading
            ) {
                keyboardController?.hide()
                if (isLoginMode) viewModel.login() else viewModel.register()
            }

            if (authResultState is UiState.Error) {
                ErrorText((authResultState as UiState.Error).message)
            }
        }

        // 切换登录/注册
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isLoginMode) "没有账号？去注册" else "已有账号？去登录",
                color = SakuraPrimary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        isLoginMode = !isLoginMode
                        viewModel.clearStates()
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        // 分割线
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(color = TextTertiary, modifier = Modifier.weight(1f), thickness = 1.dp)
            Text(
                text = "—— 其他方式 ——",
                color = TextTertiary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            HorizontalDivider(color = TextTertiary, modifier = Modifier.weight(1f), thickness = 1.dp)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // L 站账号登录
        Button(
            onClick = {
                keyboardController?.hide()
                showLzOAuth = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(12.dp)),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = TextPrimary
            ),
            border = BorderStroke(1.dp, TextTertiary.copy(alpha = 0.6f))
        ) {
            Text(
                text = "使用 L 站账号登录",
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 游客浏览
        TextButton(
            onClick = {
                viewModel.skipLogin()
                onAuthSuccess()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "跳过，游客浏览",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Text(
            text = "登录即表示同意《用户协议》和《隐私政策》",
            color = TextTertiary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 16.dp)
        )
    }

    // L 站 OAuth 授权页（全屏覆盖）
    if (showLzOAuth) {
        LzOAuthScreen(
            onAuthSuccess = {
                showLzOAuth = false
                onAuthSuccess()
            },
            onClose = {
                showLzOAuth = false
            }
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Spacer(modifier = Modifier.height(8.dp))
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.horizontalGradient(colors = listOf(PinkGradientStart, PinkGradientEnd)),
                RoundedCornerShape(12.dp)
            ),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        enabled = enabled
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White
            )
        }
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(
        text = message,
        color = Color.Red,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 16.dp)
    )
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = SakuraPrimary,
    unfocusedBorderColor = DarkSurfaceVariant,
    errorBorderColor = Color.Red,
    cursorColor = SakuraPrimary
)
