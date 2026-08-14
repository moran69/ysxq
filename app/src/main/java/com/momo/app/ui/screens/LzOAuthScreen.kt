package com.momo.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.momo.app.data.auth.AuthRepository
import com.momo.app.ui.theme.DarkBackground
import com.momo.app.ui.theme.SakuraPrimary
import com.momo.app.ui.theme.TextPrimary
import com.momo.app.ui.theme.TextSecondary
import com.momo.app.ui.theme.TextTertiary
import kotlinx.coroutines.launch

/**
 * L 站（connect.linux.do）OAuth 授权登录页。
 *
 * 流程：
 * 1. WebView 打开苹果CMS 的 L 站授权入口，跳转 connect.linux.do 完成授权
 * 2. 授权后回调 logincallback，网站端写入登录 Cookie 并跳转到 /user/index
 * 3. 检测到 /user/index 页面加载完成 → 读取 CookieManager 中的 Cookie
 * 4. 调用 /api.php/auth/oauth_jwt 用 Cookie 换取 JWT，完成 APP 登录
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LzOAuthScreen(
    onAuthSuccess: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var authError by remember { mutableStateOf<String?>(null) }
    var oauthDone by remember { mutableStateOf(false) }

    // 苹果CMS 的 L 站授权入口（302 → connect.linux.do）
    val oauthUrl = "http://161.118.252.183:8899/user/oauth.html?type=lz"
    // 授权成功后网站端跳转的目标页面（含 user/index 即视为登录成功）
    val successMarkers = listOf("user/index", "index.php/user/index", "user/index.html")

    LaunchedEffect(oauthDone) {
        if (!oauthDone) return@LaunchedEffect
        authError = null
        val cookies = CookieManager.getInstance().getCookie("http://161.118.252.183:8899/") ?: ""
        if (cookies.isBlank()) {
            authError = "未获取到登录状态，请重试"
            oauthDone = false
            return@LaunchedEffect
        }
        val result = AuthRepository.signInWithCookie(cookies)
        result.onSuccess {
            onAuthSuccess()
        }.onFailure {
            authError = it.message ?: "登录失败"
            oauthDone = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary)
                }
                Text(
                    text = "L 站账号登录",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.weight(1f))
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = SakuraPrimary,
                        strokeWidth = 2.dp
                    )
                }
            }
            HorizontalDivider(color = TextTertiary.copy(alpha = 0.3f))

            if (authError != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(authError ?: "", color = TextSecondary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            authError = null
                            oauthDone = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SakuraPrimary)
                    ) {
                        Text("重试")
                    }
                }
            }

            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        // 移动端 Chrome UA，降低 Cloudflare 人机验证误拦概率
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                loading = false
                                val u = url ?: return
                                if (!oauthDone && successMarkers.any { u.contains(it) }) {
                                    oauthDone = true
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                // 授权流程都在同一 WebView 内完成，不跳外部浏览器
                                return false
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                // Cloudflare 挑战期间可能报错，忽略（页面后续会加载）
                            }
                        }
                        loadUrl(oauthUrl)
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
