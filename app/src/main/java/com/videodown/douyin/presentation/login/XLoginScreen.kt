package com.videodown.douyin.presentation.login

import android.graphics.Bitmap
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.videodown.douyin.R
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.generic.XSession
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons
import kotlinx.coroutines.launch

/** 状态行的测试标签：设备用例要断言"登录后被认出来"这件事。 */
internal const val X_LOGIN_STATUS_TAG = "x-login-status"

/**
 * X（Twitter）登录网页。
 *
 * 只负责让用户在一个真的网页里登录，并报告「本机现在有没有登录态」；登录态由系统 CookieManager
 * 持有并持久化，**有效期完全由 X 决定**：这里不设过期时间、不清理 Cookie，离开页面也不清。
 * 每次页面加载完成都会 flush，保证刚拿到的 Cookie 能活过进程退出。
 */
@Suppress("SetJavaScriptEnabled")
@Composable
fun XLoginScreen(
    startUrl: String = XSession.LOGIN_URL,
    onClose: () -> Unit,
    /** 用户在弹窗里点了「完成」；true 表示当前确实检测到登录态。关窗由调用方负责。 */
    onFinished: (Boolean) -> Unit,
    signedIn: suspend () -> Boolean = { ServiceLocator.browserSessions.xSignedIn() },
) {
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    var browser by remember { mutableStateOf<WebView?>(null) }
    var page by remember { mutableStateOf(startUrl) }
    var status by remember { mutableStateOf<Boolean?>(null) }
    var failed by remember { mutableStateOf(false) }

    fun refreshStatus() {
        scope.launch {
            CookieManager.getInstance().flush()
            status = runCatching { signedIn() }.getOrDefault(false)
        }
    }

    LaunchedEffect(Unit) { refreshStatus() }
    DisposableEffect(Unit) { onDispose {
        runCatching { CookieManager.getInstance().flush() }
        browser?.apply { webViewClient = WebViewClient(); stopLoading(); loadUrl("about:blank"); clearHistory(); removeAllViews(); destroy() }
    } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { onClose() }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { AppIcon(AppIcons.Close, contentDescription = stringResource(R.string.action_close)) }
                    Text(stringResource(R.string.x_login_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { browser?.reload() }) { AppIcon(AppIcons.Retry, contentDescription = stringResource(R.string.action_refresh)) }
                }
                Text(android.net.Uri.parse(page).host.orEmpty(),
                    modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(R.string.x_login_hint), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall)
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                    WebView(context).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                        browser = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportMultipleWindows(false)
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                request.url.scheme !in setOf("http", "https")
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                if (url == "about:blank") return
                                page = url; failed = false
                            }
                            override fun onPageFinished(view: WebView, url: String) {
                                failed = false
                                // 先落盘再判断：这样即使进程马上被杀，刚拿到的登录态也不会丢。
                                refreshStatus()
                            }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (request.isForMainFrame) failed = true
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) failed = true
                            }
                        }
                        loadUrl(startUrl)
                    }
                })
                val statusText = when {
                    failed -> resources.getString(R.string.browser_load_failed)
                    status == true -> resources.getString(R.string.x_login_signed_in)
                    status == null -> resources.getString(R.string.x_login_checking)
                    else -> resources.getString(R.string.x_login_signed_out)
                }
                Text(statusText, Modifier.padding(12.dp).testTag(X_LOGIN_STATUS_TAG), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Button(onClick = {
                        CookieManager.getInstance().flush()
                        onFinished(status == true)
                    }, modifier = Modifier.padding(bottom = 8.dp)) { Text(stringResource(R.string.action_done)) }
                }
            }
        }
    }
}
