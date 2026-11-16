package com.videodown.douyin.presentation.sites

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.videodown.douyin.R
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.MediaSourceType
import com.videodown.douyin.domain.model.GestureSide
import com.videodown.douyin.domain.model.PlayerGestures
import com.videodown.douyin.domain.model.VideoScaleMode
import com.videodown.douyin.domain.model.playbackSpeeds
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.components.AppDialog
import com.videodown.douyin.presentation.components.AppDialogButtonRow
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons
import com.videodown.douyin.presentation.player.VideoControlsOverlay
import com.videodown.douyin.presentation.player.VideoTrackDialog
import com.videodown.douyin.presentation.player.playerGestures
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.CookieJar
import kotlin.math.roundToInt

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun OnlinePlayerScreen(
    sessionId: String,
    onBack: () -> Unit,
    model: OnlinePlayerViewModel = viewModel(),
) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.activity() as? MainActivity }
    val owner = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var playbackError by remember { mutableStateOf<PlaybackException?>(null) }
    var buffering by remember { mutableStateOf(false) }
    var aspectRatio by remember { mutableFloatStateOf(16f / 9f) }
    var fullscreen by rememberSaveable(sessionId) { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlInteraction by remember { mutableIntStateOf(0) }
    var choosingSpeed by remember { mutableStateOf(false) }
    var choosingScale by remember { mutableStateOf(false) }
    var choosingTracks by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var looping by remember { mutableStateOf(false) }
    var scaleMode by remember { mutableStateOf(VideoScaleMode.FIT) }
    var boostedSpeed by remember { mutableStateOf(false) }
    var gestureFeedback by remember { mutableStateOf<String?>(null) }
    var swipeBrightnessStart by remember { mutableFloatStateOf(0.5f) }
    var swipeVolumeStart by remember { mutableFloatStateOf(0.5f) }

    LaunchedEffect(sessionId) { model.load(sessionId) }
    LaunchedEffect(state.downloadQueued, state.downloadFailed) {
        when {
            state.downloadQueued -> snackbar.showSnackbar(context.getString(R.string.sites_download_queued))
            state.downloadFailed -> snackbar.showSnackbar(context.getString(R.string.sites_download_failed))
        }
        if (state.downloadQueued || state.downloadFailed) model.consumeMessage()
    }

    state.duplicate?.let { duplicate ->
        AppDialog(
            onDismissRequest = model::dismissDuplicate,
            icon = AppIcons.Warning,
            title = stringResource(R.string.sites_duplicate_title),
            actions = {
                AppDialogButtonRow {
                    TextButton(onClick = model::dismissDuplicate) { Text(stringResource(R.string.action_cancel)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = model::confirmDuplicate) { Text(stringResource(R.string.home_download_anyway)) }
                }
            },
        ) { Text(duplicate.title, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    val session = state.session
    if (!state.loading && session == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = onBack) { Text(stringResource(R.string.sites_session_expired)) }
        }
        return
    }
    if (session == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val player = remember(session.id) {
        val headers = buildMap {
            if (session.referer.isNotBlank()) put("Referer", session.referer)
            if (session.userAgent.isNotBlank()) put("User-Agent", session.userAgent)
        }
        val mediaClient = ServiceLocator.douyinApiClient.okHttpClient.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .addNetworkInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().removeHeader("Cookie").build())
            }
            .build()
        val dataSource = OkHttpDataSource.Factory(mediaClient)
            .setDefaultRequestProperties(headers)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
            .apply {
                setHandleAudioBecomingNoisy(true)
                val mime = when (session.variant.media?.type) {
                    MediaSourceType.HLS -> MimeTypes.APPLICATION_M3U8
                    MediaSourceType.DASH -> MimeTypes.APPLICATION_MPD
                    else -> null
                }
                setMediaItem(MediaItem.Builder().setMediaId(session.id).setUri(Uri.parse(session.variant.url))
                    .apply { mime?.let(::setMimeType) }.build(), session.resumePositionMs)
                prepare()
                playWhenReady = true
            }
    }
    if (choosingSpeed) AppDialog(
        onDismissRequest = { choosingSpeed = false },
        icon = AppIcons.Speed,
        title = stringResource(R.string.player_speed_title),
        actions = { AppDialogButtonRow { Spacer(Modifier.weight(1f)); TextButton(onClick = { choosingSpeed = false }) {
            Text(stringResource(R.string.action_close))
        } } },
    ) {
        playbackSpeeds.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { value ->
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = { speed = value; choosingSpeed = false },
                    ) {
                        Text(stringResource(R.string.player_speed_value, value) +
                            if (value == speed) stringResource(R.string.player_speed_current) else "")
                    }
                }
            }
        }
    }
    if (choosingScale) AppDialog(
        onDismissRequest = { choosingScale = false },
        icon = AppIcons.Grid,
        title = stringResource(R.string.player_scale_title),
        actions = { AppDialogButtonRow { Spacer(Modifier.weight(1f)); TextButton(onClick = { choosingScale = false }) {
            Text(stringResource(R.string.action_close))
        } } },
    ) {
        VideoScaleMode.entries.forEach { mode ->
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { scaleMode = mode; choosingScale = false },
            ) {
                Text(stringResource(mode.onlineLabelRes()) +
                    if (mode == scaleMode) stringResource(R.string.player_speed_current) else "")
            }
        }
    }
    if (choosingTracks) VideoTrackDialog(player) { choosingTracks = false }

    LaunchedEffect(player, speed, boostedSpeed) {
        player.setPlaybackSpeed(if (boostedSpeed) PlayerGestures.LONG_PRESS_SPEED else speed)
    }
    LaunchedEffect(player, looping) {
        player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
    LaunchedEffect(controlInteraction, controlsVisible) {
        if (controlsVisible) {
            delay(3_500)
            controlsVisible = false
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) playbackError = null
                if (playbackState == Player.STATE_ENDED) model.saveProgress(0L, player.duration, true)
            }
            override fun onPlayerError(error: PlaybackException) { playbackError = error }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspectRatio = (videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height).coerceIn(.42f, 2.39f)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            model.saveProgress(player.currentPosition, player.duration.takeUnless { it == C.TIME_UNSET } ?: 0L,
                player.playbackState == Player.STATE_ENDED)
            activity?.configurePip(null)
            player.removeListener(listener)
            player.release()
        }
    }
    DisposableEffect(owner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.inPictureInPicture != true) player.pause()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(activity, player, aspectRatio) {
        activity?.configurePip(player, aspectRatio)
        onDispose { activity?.configurePip(null) }
    }
    LaunchedEffect(player) {
        while (true) {
            delay(2_000)
            model.saveProgress(player.currentPosition, player.duration.takeUnless { it == C.TIME_UNSET } ?: 0L, false)
        }
    }
    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        val bars = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        onDispose {
            bars?.show(WindowInsetsCompat.Type.systemBars())
            if (previousOrientation != null) activity.requestedOrientation = previousOrientation
            activity?.window?.let { window ->
                window.attributes = window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }
    LaunchedEffect(activity, fullscreen) {
        activity?.requestedOrientation = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                if (fullscreen) {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                } else show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val audioManager = remember(context) { context.getSystemService(android.media.AudioManager::class.java) }
    val maxVolume = remember(audioManager) { audioManager?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 1 }
    fun volumeFraction(): Float = audioManager?.let {
        it.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / maxVolume
    } ?: 0f
    fun currentBrightness(): Float {
        val overridden = activity?.window?.attributes?.screenBrightness ?: -1f
        if (overridden >= 0f) return overridden.coerceIn(.02f, 1f)
        return (runCatching {
            android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128) / 255f).coerceIn(.02f, 1f)
    }
    fun feedback(text: String) {
        gestureFeedback = text
        scope.launch { delay(900); if (gestureFeedback == text) gestureFeedback = null }
    }
    val gestureModifier = Modifier.playerGestures(
        currentPosition = { player.currentPosition },
        duration = { player.duration },
        onTap = { controlsVisible = !controlsVisible; controlInteraction++ },
        onSeek = { side ->
            player.seekTo(PlayerGestures.seekTarget(player.currentPosition, player.duration, side))
            feedback(context.getString(R.string.player_seek_seconds, PlayerGestures.seekLabelSeconds(side)))
        },
        onBoost = { boostedSpeed = it },
        onSwipeStart = {
            swipeVolumeStart = volumeFraction()
            swipeBrightnessStart = currentBrightness()
        },
        onSwipe = { side, dy, height ->
            if (side == GestureSide.LEFT) {
                val value = PlayerGestures.brightnessAfter(swipeBrightnessStart, dy, height)
                activity?.window?.let { window ->
                    window.attributes = window.attributes.apply { screenBrightness = value }
                }
                feedback(context.getString(R.string.player_brightness, (value * 100).roundToInt()))
            } else {
                val value = PlayerGestures.volumeAfter(swipeVolumeStart, dy, height)
                audioManager?.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                    (value * maxVolume).roundToInt().coerceIn(0, maxVolume), 0)
                feedback(context.getString(R.string.player_volume, (value * 100).roundToInt()))
            }
        },
        onSwipeEnd = { gestureFeedback = null },
        onSeekDrag = { gestureFeedback = com.videodown.douyin.presentation.player.formatPlaybackTime(it) },
        onSeekDragEnd = { target -> player.seekTo(target); gestureFeedback = null; controlInteraction++ },
    )
    BackHandler(enabled = fullscreen) { fullscreen = false }
    BackHandler(enabled = !fullscreen, onBack = onBack)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { PlayerView(it).apply { useController = false; this.player = player } },
            update = {
                it.player = player
                it.resizeMode = when (scaleMode) {
                    VideoScaleMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    VideoScaleMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    VideoScaleMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
            },
        )
        Box(Modifier.fillMaxSize().then(gestureModifier).testTag("online-player-surface"))
        VideoControlsOverlay(
            player = player,
            title = session.video.title,
            visible = controlsVisible,
            fullscreen = fullscreen,
            buffering = buffering,
            looping = looping,
            frameProvider = null,
            scaleMode = scaleMode,
            onBack = { if (fullscreen) fullscreen = false else onBack() },
            onInteraction = { controlsVisible = true; controlInteraction++ },
            onSpeed = { choosingSpeed = true; controlsVisible = true; controlInteraction++ },
            onLoop = { looping = !looping; controlInteraction++ },
            onTracks = { choosingTracks = true; controlsVisible = true; controlInteraction++ },
            onScaleMode = { choosingScale = true; controlsVisible = true; controlInteraction++ },
            onScreenshot = {},
            onPip = { activity?.enterVideoPip() },
            onFullscreen = { fullscreen = !fullscreen; controlInteraction++ },
            showScreenshot = false,
        )
        if (!fullscreen && controlsVisible) Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { AppIcon(AppIcons.Back, stringResource(R.string.action_back), tint = Color.White) }
            Text(session.video.title, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            IconButton(onClick = model::download) {
                AppIcon(AppIcons.Download, stringResource(R.string.sites_download), tint = Color.White)
            }
        }
        gestureFeedback?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center)
                .background(Color.Black.copy(alpha = .58f), MaterialTheme.shapes.medium).padding(horizontal = 14.dp, vertical = 8.dp))
        }
        playbackError?.let {
            Row(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .75f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.player_playback_failed), color = Color.White)
                TextButton(onClick = { playbackError = null; player.prepare(); player.play() }) {
                    Text(stringResource(R.string.player_retry_playback))
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
    }
}

private fun Context.activity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

private fun VideoScaleMode.onlineLabelRes(): Int = when (this) {
    VideoScaleMode.FIT -> R.string.player_scale_fit
    VideoScaleMode.CROP -> R.string.player_scale_crop
    VideoScaleMode.STRETCH -> R.string.player_scale_stretch
}
