package com.videodown.douyin.presentation.player

import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.library.authorText
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.rememberTooltipState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.videodown.douyin.R
import com.videodown.douyin.presentation.videoPlatformLabelRes
import com.videodown.douyin.presentation.platformName
import androidx.compose.ui.platform.testTag
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
import androidx.media3.common.Tracks
import androidx.media3.common.MediaMetadata
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.videodown.douyin.data.playback.AudioPlaybackService
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.presentation.galleryErrorMessage
import com.videodown.douyin.core.DouyinIntents
import com.videodown.douyin.core.formatBytes
import com.videodown.douyin.core.relativeTime
import com.videodown.douyin.presentation.relativeTimeText
import com.videodown.douyin.data.gallery.GalleryExporter
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.videodown.douyin.domain.model.playbackSpeeds
import com.videodown.douyin.domain.model.SleepTimer
import com.videodown.douyin.domain.model.PlayerGestures
import com.videodown.douyin.domain.model.GestureSide
import com.videodown.douyin.data.playback.SubtitleSidecarResolver
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var choosingSleep by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var videoAspectRatio by remember(recordId) { mutableStateOf(16f / 9f) }
    // The gesture layer only owns touches while the player's own controls are hidden, so the seek
    // bar, play button and fullscreen button keep working exactly as before.
    var controllerVisible by remember { mutableStateOf(true) }
    var gestureFeedback by remember { mutableStateOf<String?>(null) }
    var boostedSpeed by remember { mutableStateOf(false) }
    var brightnessOverride by remember { mutableStateOf<Float?>(null) }
    var hasTextTrack by remember(recordId) { mutableStateOf(false) }
    var subtitlesEnabled by remember { mutableStateOf(ServiceLocator.preferences.subtitlesEnabled) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var swipeBrightnessStart by remember { mutableStateOf(0f) }
    var swipeVolumeStart by remember { mutableStateOf(0f) }

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
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED))
                    viewModel.saveProgress(player.currentMediaItem?.mediaId, player.currentPosition, player.duration, player.playbackState == Player.STATE_ENDED)
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                viewModel.saveProgress(player.currentMediaItem?.mediaId, player.currentPosition, player.duration, player.playbackState == Player.STATE_ENDED)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) viewModel.saveProgress(player.currentMediaItem?.mediaId, 0, player.duration, true)
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                hasTextTrack = tracks.groups.any { group ->
                    group.type == C.TRACK_TYPE_TEXT && group.length > 0
                }
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
    val sidecar = remember(filePath) { filePath?.let { SubtitleSidecarResolver.find(File(it)) } }
    LaunchedEffect(filePath, state.fileExists, player) {
        if (filePath != null && state.fileExists && (!isAudio || controller != null)) {
            if (!isAudio) AudioPlaybackService.pauseActive()
            if (player.currentMediaItem?.mediaId != recordId) {
                val builder = MediaItem.Builder().setMediaId(recordId).setUri(Uri.fromFile(File(filePath)))
                // A subtitle file next to the video is offered as an external text track.
                sidecar?.let { found ->
                    builder.setSubtitleConfigurations(listOf(
                        MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(found.file))
                            .setMimeType(found.mimeType)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build(),
                    ))
                }
                player.setMediaItem(builder
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(state.record?.title).setArtist(state.record?.displayAuthor?.ifBlank { context.getString(R.string.unknown_author) }).build())
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
    if (choosingSpeed && !inPip) AlertDialog(onDismissRequest = { choosingSpeed = false }, title = { Text(stringResource(R.string.player_speed_title)) },
        text = { Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) { playbackSpeeds.chunked(2).forEach { speeds ->
            Row(Modifier.fillMaxWidth()) {
                speeds.forEach { speed ->
                    TextButton(modifier = Modifier.weight(1f), onClick = {
                        viewModel.options(state.options.copy(speed = speed)); choosingSpeed = false
                    }) {
                        Text(stringResource(R.string.player_speed_value, speed) +
                            if (speed == state.options.speed) stringResource(R.string.player_speed_current) else "")
                    }
                }
            }
        } } }, confirmButton = { TextButton(onClick = { choosingSpeed = false }) { Text(stringResource(R.string.action_close)) } })

    if (choosingSleep && !inPip) AlertDialog(onDismissRequest = { choosingSleep = false }, title = { Text(stringResource(R.string.player_sleep_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                state.sleepTimerRemaining?.let { stringResource(R.string.player_sleep_active, sleepRemainingText(it)) }
                    ?: stringResource(R.string.player_sleep_hint),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp),
            )
            SleepTimer.minutes.forEach { minutes ->
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    viewModel.setSleepTimer(minutes); choosingSleep = false
                }) { Text(stringResource(R.string.player_sleep_minutes, minutes)) }
            }
            TextButton(modifier = Modifier.fillMaxWidth(), enabled = state.sleepTimerRemaining != null,
                onClick = { viewModel.setSleepTimer(0); choosingSleep = false }) { Text(stringResource(R.string.player_sleep_off)) }
        } },
        confirmButton = { TextButton(onClick = { choosingSleep = false }) { Text(stringResource(R.string.action_cancel)) } })

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
                onSuccess = { context.getString(if (record.isAudio) R.string.player_transfer_audio_done else R.string.player_transfer_video_done) },
                onFailure = { context.getString(R.string.player_transfer_failed, galleryErrorMessage(context, it)) },
            ))
        }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) exportToGallery()
        else scope.launch { snackbar.showSnackbar(context.getString(R.string.player_transfer_permission)) }
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

    val audioManager = remember(context) { context.getSystemService(android.media.AudioManager::class.java) }
    val maxVolume = remember(audioManager) { audioManager?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 1 }
    fun volumeFraction(): Float =
        audioManager?.let { it.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / maxVolume } ?: 0f
    fun applyVolume(fraction: Float) {
        val target = (fraction * maxVolume).roundToInt().coerceIn(0, maxVolume)
        audioManager?.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
    }
    fun currentBrightness(): Float {
        val overridden = activity?.window?.attributes?.screenBrightness ?: -1f
        if (overridden >= 0f) return overridden.coerceIn(0.02f, 1f)
        val system = runCatching {
            android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return (system / 255f).coerceIn(0.02f, 1f)
    }
    fun applyBrightness(value: Float) {
        brightnessOverride = value
        activity?.window?.let { window ->
            window.attributes = window.attributes.apply { screenBrightness = value.coerceIn(0.02f, 1f) }
        }
    }
    // Leaving the player gives the screen brightness back to the system.
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { window ->
                window.attributes = window.attributes.apply { screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }
    }
    fun showFeedback(text: String) {
        gestureFeedback = text
        scope.launch { delay(900); if (gestureFeedback == text) gestureFeedback = null }
    }
    fun applySubtitles(enabled: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled).build()
    }
    LaunchedEffect(hasTextTrack, subtitlesEnabled) { if (hasTextTrack) applySubtitles(subtitlesEnabled) }
    LaunchedEffect(boostedSpeed, player) {
        player.setPlaybackSpeed(if (boostedSpeed) PlayerGestures.LONG_PRESS_SPEED else state.options.speed)
    }
    // Only while the player's own controls are hidden: otherwise the seek bar and buttons inside
    // PlayerView would never see a touch.
    val gestureModifier = if (!isAudio && !inPip && state.fileExists && !controllerVisible) {
        Modifier.playerGestures(
            onTap = { playerView?.showController() },
            onSeek = { side ->
                player.seekTo(PlayerGestures.seekTarget(player.currentPosition, player.duration, side))
                showFeedback(context.getString(R.string.player_seek_seconds, PlayerGestures.seekLabelSeconds(side)))
            },
            onBoost = { boosting -> boostedSpeed = boosting },
            onSwipeStart = {
                swipeVolumeStart = volumeFraction()
                swipeBrightnessStart = currentBrightness()
            },
            onSwipe = { side, dy, height ->
                if (side == GestureSide.LEFT) {
                    val value = PlayerGestures.brightnessAfter(swipeBrightnessStart, dy, height)
                    applyBrightness(value)
                    showFeedback(context.getString(R.string.player_brightness, (value * 100).roundToInt()))
                } else {
                    val value = PlayerGestures.volumeAfter(swipeVolumeStart, dy, height)
                    applyVolume(value)
                    showFeedback(context.getString(R.string.player_volume, (value * 100).roundToInt()))
                }
            },
            onSwipeEnd = { gestureFeedback = null },
        )
    } else {
        Modifier
    }

    Scaffold(
        topBar = {
            if (!fullscreen && !inPip) {
                TopAppBar(
                    title = {
                        Text(state.record?.title ?: stringResource(R.string.player_playing_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            AppIcon(AppIcons.Back, contentDescription = stringResource(R.string.action_back))
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
                                onViewCreated = { playerView = it },
                                onControllerVisibilityChange = { controllerVisible = it },
                                modifier = Modifier.fillMaxSize()
                                    .then(if (fullscreen) Modifier.safeDrawingPadding() else Modifier),
                            )
                            // A sibling overlay rather than an ancestor: PlayerView consumes touches
                            // itself, so an ancestor would never see an unconsumed down.
                            if (gestureModifier !== Modifier) {
                                Box(Modifier.fillMaxSize().then(gestureModifier).testTag("player-surface"))
                            }
                            // The speed boost stays on screen for as long as the finger is held.
                            (gestureFeedback ?: boostedSpeed.takeIf { it }
                                ?.let { stringResource(R.string.player_boost, PlayerGestures.LONG_PRESS_SPEED.toInt()) })?.let { feedback ->
                                Text(
                                    feedback,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.align(Alignment.Center)
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(Color.Black.copy(alpha = .55f))
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                        .testTag("gesture-feedback"),
                                )
                            }
                        }
                        else -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(if (connectionError) R.string.player_audio_service_failed else R.string.player_file_missing), color = Color.White)
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                                }
                            }
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
                            "${authorText(record.displayAuthor)} · ${record.resolutionLabel} · " +
                                formatBytes(record.totalBytes.takeIf { it > 0 } ?: record.downloadedBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.player_downloaded_at, relativeTimeText(relativeTime(record.updatedAt))) +
                                if (record.rangeSupported) stringResource(R.string.player_range_supported) else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val speedDescription = stringResource(R.string.player_speed_description, state.options.speed)
                            val loopDescription = stringResource(if (state.options.loop) R.string.player_loop_enabled else R.string.player_loop_disabled)
                            val loopState = stringResource(if (state.options.loop) R.string.player_on else R.string.player_off)
                            val subtitleDescription = stringResource(if (subtitlesEnabled) R.string.player_subtitles_enabled else R.string.player_subtitles_disabled)
                            val sleepDescription = state.sleepTimerRemaining?.let { sleepRemainingText(it) } ?: stringResource(R.string.player_sleep_title)
                            PlayerActionTooltip(stringResource(R.string.player_speed_title)) {
                                OutlinedButton(onClick = { choosingSpeed = true }, enabled = state.fileExists,
                                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = speedDescription }) {
                                    AppIcon(AppIcons.Speed, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("${state.options.speed}×")
                                }
                            }
                            PlayerActionTooltip(stringResource(if (state.options.loop) R.string.player_loop_off else R.string.player_loop_on)) {
                                FilledIconToggleButton(checked = state.options.loop,
                                    onCheckedChange = { viewModel.options(state.options.copy(loop = it)) },
                                    enabled = state.fileExists, modifier = Modifier.size(48.dp).semantics {
                                        contentDescription = loopDescription
                                        stateDescription = loopState
                                    }) { AppIcon(AppIcons.Repeat, null) }
                            }
                            if (!record.isAudio && hasTextTrack) PlayerActionTooltip(stringResource(if (subtitlesEnabled) R.string.player_subtitles_off else R.string.player_subtitles_on)) {
                                OutlinedButton(onClick = {
                                    subtitlesEnabled = !subtitlesEnabled
                                    ServiceLocator.preferences.subtitlesEnabled = subtitlesEnabled
                                }, enabled = state.fileExists,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("subtitles-toggle")
                                        .semantics { contentDescription = subtitleDescription }) {
                                    Text(stringResource(if (subtitlesEnabled) R.string.player_subtitles else R.string.player_subtitles_short))
                                }
                            }
                            if (record.isAudio) PlayerActionTooltip(stringResource(R.string.player_sleep_title)) {
                                OutlinedButton(onClick = { choosingSleep = true }, enabled = state.fileExists,
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("sleep-timer")
                                        .semantics { contentDescription = sleepDescription }) {
                                    Text(state.sleepTimerRemaining?.let { sleepRemainingText(it) } ?: stringResource(R.string.player_sleep_title))
                                }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlayerIconAction(stringResource(if (exporting) R.string.player_transferring else if (record.isAudio) R.string.player_transfer_audio else R.string.player_transfer_video),
                                if (record.isAudio) AppIcons.Audio else AppIcons.SaveAlbum,
                                enabled = state.fileExists && !exporting, busy = exporting, onClick = ::requestExport)
                            PlayerIconAction(stringResource(R.string.player_share), AppIcons.Share, enabled = state.fileExists) {
                                val file = File(record.filePath.orEmpty())
                                if (!DouyinIntents.shareFile(context, file)) scope.launch { snackbar.showSnackbar(context.getString(R.string.player_share_failed)) }
                            }
                            if (!record.isAudio) PlayerIconAction(stringResource(R.string.player_pip), AppIcons.Pip,
                                enabled = state.fileExists && pipActivity?.supportsPip == true) {
                                if (pipActivity?.enterVideoPip() != true) scope.launch { snackbar.showSnackbar(context.getString(R.string.player_pip_failed)) }
                            }
                            PlayerIconAction(stringResource(R.string.player_external), AppIcons.Play, enabled = state.fileExists) {
                                val file = File(record.filePath.orEmpty())
                                if (!DouyinIntents.playExternally(context, file)) scope.launch { snackbar.showSnackbar(context.getString(R.string.player_external_failed)) }
                            }
                            PlayerIconAction(stringResource(R.string.player_view_original, stringResource(videoPlatformLabelRes(record.platform))), AppIcons.ExternalLink) {
                                if (!DouyinIntents.openVideo(context, record)) scope.launch { snackbar.showSnackbar(context.getString(R.string.player_open_failed, platformName(context, record.platform))) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerActionTooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        content()
    }
}

@Composable
private fun PlayerIconAction(label: String, icon: AppIcons, enabled: Boolean = true,
    busy: Boolean = false, onClick: () -> Unit) {
    PlayerActionTooltip(label) {
        OutlinedIconButton(onClick = onClick, enabled = enabled,
            modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else AppIcon(icon, null)
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
    onViewCreated: (PlayerView) -> Unit,
    onControllerVisibilityChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setEnableComposeSurfaceSyncWorkaround(true)
                onViewCreated(this)
            }
        },
        onReset = { view -> onViewCreated(view) },
        update = { view ->
            view.player = player
            view.useController = controlsEnabled
            if (!controlsEnabled) view.hideController()
            view.setFullscreenButtonClickListener(onFullscreenChange)
            view.setFullscreenButtonState(fullscreen)
            view.setControllerVisibilityListener(object : PlayerView.ControllerVisibilityListener {
                override fun onVisibilityChanged(visibility: Int) {
                    onControllerVisibilityChange(visibility == android.view.View.VISIBLE)
                }
            })
        },
        onRelease = { view ->
            view.setFullscreenButtonClickListener(null)
            view.setControllerVisibilityListener(null as PlayerView.ControllerVisibilityListener?)
            view.player = null
        },
        modifier = modifier,
    )
}

/**
 * Touch gestures for the video surface.
 *
 * Vertical drags and taps live in two separate pointer-input nodes: neither consumes anything until
 * it actually recognises its own gesture, so a still finger produces a tap (and a double tap) while
 * a moving one produces a swipe instead.
 */
private fun Modifier.playerGestures(
    onTap: () -> Unit,
    onSeek: (GestureSide) -> Unit,
    onBoost: (Boolean) -> Unit,
    onSwipeStart: () -> Unit,
    onSwipe: (side: GestureSide, dy: Float, height: Int) -> Unit,
    onSwipeEnd: () -> Unit,
): Modifier = this
    // Tap detection first and dragging last: in the main pointer pass the inner node sees a move
    // before the tap detector consumes it, which is what lets a swipe become a drag rather than a tap.
    .pointerInput(Unit) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = { offset -> onSeek(PlayerGestures.sideFor(offset.x, size.width)) },
            onLongPress = { onBoost(true) },
        )
    }
    // detectTapGestures releases its press scope when the long press *starts*, which is too early to
    // know when the finger lifts, so a separate node ends the boost on release.
    .pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            waitForUpOrCancellation()
            onBoost(false)
        }
    }
    .pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragStart = { offset ->
                onSwipeStart()
                onSwipe(PlayerGestures.sideFor(offset.x, size.width), 0f, size.height)
            },
            onDragEnd = { onSwipeEnd() },
            onDragCancel = { onSwipeEnd() },
        ) { change, dragAmount ->
            change.consume()
            onSwipe(PlayerGestures.sideFor(change.position.x, size.width), dragAmount, size.height)
        }
    }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** `约剩 12 分` or `约剩 45 秒`. */
@Composable
private fun sleepRemainingText(remaining: com.videodown.douyin.domain.model.SleepRemaining): String =
    when (remaining) {
        is com.videodown.douyin.domain.model.SleepRemaining.Minutes -> stringResource(R.string.sleep_remaining_minutes, remaining.value)
        is com.videodown.douyin.domain.model.SleepRemaining.Seconds -> stringResource(R.string.sleep_remaining_seconds, remaining.value)
    }
