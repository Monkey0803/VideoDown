package com.videodown.douyin.presentation.player

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.media3.common.Player
import com.videodown.douyin.R
import com.videodown.douyin.data.playback.VideoFrameProvider
import com.videodown.douyin.domain.model.VideoScaleMode
import com.videodown.douyin.presentation.components.AppIcon
import com.videodown.douyin.presentation.components.AppIcons
import kotlinx.coroutines.delay

/** 覆盖在 PlayerView 之上的沉浸式控制层；空白区域不拦截底层手势。 */
@Composable
internal fun VideoControlsOverlay(
    player: Player,
    title: String,
    visible: Boolean,
    fullscreen: Boolean,
    buffering: Boolean,
    looping: Boolean,
    frameProvider: VideoFrameProvider?,
    scaleMode: VideoScaleMode,
    onBack: () -> Unit,
    onInteraction: () -> Unit,
    onSpeed: () -> Unit,
    onLoop: () -> Unit,
    onTracks: () -> Unit,
    onScaleMode: () -> Unit,
    onScreenshot: () -> Unit,
    onPip: () -> Unit,
    onFullscreen: () -> Unit,
    showScreenshot: Boolean = true,
) {
    val progressDescription = stringResource(R.string.player_progress)
    var position by remember(player) { mutableLongStateOf(player.currentPosition.coerceAtLeast(0L)) }
    var duration by remember(player) { mutableLongStateOf(player.duration.coerceAtLeast(0L)) }
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var playbackState by remember(player) { mutableStateOf(player.playbackState) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableLongStateOf(0L) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                position = player.currentPosition.coerceAtLeast(0L)
                duration = player.duration.coerceAtLeast(0L)
                playing = player.isPlaying
                playbackState = player.playbackState
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player, scrubbing) {
        while (true) {
            if (!scrubbing) position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            playing = player.isPlaying
            playbackState = player.playbackState
            delay(250)
        }
    }
    LaunchedEffect(scrubbing, scrubPosition, frameProvider) {
        if (!scrubbing || frameProvider == null) {
            preview = null
            return@LaunchedEffect
        }
        delay(120)
        preview = frameProvider.preview(scrubPosition).getOrNull()
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .18f))) {
                if (fullscreen) {
                    Row(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth()
                            .background(Color.Black.copy(alpha = .48f))
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) { AppIcon(AppIcons.Back, stringResource(R.string.action_back), tint = Color.White) }
                        Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                    }
                }

                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    PlayerControlButton(AppIcons.Back, stringResource(R.string.player_rewind_10), 48) {
                        player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)); onInteraction()
                    }
                    val ended = playbackState == Player.STATE_ENDED
                    PlayerControlButton(
                        when {
                            ended -> AppIcons.Retry
                            playing -> AppIcons.Pause
                            else -> AppIcons.Play
                        },
                        stringResource(
                            when {
                                ended -> R.string.player_retry_playback
                                playing -> R.string.action_pause
                                else -> R.string.action_play
                            },
                        ),
                        64,
                    ) {
                        when {
                            ended -> {
                                player.seekTo(0L)
                                player.play()
                            }
                            playing -> player.pause()
                            else -> player.play()
                        }
                        onInteraction()
                    }
                    PlayerControlButton(AppIcons.Forward, stringResource(R.string.player_forward_10), 48) {
                        player.seekTo((player.currentPosition + 10_000L).coerceAtMost(duration.takeIf { it > 0 } ?: Long.MAX_VALUE)); onInteraction()
                    }
                }

                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color.Black.copy(alpha = .58f))
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (scrubbing) {
                        Column(Modifier.align(Alignment.CenterHorizontally), horizontalAlignment = Alignment.CenterHorizontally) {
                            preview?.let {
                                Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit,
                                    modifier = Modifier.width(160.dp).height(90.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black))
                            }
                            Text(formatPlaybackTime(scrubPosition), color = Color.White)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatPlaybackTime(if (scrubbing) scrubPosition else position), color = Color.White)
                        Slider(
                            value = (if (scrubbing) scrubPosition else position).toFloat().coerceAtMost(duration.toFloat().coerceAtLeast(1f)),
                            onValueChange = { scrubbing = true; scrubPosition = it.toLong(); onInteraction() },
                            onValueChangeFinished = {
                                player.seekTo(scrubPosition); position = scrubPosition; scrubbing = false; onInteraction()
                            },
                            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                            modifier = Modifier.weight(1f).semantics { contentDescription = progressDescription },
                        )
                        Text(formatPlaybackTime(duration), color = Color.White)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically) {
                        PlayerControlButton(AppIcons.Speed, stringResource(R.string.player_speed_title), onClick = onSpeed)
                        PlayerControlButton(AppIcons.Repeat,
                            stringResource(if (looping) R.string.player_loop_off else R.string.player_loop_on), onClick = onLoop)
                        PlayerControlButton(AppIcons.Settings, stringResource(R.string.player_tracks), onClick = onTracks)
                        PlayerControlButton(AppIcons.Grid, stringResource(scaleMode.labelRes()), onClick = onScaleMode)
                        if (showScreenshot) {
                            PlayerControlButton(AppIcons.SaveAlbum, stringResource(R.string.player_screenshot), onClick = onScreenshot)
                        }
                        PlayerControlButton(AppIcons.Pip, stringResource(R.string.player_pip), onClick = onPip)
                        PlayerControlButton(if (fullscreen) AppIcons.FullscreenExit else AppIcons.Fullscreen,
                            stringResource(if (fullscreen) R.string.player_exit_fullscreen else R.string.player_enter_fullscreen), onClick = onFullscreen)
                    }
                }
            }
        }
        if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center).size(42.dp), color = Color.White)
    }
}

@Composable
private fun PlayerControlButton(icon: AppIcons, description: String, size: Int = 42, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(size.dp)) {
        AppIcon(icon, description, tint = Color.White, modifier = Modifier.size((size / 2).dp))
    }
}

internal fun formatPlaybackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = seconds / 3_600L
    val minutes = seconds % 3_600L / 60L
    val remainder = seconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, remainder)
    else "%02d:%02d".format(minutes, remainder)
}

private fun VideoScaleMode.labelRes(): Int = when (this) {
    VideoScaleMode.FIT -> R.string.player_scale_fit
    VideoScaleMode.CROP -> R.string.player_scale_crop
    VideoScaleMode.STRETCH -> R.string.player_scale_stretch
}
