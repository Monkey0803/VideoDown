package com.videodown.douyin.presentation.sites

import android.content.Intent
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.videodown.douyin.R
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.generic.BrowserMediaDetector
import com.videodown.douyin.data.site.SitePageMetadataCodec
import com.videodown.douyin.data.site.SiteSourceAdapter
import com.videodown.douyin.domain.model.SiteChannel
import com.videodown.douyin.domain.model.SiteSource
import com.videodown.douyin.presentation.appErrorText
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("SetJavaScriptEnabled")
@Composable
fun SiteBrowserScreen(
    adapter: SiteSourceAdapter,
    initialPageUrl: String? = null,
    onBack: () -> Unit,
    onOpenPlayer: (String) -> Unit,
    model: SiteBrowserViewModel = viewModel(),
) {
    val source: SiteSource = adapter.source
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val detector = remember { BrowserMediaDetector(ServiceLocator.douyinApiClient.okHttpClient, ServiceLocator.browserSessions) }
    val limiter = remember { Semaphore(2) }
    val probeJobs = remember { mutableListOf<Job>() }
    val seen = remember { mutableSetOf<String>() }
    val firstChannel = source.channels.first()
    var selectedChannelId by rememberSaveable(source.id) { mutableStateOf(firstChannel.id) }
    var currentUrl by rememberSaveable(source.id) {
        mutableStateOf(initialPageUrl?.takeIf(source::allowsNavigation) ?: source.channelUrl(firstChannel))
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageLoading by remember { mutableStateOf(model.retainedWebView() == null) }

    fun inspect(url: String, pageUrl: String, userAgent: String) {
        if (!source.acceptsMediaUrl(url) || !BrowserMediaDetector.mayBeMedia(url) || !seen.add(url)) return
        probeJobs += scope.launch {
            val candidate = limiter.withPermit { detector.inspect(url, pageUrl, userAgent) }
            if (candidate != null && !candidate.isImage) {
                val view = webView
                if (view == null) {
                    model.parse(pageUrl, candidate.url, userAgent, source, requireSelection = true)
                } else {
                    view.evaluateJavascript(adapter.metadataJavascript) { encoded ->
                        val metadata = SitePageMetadataCodec.decodeJavascriptResult(encoded)
                        val effectivePageUrl = metadata?.safePageUrl
                            ?.takeIf(source::allowsNavigation) ?: pageUrl
                        metadata?.let { model.updatePageMetadata(source.id, effectivePageUrl, it) }
                        // 资源嗅探可能先看到广告 HLS；即使只有一个候选也必须让用户确认。
                        model.parse(effectivePageUrl, candidate.url, userAgent, source, requireSelection = true)
                    }
                }
            }
        }
    }

    LaunchedEffect(state.readySessionId) {
        state.readySessionId?.let { id -> model.consumeSession(); onOpenPlayer(id) }
    }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(appErrorText(context, it)); model.clearError() }
    }
    DisposableEffect(Unit) {
        onDispose {
            probeJobs.forEach(Job::cancel)
            webView?.apply {
                val state = Bundle()
                saveState(state)
                model.saveBrowserState(state, scrollX, scrollY)
                model.retainWebView(this)
                webViewClient = WebViewClient()
            }
            // ViewModel 跨普通导航保留 WebView；配置切换时必须丢弃旧 Activity 的 Context。
            if (activity?.isChangingConfigurations == true) model.destroyRetainedWebView()
        }
    }
    BackHandler {
        if (webView?.canGoBack() == true) webView?.goBack() else onBack()
    }

    if (state.choices.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = model::dismissChoices) {
            Text(stringResource(R.string.sites_choose_quality), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            state.choices.forEach { choice ->
                TextButton(
                    onClick = {
                        model.choose(source.id, currentUrl, webView?.settings?.userAgentString.orEmpty(), choice)
                        model.dismissChoices()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val host = Uri.parse(choice.variant.url).host.orEmpty()
                    Text(listOf(choice.video.title, choice.variant.label, host).filter(String::isNotBlank).joinToString(" · "))
                }
            }
            Spacer(Modifier.size(20.dp))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (source.id == "kubb") "KUBB.TV" else source.id) },
                navigationIcon = { IconButton(onClick = onBack) { AppIcon(AppIcons.Back, stringResource(R.string.action_back)) } },
                actions = {
                    IconButton(onClick = { webView?.reload() }) { AppIcon(AppIcons.Retry, stringResource(R.string.action_refresh)) }
                    if (state.parsing) CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(22.dp), strokeWidth = 2.dp)
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(source.channels, key = SiteChannel::id) { channel ->
                    FilterChip(
                        modifier = Modifier.testTag("site-channel-${channel.id}"),
                        selected = selectedChannelId == channel.id,
                        onClick = {
                            selectedChannelId = channel.id
                            currentUrl = source.channelUrl(channel)
                            seen.clear()
                            webView?.loadUrl(currentUrl)
                        },
                        label = { Text(stringResource(channelLabel(channel.id))) },
                    )
                }
            }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { viewContext ->
                    val retained = model.retainedWebView()
                    val isNew = retained == null
                    val instance = retained ?: WebView(viewContext).also(model::retainWebView)
                    (instance.parent as? ViewGroup)?.removeView(instance)
                    instance.apply {
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportMultipleWindows(false)
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        val configuredUserAgent = settings.userAgentString.orEmpty()
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString()
                                if (request.url.scheme !in setOf("http", "https")) return true
                                if (source.allowsNavigation(url)) return false
                                if (request.isForMainFrame && request.hasGesture()) {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                                } else if (request.isForMainFrame) {
                                    scope.launch { snackbar.showSnackbar(resources.getString(R.string.sites_navigation_blocked)) }
                                }
                                return true
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                if (url == "about:blank") return
                                currentUrl = url
                                pageLoading = true
                                model.onPageStarted()
                                seen.clear()
                                probeJobs.forEach(Job::cancel)
                                probeJobs.clear()
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                pageLoading = false
                                currentUrl = url
                                adapter.interactionJavascript?.let { view.evaluateJavascript(it, null) }
                                if (Uri.parse(url).path?.startsWith("/play/") == true) {
                                    // DOM 结果只补展示信息；媒体 URL 与授权判断仍由解析器负责。
                                    view.evaluateJavascript(adapter.metadataJavascript) { encoded ->
                                        SitePageMetadataCodec.decodeJavascriptResult(encoded)?.let { metadata ->
                                            val effectivePageUrl = metadata.safePageUrl
                                                ?.takeIf(source::allowsNavigation) ?: url
                                            model.updatePageMetadata(source.id, effectivePageUrl, metadata)
                                        }
                                        if (!model.isResolvingOrResolved()) {
                                            model.parse(url, url, configuredUserAgent, source)
                                        }
                                    }
                                }
                            }

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                if (request.method == "GET" && !request.isForMainFrame) {
                                    val url = request.url.toString()
                                    val page = currentUrl
                                    view.post { inspect(url, page, configuredUserAgent) }
                                }
                                return null
                            }
                        }
                        val snapshot = model.browserSnapshot
                        if (!isNew) {
                            currentUrl = url ?: currentUrl
                            pageLoading = false
                        } else if (snapshot != null && restoreState(snapshot.state) != null) {
                            post { scrollTo(snapshot.scrollX, snapshot.scrollY) }
                        } else {
                            loadUrl(currentUrl)
                        }
                    }
                },
            )
            if (pageLoading) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.sites_loading_page), modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun channelLabel(id: String): Int = when (id) {
    "movie" -> R.string.site_channel_movie
    "drama" -> R.string.site_channel_drama
    "variety" -> R.string.site_channel_variety
    "anime" -> R.string.site_channel_anime
    "short" -> R.string.site_channel_short
    "sport" -> R.string.site_channel_sport
    "documentary" -> R.string.site_channel_documentary
    "chinese" -> R.string.site_channel_chinese
    "game" -> R.string.site_channel_game
    "news" -> R.string.site_channel_news
    "yule" -> R.string.site_channel_entertainment
    "life" -> R.string.site_channel_life
    "music" -> R.string.site_channel_music
    "fashion" -> R.string.site_channel_fashion
    "tech" -> R.string.site_channel_tech
    else -> R.string.site_channel_short_video
}
