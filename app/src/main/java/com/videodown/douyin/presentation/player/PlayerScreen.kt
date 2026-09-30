package com.videodown.douyin.presentation.player

import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.videodown.douyin.data.playback.AudioPlaybackService
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.videodown.douyin.core.galleryErrorMessage
import com.videodown.douyin.core.DouyinIntents
import com.videodown.douyin.core.formatBytes
import com.videodown.douyin.core.formatRelativeTime
import com.videodown.douyin.data.gallery.GalleryExporter
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.videodown.douyin.domain.model.playbackSpeeds
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    recordId: String,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val pipActivity = activity as? com.videodown.douyin.presentation.MainActivity
    val inPip = pipActivity?.inPictureInPicture == true
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val exporter = remember(context) { GalleryExporter(context.applicationContext) }
    var fullscreen by rememberSaveable(recordId) { mutableStateOf(false) }
    var choosingSpeed by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var videoAspectRatio by remember(recordId) { mutableStateOf(16f / 9f) }

    LaunchedEffect(recordId) { viewModel.load(recordId) }

    val localPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
    }
    val isAudio = state.record?.isAudio == true
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var connectionError by remember { mutableStateOf(false) }
    DisposableEffect(context, isAudio) {
        if (!isAudio) return@DisposableEffect onDispose { }
        connectionError = false
        val future = MediaController.Builder(context,
            SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))).buildAsync()
        var disposed = false
        future.addListener({
            if (!disposed) runCatching { future.get() }.onSuccess { controller = it }.onFailure { connectionError = true }
        }, ContextCompat.getMainExecutor(context))
        onDispose { disposed = true; controller = null; MediaController.releaseFuture(future) }
    }
    val player: Player = if (isAudio) controller ?: localPlayer else localPlayer
    LaunchedEffect(isAudio) { if (isAudio) localPlayer.pause() }
    DisposableEffect(localPlayer) {
        onDispose {
            viewModel.saveProgress(localPlayer.currentMediaItem?.mediaId, localPlayer.currentPosition, localPlayer.duration, localPlayer.playbackState == Player.STATE_ENDED)
            localPlayer.release()
        }
    }
    DisposableEffect(player) {
        isPlaying = player.isPlaying
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED))
                    viewModel.saveProgress(player.currentMediaItem?.mediaId, player.currentPosition, player.duration, player.playbackState == Player.STATE_ENDED)
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                viewModel.saveProgress(player.currentMediaItem?.mediaId, player.currentPosition, player.duration, player.playbackState == Player.STATE_ENDED)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) viewModel.saveProgress(player.currentMediaItem?.mediaId, 0, player.duration, true)
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    if (ratio.isFinite() && ratio > 0f) videoAspectRatio = ratio
                }
            }
        }
        player.addListener(listener)
        onDispose {
            viewModel.saveProgress(player.currentMediaItem?.mediaId, player.currentPosition, player.duration, player.playbackState == Player.STATE_ENDED)
            player.removeListener(listener)
        }
    }

    val filePath = state.record?.filePath
    LaunchedEffect(filePath, state.fileExists, player) {
        if (filePath != null && state.fileExists && (!isAudio || controller != null)) {
            if (!isAudio) AudioPlaybackService.pauseActive()
            if (player.currentMediaItem?.mediaId != recordId) {
                player.setMediaItem(MediaItem.Builder().setMediaId(recordId).setUri(Uri.fromFile(File(filePath)))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(state.record?.title).setArtist(state.record?.displayAuthor).build())
                    .build(), state.resumePositionMs)
                player.prepare()
                player.setPlaybackSpeed(state.options.speed)
                player.repeatMode = if (state.options.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                player.playWhenReady = true
            } else if (player.playbackState == Player.STATE_ENDED) { player.seekTo(0); player.play() }
        }
    }
    DisposableEffect(pipActivity, player, isAudio, state.fileExists, videoAspectRatio) {
        pipActivity?.configurePip(player.takeIf { !isAudio && state.fileExists }, videoAspectRatio)
        onDispose { pipActivity?.configurePip(null) }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, player, isAudio) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !isAudio) player.pause()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(player, state.options) {
        player.setPlaybackSpeed(state.options.speed)
        player.repeatMode = if (state.options.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
    LaunchedEffect(player, filePath) {
        while (true) {
            delay(2000)
            if (state.fileExists) viewModel.saveProgress(player.currentMediaItem?.mediaId, player.currentPosition, player.duration, player.playbackState == Player.STATE_ENDED)
        }
    }
    if (choosingSpeed && !inPip) AlertDialog(onDismissRequest = { choosingSpeed = false }, title = { Text("播放速度") },
        text = { Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) { playbackSpeeds.chunked(2).forEach { speeds ->
            Row(Modifier.fillMaxWidth()) {
                speeds.forEach { speed ->
                    TextButton(modifier = Modifier.weight(1f), onClick = {
                        viewModel.options(state.options.copy(speed = speed)); choosingSpeed = false
                    }) {
                        Text("${speed}×" + if (speed == state.options.speed) " · 当前" else "")
                    }
                }
            }
        } } }, confirmButton = { TextButton(onClick = { choosingSpeed = false }) { Text("关闭") } })

    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousBarsBehavior = controller?.systemBarsBehavior
        onDispose {
            controller?.apply {
                show(WindowInsetsCompat.Type.systemBars())
                if (previousBarsBehavior != null) systemBarsBehavior = previousBarsBehavior
            }
            if (previousOrientation != null) activity.requestedOrientation = previousOrientation
        }
    }
    LaunchedEffect(activity, fullscreen, inPip) {
        if (inPip) return@LaunchedEffect
        activity?.requestedOrientation = if (fullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                if (fullscreen) {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }
    BackHandler(fullscreen) { fullscreen = false }

    fun exportToGallery() {
        val record = state.record ?: return
        if (exporting) return
        exporting = true
        scope.launch {
            val result = exporter.export(record)
            exporting = false
            snackbar.showSnackbar(result.fold(
                onSuccess = { if (record.isAudio) "已转存到音乐" else "已转存到相册" },
                onFailure = { "转存失败：${it.galleryErrorMessage()}" },
            ))
        }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) exportToGallery()
        else scope.launch { snackbar.showSnackbar("需要存储权限才能转存到相册") }
    }
    fun requestExport() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            exportToGallery()
        }
    }

    Scaffold(
        topBar = {
            if (!fullscreen && !inPip) {
                TopAppBar(
                    title = {
                        Text(state.record?.title ?: "播放视频", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            AppIcon(AppIcons.Back, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { fullscreen = true }, enabled = state.fileExists && state.record?.isAudio != true) {
                            AppIcon(AppIcons.Fullscreen, contentDescription = "横屏播放")
                        }
                    },
                )
            }
        },
        snackbarHost = { if (!inPip) SnackbarHost(snackbar) },
        contentWindowInsets = if (fullscreen || inPip) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
        containerColor = if (fullscreen || inPip) Color.Black else MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .padding(if (fullscreen || inPip) PaddingValues(0.dp) else padding)
                .fillMaxSize()
                .then(if (fullscreen || inPip) Modifier else Modifier.verticalScroll(rememberScrollState())),
        ) {
            Box(
                (if (fullscreen || inPip) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(videoAspectRatio))
                    .background(Color.Black),
            ) {
                    when {
                        state.loading || (isAudio && controller == null && !connectionError) -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        state.fileExists && !connectionError -> {
                            VideoSurface(
                                player = player,
                                fullscreen = fullscreen,
                                onFullscreenChange = { if (!isAudio && !inPip) fullscreen = it },
                                controlsEnabled = !inPip,
                                modifier = Modifier.fillMaxSize()
                                    .then(if (fullscreen) Modifier.safeDrawingPadding() else Modifier),
                            )
                        }
                        else -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(if (connectionError) "音频播放服务连接失败，请返回后重试" else "文件不存在或尚未下载完成", color = Color.White)
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(onClick = onBack) { Text("返回") }
                                }
                            }
                        }
                    }
                if (fullscreen && state.fileExists && !inPip) {
                    IconButton(
                        onClick = { fullscreen = false },
                        modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp).size(48.dp)
                            .background(Color.Black.copy(alpha = .55f), CircleShape),
                    ) {
                        AppIcon(AppIcons.FullscreenExit, contentDescription = "退出全屏", tint = Color.White)
                    }
                }
            }
            if (!fullscreen && !inPip) {
                state.record?.let { record ->
                    Column(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(record.title, style = MaterialTheme.typography.titleMedium,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${record.displayAuthor} · ${record.resolutionLabel} · " +
                                formatBytes(record.totalBytes.takeIf { it > 0 } ?: record.downloadedBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "下载于 ${formatRelativeTime(record.updatedAt)}" +
                                if (record.rangeSupported) " · 支持断点续传" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                                AppIcon(if (isPlaying) AppIcons.Pause else AppIcons.Play,
                                    contentDescription = if (isPlaying) "暂停" else "播放")
                                Spacer(Modifier.width(6.dp))
                                Text(if (isPlaying) "暂停" else "播放")
                            }
                            OutlinedButton(onClick = { fullscreen = true }, enabled = state.fileExists && !record.isAudio) {
                                AppIcon(AppIcons.Fullscreen, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("横屏全屏")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { choosingSpeed = true }, enabled = state.fileExists) { Text("倍速 ${state.options.speed}×") }
                            OutlinedButton(onClick = { viewModel.options(state.options.copy(loop = !state.options.loop)) }, enabled = state.fileExists) {
                                Text(if (state.options.loop) "循环：开启" else "循环：关闭")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = ::requestExport, enabled = state.fileExists && !exporting) {
                                AppIcon(if (record.isAudio) AppIcons.Audio else AppIcons.SaveAlbum, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (exporting) "转存中…" else if (record.isAudio) "转存到音乐" else "转存到相册")
                            }
                            OutlinedButton(onClick = {
                                val file = File(record.filePath.orEmpty())
                                if (!DouyinIntents.shareFile(context, file)) {
                                    scope.launch { snackbar.showSnackbar("分享失败") }
                                }
                            }, enabled = state.fileExists) {
                                AppIcon(AppIcons.Share, contentDescription = "分享")
                            }
                        }
                        if (!record.isAudio) OutlinedButton(onClick = {
                            if (pipActivity?.enterVideoPip() != true) scope.launch { snackbar.showSnackbar("系统暂时无法开启画中画，请检查应用的画中画权限") }
                        }, enabled = state.fileExists && pipActivity?.supportsPip == true) { Text("画中画") }
                        OutlinedButton(onClick = {
                            val file = File(record.filePath.orEmpty())
                            if (!DouyinIntents.playExternally(context, file)) {
                                scope.launch { snackbar.showSnackbar("没有可用的外部播放器") }
                            }
                        }, enabled = state.fileExists) { Text("外部播放器") }
                        OutlinedButton(onClick = {
                            if (!DouyinIntents.openVideo(context, record)) {
                                scope.launch { snackbar.showSnackbar("未能打开${record.platform.label}") }
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            AppIcon(AppIcons.ExternalLink, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("在${record.platform.label}中查看原视频")
                        }
                    }
                }
            }
        }
    }
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun VideoSurface(
    player: Player,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    controlsEnabled: Boolean,
    modifier: Modifier,
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setEnableComposeSurfaceSyncWorkaround(true)
            }
        },
        update = { view ->
            view.player = player
            view.useController = controlsEnabled
            if (!controlsEnabled) view.hideController()
            view.setFullscreenButtonClickListener(onFullscreenChange)
            view.setFullscreenButtonState(fullscreen)
        },
        onRelease = { view ->
            view.setFullscreenButtonClickListener(null)
            view.player = null
        },
        modifier = modifier,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
