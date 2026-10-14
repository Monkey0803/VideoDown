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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.generic.BrowserMediaDetector
import com.videodown.douyin.data.remote.VideoLinkExtractor
import com.videodown.douyin.domain.model.ParsedVideos
import com.videodown.douyin.presentation.appErrorText
import kotlinx.coroutines.*
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONTokener

/** 状态行的测试标签：设备用例要断言"失败时确实有一句给人看的话"。 */
internal const val BROWSER_STATUS_TAG = "browser-status"

@Suppress("SetJavaScriptEnabled")
@Composable
fun BrowserAssistScreen(initialUrl: String, onClose: () -> Unit, onResult: (ParsedVideos) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // 资源按当前组合读取：context.getString 不会随语言/配置切换重算。
    val resources = LocalResources.current
    var browser by remember { mutableStateOf<WebView?>(null) }
    var page by remember { mutableStateOf(initialUrl) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    // 本轮扫描是否已经出结论（探测都结束了）。没找到的结论只有在此时才算数。
    var scanSettled by remember { mutableStateOf(false) }
    var scanGeneration by remember { mutableStateOf(0) }
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
        scanSettled = false
        parsing = scope.launch {
            try {
                message = resources.getString(R.string.browser_reading)
                when (val result = ServiceLocator.genericRepository.parse(url, true, referer, browser?.settings?.userAgentString)) {
                    is AppResult.Success -> onResult(result.value)
                    // 引擎失败带的是 reasonKey，message 是空串：直接显示就成了一片空白（曾经就是这样）。
                    // 视频号另说：通用兜底那句"打开网页播放后查找"对这个网站是死的，改用准确的说法。
                    is AppResult.Failure -> message = if (VideoLinkExtractor.isWeChatChannelsLink(url)) {
                        resources.getString(R.string.engine_wechat_channels)
                    } else {
                        appErrorText(context, result.error)
                    }
                }
            } finally { busy = false }
        }
    }
    fun scan() {
        probes.forEach { it.cancel() }; probes.clear(); seen.clear()
        scanGeneration++
        val generation = scanGeneration
        scanSettled = false
        message = null
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
            // 本轮探测全部结束再下结论：否则"没找到"会先于正在验证的候选出现，等于骗人。
            scope.launch {
                probes.toList().joinAll()
                if (generation != scanGeneration) return@launch
                scanSettled = true
            }
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
                    IconButton(onClick = onClose) { AppIcon(AppIcons.Close, contentDescription = stringResource(R.string.browser_close_page)) }
                    IconButton(onClick = { browser?.reload() }) { AppIcon(AppIcons.Retry, contentDescription = stringResource(R.string.action_refresh)) }
                    IconButton(onClick = ::scan) { AppIcon(AppIcons.Search, contentDescription = stringResource(R.string.browser_find_videos)) }
                }
                Text(page.toUri().host ?: stringResource(R.string.browser_page), modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
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
                                page = url; pageError = null; candidates.clear(); seen.clear(); scanSettled = false
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
                                    pageError = context.getString(R.string.browser_http_error, response.statusCode)
                                    message = pageError!!
                                }
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) {
                                    pageError = context.getString(R.string.browser_load_failed)
                                    message = pageError!!
                                }
                            }
                        }
                        loadUrl(initialUrl)
                    }
                })
                // 扫完必须给一句结论：找到几个、还是一个都没有。留白等于什么都不说（曾经就是这样）。
                val verdictRes = browserStatusRes(
                    hasMessage = message != null,
                    scanSettled = scanSettled,
                    busy = busy,
                    candidateCount = candidates.size,
                    weChatChannels = VideoLinkExtractor.isWeChatChannelsLink(page),
                )
                val status = message ?: stringResource(verdictRes ?: R.string.browser_initial_hint)
                Text(status, Modifier.padding(12.dp).testTag(BROWSER_STATUS_TAG), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    if (busy) TextButton(onClick = { parsing?.cancel(); message = resources.getString(R.string.browser_cancelled) }) { Text(stringResource(R.string.browser_cancel_parse)) }
                    else TextButton(onClick = { parse(page, page) }) { Text(stringResource(R.string.browser_reparse_with_session)) }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                    items(candidates.toList(), key = { it.url }) { candidate ->
                        TextButton(enabled = !busy, onClick = { parse(candidate.url, candidate.pageUrl) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.browser_candidate, candidate.type, candidate.url.toUri().host.orEmpty()), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
