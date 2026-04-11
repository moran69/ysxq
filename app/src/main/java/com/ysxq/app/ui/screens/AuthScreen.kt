package com.ysxq.app.ui.screens

import android.widget.Toast
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
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Phone
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ysxq.app.data.auth.AuthRepository
import com.ysxq.app.data.auth.UiState
import com.ysxq.app.ui.theme.*
import com.ysxq.app.viewmodel.AuthMode
import com.ysxq.app.viewmodel.AuthViewModel
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
) {
    val viewModel: AuthViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val authResultState by viewModel.authResultState.collectAsState()
    val sendCodeState by viewModel.sendCodeState.collectAsState()

    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(authResultState) {
        if (authResultState is UiState.Success) {
            onAuthSuccess()
        }
    }

    LaunchedEffect(sendCodeState) {
        if (sendCodeState is UiState.Success) {
            Toast.makeText(context, "验证码已发送", Toast.LENGTH_SHORT).show()
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

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            listOf(AuthMode.EMAIL to "邮箱", AuthMode.PHONE to "手机号").forEach { (mode, label) ->
                Text(
                    text = label,
                    color = if (uiState.authMode == mode) SakuraPrimary else TextTertiary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { viewModel.onAuthModeChange(mode) }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
                if (mode == AuthMode.EMAIL) {
                    Text(
                        text = " | ",
                        color = TextTertiary,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            when (uiState.authMode) {
                AuthMode.PHONE -> {
                    OutlinedTextField(
                        value = uiState.phone,
                        onValueChange = { viewModel.onPhoneChange(it) },
                        label = { Text("手机号") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Phone, "Phone Icon", tint = TextTertiary)
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Phone,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        colors = textFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        isError = uiState.phoneError != null
                    )
                    if (uiState.phoneError != null) {
                        ErrorText(uiState.phoneError!!)
                    }
                }
                AuthMode.EMAIL -> {
                    OutlinedTextField(
                        value = uiState.email,
                        onValueChange = { viewModel.onEmailChange(it) },
                        label = { Text("邮箱地址") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Email, "Email Icon", tint = TextTertiary)
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        colors = textFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        isError = uiState.emailError != null
                    )
                    if (uiState.emailError != null) {
                        ErrorText(uiState.emailError!!)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = uiState.verificationCode,
                    onValueChange = { viewModel.onVerificationCodeChange(it) },
                    label = { Text("验证码") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    modifier = Modifier.weight(1f),
                    colors = textFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    isError = uiState.codeError != null
                )
                Button(
                    onClick = { viewModel.sendVerificationCode() },
                    enabled = uiState.codeCountdown == 0 && sendCodeState !is UiState.Loading,
                    modifier = Modifier.height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SakuraPrimary,
                        disabledContainerColor = DarkSurfaceVariant
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (uiState.codeCountdown > 0) "${uiState.codeCountdown}s"
                        else if (sendCodeState is UiState.Loading) "发送中..."
                        else "获取验证码",
                        color = if (uiState.codeCountdown == 0) Color.White else TextTertiary,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            if (uiState.codeError != null) {
                ErrorText(uiState.codeError!!)
            }

            ActionButton(
                text = "登录 / 注册",
                loading = authResultState is UiState.Loading,
                enabled = authResultState !is UiState.Loading
            ) {
                viewModel.verifyAndProceed()
            }

            if (authResultState is UiState.Error) {
                ErrorText((authResultState as UiState.Error).message)
            }

            if (sendCodeState is UiState.Error) {
                ErrorText((sendCodeState as UiState.Error).message)
            }
        }

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

        Spacer(modifier = Modifier.height(16.dp))

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
