package com.videodown.douyin.presentation.browser

import android.content.Intent
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.videodown.douyin.R
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.generic.BrowserMediaDetector
import com.videodown.douyin.data.generic.ImageMedia
import com.videodown.douyin.data.generic.groupCandidates
import com.videodown.douyin.data.remote.VideoLinkExtractor
import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.domain.model.ParsedVideos
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.presentation.appErrorText
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppFullScreenDialog
import com.videodown.douyin.presentation.components.AppIcons
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONTokener

/** 状态行的测试标签：设备用例要断言「失败时确实有一句给人看的话」。 */
internal const val BROWSER_STATUS_TAG = "browser-status"

/** 扫描如果长时间不 settle（页面卡住/JS 回调丢失），到这个点强制给结论，避免永远显示「查找中」。 */
private const val SCAN_TIMEOUT_MS = 12_000L

/** 一轮扫描最多观察多少个资源，避免大页面把网络探测量拖到不可控。 */
private const val MAX_PROBED_URLS = 120

/**
 * 内置网页解析面板。
 *
 * 布局分三层：顶部栏（关闭/标题/后退/刷新/查找）、WebView、底部面板。底部面板收起时只有一行结论，
 * 展开后才是候选列表——网页本身占绝对多数屏幅，避免「面板才是主角」。
 */
@Suppress("SetJavaScriptEnabled")
@Composable
fun BrowserAssistScreen(initialUrl: String, onClose: () -> Unit, onResult: (ParsedVideos) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 资源按当前组合读取：context.getString 不会随语言/配置切换重算。
    val resources = LocalResources.current
    var browser by remember { mutableStateOf<WebView?>(null) }
    var page by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    // 本轮扫描是否已触发/是否已经出结论。没找到的结论只有在 settle 之后才算数。
    var scanStarted by remember { mutableStateOf(false) }
    var scanSettled by remember { mutableStateOf(false) }
    var scanGeneration by remember { mutableStateOf(0) }
    var parsing by remember { mutableStateOf<Job?>(null) }
    var watchDog by remember { mutableStateOf<Job?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var autoExpanded by remember { mutableStateOf(false) }
    var userToggledPanel by remember { mutableStateOf(false) }
    var noteOpen by remember { mutableStateOf(false) }
    var desktopSite by remember { mutableStateOf(false) }
    var hidePrompts by remember { mutableStateOf(true) }
    val candidates = remember { mutableStateListOf<BrowserMediaDetector.Candidate>() }
    val groups by remember { derivedStateOf { groupCandidates(candidates.toList()) } }
    val selectedFormats = remember { mutableStateMapOf<String, String>() }
    val seen = remember { mutableSetOf<String>() }
    val probes = remember { mutableListOf<Job>() }
    val limiter = remember { Semaphore(2) }
    val detector = remember { BrowserMediaDetector(ServiceLocator.douyinApiClient.okHttpClient, ServiceLocator.browserSessions) }

    fun currentUserAgent(): String = browser?.settings?.userAgentString.orEmpty()

    fun detect(url: String) {
        scope.launch {
            if (!BrowserMediaDetector.mayBeMedia(url) || candidates.any { it.url == url } || url in seen ||
                seen.size >= MAX_PROBED_URLS
            ) return@launch
            seen.add(url)
            val ownerPage = page
            val ua = currentUserAgent()
            probes += launch {
                val candidate = limiter.withPermit { detector.inspect(url, ownerPage, ua) }
                if (candidate != null && ownerPage == page && candidates.none { it.url == candidate.url }) {
                    candidates.add(candidate)
                }
            }
        }
    }

    fun runParse(referer: String, block: suspend () -> AppResult<ParsedVideos>) {
        if (busy) return
        busy = true
        parsing = scope.launch {
            try {
                message = resources.getString(R.string.browser_reading)
                when (val result = block()) {
                    is AppResult.Success -> onResult(result.value)
                    // 引擎失败带的是 reasonKey、message 是空串：直接显示就成了一片空白（曾经就是这样）。
                    // 视频号另说：通用兜底那句「打开网页播放后查找」对这个网站是死的，改用准确的说法。
                    is AppResult.Failure -> message = if (VideoLinkExtractor.isWeChatChannelsLink(referer)) {
                        resources.getString(R.string.engine_wechat_channels)
                    } else {
                        appErrorText(context, result.error)
                    }
                }
            } finally { busy = false }
        }
    }

    /**
     * 页面级「重新解析」：已知平台走原生仓库（自带 WebView 登录态），普通网页才用带 referer/UA 的引擎。
     *
     * 原生失败且不是「需要登录」时再回退一次网页会话引擎：个人主页这类页面原生解析不了，但页面里
     * 真有可能有媒体。两者都失败时保留原生更具体的归因。
     */
    fun parsePage() {
        val currentPage = page
        val link = VideoLinkExtractor.extract(currentPage)
        val target = link?.url ?: currentPage
        val platform = link?.platform ?: VideoPlatform.GENERIC
        runParse(currentPage) {
            if (platform == VideoPlatform.GENERIC) {
                ServiceLocator.genericRepository.parse(target, browserSession = true, referer = currentPage, userAgent = currentUserAgent())
            } else {
                val native = ServiceLocator.videoRepository.parseVideos(target)
                if (native is AppResult.Failure && native.error.failureKind != DownloadFailureKind.LOGIN_REQUIRED) {
                    val engine = ServiceLocator.genericRepository.parse(target, true, currentPage, currentUserAgent())
                    if (engine is AppResult.Success) engine else native
                } else {
                    native
                }
            }
        }
    }

    fun onCandidate(candidate: BrowserMediaDetector.Candidate) {
        if (busy) return
        if (candidate.isImage) {
            // 图片候选不再交给 yt-dlp：网页里的图片没有提取器，直接构造下载条目。
            onResult(ParsedVideos(listOf(
                ImageMedia.entry(
                    url = candidate.url,
                    pageUrl = candidate.pageUrl,
                    extension = candidate.extension ?: "jpg",
                    sizeBytes = candidate.sizeBytes,
                    width = candidate.width,
                    height = candidate.height,
                    referer = candidate.pageUrl,
                    userAgent = candidate.userAgent,
                    browserSession = true,
                ),
            )))
        } else {
            runParse(candidate.pageUrl) {
                ServiceLocator.genericRepository.parse(candidate.url, true, candidate.pageUrl, candidate.userAgent)
            }
        }
    }

    fun scan() {
        val view = browser ?: return
        probes.forEach { it.cancel() }; probes.clear(); seen.clear()
        scanGeneration++
        val generation = scanGeneration
        scanStarted = true
        scanSettled = false
        message = null
        // Evaluate a read-only expression; never expose a Java/Kotlin interface to arbitrary pages.
        view.evaluateJavascript(SCAN_SCRIPT) { encoded ->
            runCatching {
                val decoded = JSONTokener(encoded).nextValue() as? String ?: return@runCatching
                val urls = JSONArray(decoded)
                for (i in 0 until urls.length()) detect(urls.getString(i))
            }
            // 本轮探测全部结束再下结论：否则「没找到」会先于正在验证的候选出现，等于骗人。
            scope.launch {
                probes.toList().joinAll()
                if (generation != scanGeneration) return@launch
                scanSettled = true
            }
        }
        watchDog?.cancel()
        watchDog = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (generation == scanGeneration && !scanSettled) scanSettled = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            parsing?.cancel(); probes.forEach { it.cancel() }; watchDog?.cancel()
            browser?.apply {
                webViewClient = WebViewClient()
                stopLoading(); loadUrl("about:blank"); clearHistory(); removeAllViews(); destroy()
            }
        }
    }

    // 桌面版网页：切 UA 后重载；关掉时恢复系统默认 UA。只在本次面板会话内有效。
    LaunchedEffect(desktopSite) {
        val view = browser ?: return@LaunchedEffect
        val target = if (desktopSite) PageTweaks.DESKTOP_USER_AGENT else WebSettings.getDefaultUserAgent(context)
        // 首次组合时 WebView 可能刚建好，此时 UA 已经是系统默认值：不要再重载一次。
        if (view.settings.userAgentString == target) return@LaunchedEffect
        view.settings.userAgentString = target
        view.settings.useWideViewPort = true
        view.settings.loadWithOverviewMode = true
        view.reload()
    }

    // 隐藏网页自己的「打开 App」弹窗：打开时立即补一次，关闭时重载页面撤掉已注入的样式。
    LaunchedEffect(hidePrompts) {
        val view = browser ?: return@LaunchedEffect
        if (hidePrompts) view.evaluateJavascript(PageTweaks.appPromptHiderScript(), null) else view.reload()
    }

    val videos = groups.count { !it.isImage }
    val images = groups.count { it.isImage }
    val status = browserPanelStatus(
        message = message,
        pageLoading = loading,
        parsing = busy,
        scanStarted = scanStarted,
        scanSettled = scanSettled,
        videos = videos,
        images = images,
        weChatChannels = VideoLinkExtractor.isWeChatChannelsLink(page),
    )
    // 首次扫出候选时自动展开一次；用户手动收起过就不再打扰。
    LaunchedEffect((status as? BrowserPanelStatus.Found)?.total) {
        if (status is BrowserPanelStatus.Found && !autoExpanded && !userToggledPanel) {
            expanded = true
            autoExpanded = true
        }
    }

    AppFullScreenDialog(onDismissRequest = onClose) {
        BackHandler {
            when {
                expanded -> { userToggledPanel = true; expanded = false }
                browser?.canGoBack() == true -> browser?.goBack()
                else -> onClose()
            }
        }
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                BrowserTopBar(
                    host = page.toUri().host ?: stringResource(R.string.browser_page),
                    title = pageTitle,
                    canGoBack = canGoBack,
                    onClose = onClose,
                    onBack = { browser?.goBack() },
                    onReload = { browser?.reload() },
                    onScan = ::scan,
                )
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                }
                AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { viewContext ->
                    WebView(viewContext).apply {
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        browser = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportMultipleWindows(false)
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView, title: String?) {
                                pageTitle = title.orEmpty()
                            }

                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                loading = newProgress < 100
                                canGoBack = view.canGoBack()
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                request.url.scheme !in setOf("http", "https")

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                if (url == "about:blank") return
                                page = url
                                pageTitle = ""
                                pageError = null
                                loading = true
                                canGoBack = view.canGoBack()
                                candidates.clear()
                                seen.clear()
                                selectedFormats.clear()
                                scanStarted = false
                                scanSettled = false
                                expanded = false
                                autoExpanded = false
                                userToggledPanel = false
                                noteOpen = false
                                message = null
                                probes.forEach { it.cancel() }; probes.clear()
                                parsing?.cancel()
                                watchDog?.cancel()
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                loading = false
                                canGoBack = view.canGoBack()
                                CookieManager.getInstance().flush()
                                if (pageError != null) {
                                    message = pageError!!
                                    return
                                }
                                if (hidePrompts) view.evaluateJavascript(PageTweaks.appPromptHiderScript(), null)
                                scan()
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
                                    loading = false
                                    message = pageError!!
                                }
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) {
                                    pageError = context.getString(R.string.browser_load_failed)
                                    loading = false
                                    message = pageError!!
                                }
                            }
                        }
                        loadUrl(initialUrl)
                    }
                })
                BrowserPanel(
                    status = status,
                    groups = groups,
                    busy = busy,
                    expanded = expanded,
                    noteOpen = noteOpen,
                    desktopSite = desktopSite,
                    hidePrompts = hidePrompts,
                    selectedFormats = selectedFormats,
                    onToggleExpanded = {
                        userToggledPanel = true
                        expanded = !expanded
                    },
                    onReparse = ::parsePage,
                    onCancelParse = {
                        parsing?.cancel()
                        message = resources.getString(R.string.browser_cancelled)
                    },
                    onToggleNote = { noteOpen = !noteOpen },
                    onToggleDesktopSite = { desktopSite = !desktopSite },
                    onToggleHidePrompts = { hidePrompts = !hidePrompts },
                    onOpenExternal = {
                        val url = page
                        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, url.toUri()), null)) }
                            .onFailure { message = resources.getString(R.string.browser_open_external_failed) }
                    },
                    onCandidateClick = ::onCandidate,
                    onFormatSelect = { groupId, extension -> selectedFormats[groupId] = extension },
                )
            }
    }
}

/** 顶部栏：关闭、主机名/页面标题、后退（可后退时）、刷新、查找。 */
@Composable
private fun BrowserTopBar(
    host: String,
    title: String,
    canGoBack: Boolean,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onScan: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) {
            AppIcon(AppIcons.Close, contentDescription = stringResource(R.string.browser_close_page))
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(host, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (title.isNotBlank()) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (canGoBack) {
            IconButton(onClick = onBack) { AppIcon(AppIcons.Back, contentDescription = stringResource(R.string.action_back)) }
        }
        IconButton(onClick = onReload) { AppIcon(AppIcons.Retry, contentDescription = stringResource(R.string.action_refresh)) }
        IconButton(onClick = onScan) { AppIcon(AppIcons.Search, contentDescription = stringResource(R.string.browser_find_videos)) }
    }
}

/**
 * 只读取页面上已经存在的媒体 URL：视频/图片的 `currentSrc`、`src`，`srcset` 里最大的一档，以及资源计时。
 * 只做只读表达式求值，不向页面暴露任何 Java/Kotlin 接口。
 */
private val SCAN_SCRIPT = """
    JSON.stringify(Array.from(new Set([
      ...Array.from(document.querySelectorAll('video,video source')).flatMap(v=>[v.currentSrc,v.src]),
      ...Array.from(document.querySelectorAll('img')).flatMap(v=>[v.currentSrc,v.src,
          ...(v.srcset||'').split(',').map(s=>s.trim().split(/\s+/)[0]).filter(Boolean).slice(-1)]),
      ...Array.from(document.querySelectorAll('picture source')).flatMap(v=>
          (v.srcset||'').split(',').map(s=>s.trim().split(/\s+/)[0]).filter(Boolean).slice(-1)),
      ...performance.getEntriesByType('resource').map(r=>r.name)
    ].filter(Boolean))))
""".trimIndent()
