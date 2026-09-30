package com.videodown.douyin.presentation.browser

import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons

import android.graphics.Bitmap
import androidx.core.net.toUri
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.generic.BrowserMediaDetector
import com.videodown.douyin.domain.model.ParsedVideos
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONTokener

@Suppress("SetJavaScriptEnabled")
@Composable
fun BrowserAssistScreen(initialUrl: String, onClose: () -> Unit, onResult: (ParsedVideos) -> Unit) {
    val scope = rememberCoroutineScope()
    var browser by remember { mutableStateOf<WebView?>(null) }
    var page by remember { mutableStateOf(initialUrl) }
    var message by remember { mutableStateOf("可在网页中登录并播放视频，然后点击顶部的搜索图标查找视频。部分网站可能限制内置网页登录。") }
    var busy by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var parsing by remember { mutableStateOf<Job?>(null) }
    val candidates = remember { mutableStateListOf<BrowserMediaDetector.Candidate>() }
    val seen = remember { mutableSetOf<String>() }
    val probes = remember { mutableListOf<Job>() }
    val limiter = remember { Semaphore(2) }
    val detector = remember { BrowserMediaDetector(ServiceLocator.douyinApiClient.okHttpClient, ServiceLocator.browserSessions) }

    fun detect(url: String) {
        scope.launch {
            if (!BrowserMediaDetector.mayBeMedia(url) || candidates.any { it.url == url } || url in seen || seen.size >= 120) return@launch
            seen.add(url)
            val ownerPage = page
            val ua = browser?.settings?.userAgentString.orEmpty()
            probes += launch {
                val candidate = limiter.withPermit { detector.inspect(url, ownerPage, ua) }
                if (candidate != null && ownerPage == page && candidates.none { it.url == candidate.url }) candidates.add(candidate)
            }
        }
    }
    fun parse(url: String, referer: String) {
        if (busy) return
        busy = true
        parsing = scope.launch {
            try {
                message = "正在读取视频和画质…"
                when (val result = ServiceLocator.genericRepository.parse(url, true, referer, browser?.settings?.userAgentString)) {
                    is AppResult.Success -> onResult(result.value)
                    is AppResult.Failure -> message = result.error.message
                }
            } finally { busy = false }
        }
    }
    fun scan() {
        probes.forEach { it.cancel() }; probes.clear(); seen.clear()
        // Evaluate a read-only expression; never expose a Java/Kotlin interface to arbitrary pages.
        browser?.evaluateJavascript("""
            JSON.stringify(Array.from(new Set([
              ...Array.from(document.querySelectorAll('video,video source')).flatMap(v=>[v.currentSrc,v.src]),
              ...performance.getEntriesByType('resource').map(r=>r.name)
            ].filter(Boolean))))
        """.trimIndent()) { encoded ->
            runCatching {
                val decoded = JSONTokener(encoded).nextValue() as? String ?: return@runCatching
                val urls = JSONArray(decoded)
                for (i in 0 until urls.length()) detect(urls.getString(i))
            }
            message = "已检查页面资源。若没有结果，请先点击播放；blob 地址需要底层媒体资源，DRM 视频不支持下载。"
        }
    }
    DisposableEffect(Unit) { onDispose {
        parsing?.cancel(); probes.forEach { it.cancel() }
        browser?.apply { webViewClient = WebViewClient(); stopLoading(); loadUrl("about:blank"); clearHistory(); removeAllViews(); destroy() }
    } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { if (browser?.canGoBack() == true) browser?.goBack() else onClose() }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = onClose) { AppIcon(AppIcons.Close, contentDescription = "关闭网页") }
                    IconButton(onClick = { browser?.reload() }) { AppIcon(AppIcons.Retry, contentDescription = "刷新") }
                    IconButton(onClick = ::scan) { AppIcon(AppIcons.Search, contentDescription = "查找视频") }
                }
                Text(page.toUri().host ?: "网页", modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                    WebView(context).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
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
                                page = url; pageError = null; candidates.clear(); seen.clear()
                                probes.forEach { it.cancel() }; probes.clear()
                                parsing?.cancel()
                            }
                            override fun onPageFinished(view: WebView, url: String) {
                                CookieManager.getInstance().flush()
                                if (pageError == null) scan() else message = pageError!!
                            }
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val url = request.url.toString()
                                // Observe only; WebView performs its normal request with its own session.
                                if (request.method == "GET" && !request.isForMainFrame) view.post { detect(url) }
                                return null
                            }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (request.isForMainFrame && response.statusCode in setOf(401, 403)) {
                                    pageError = "网站拒绝访问（HTTP ${response.statusCode}），请完成登录或验证；该网站也可能禁止内置浏览器登录。"
                                    message = pageError!!
                                }
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) {
                                    pageError = "网页加载失败，请检查网络或尝试在原浏览器中打开。"
                                    message = pageError!!
                                }
                            }
                        }
                        loadUrl(initialUrl)
                    }
                })
                Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    if (busy) TextButton(onClick = { parsing?.cancel(); message = "已取消解析" }) { Text("取消解析") }
                    else TextButton(onClick = { parse(page, page) }) { Text("使用网页登录态重新解析") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                    items(candidates.toList(), key = { it.url }) { candidate ->
                        TextButton(enabled = !busy, onClick = { parse(candidate.url, candidate.pageUrl) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${candidate.type} · ${candidate.url.toUri().host} · 选择画质", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
