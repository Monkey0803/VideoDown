package com.videodown.douyin.presentation.player

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.videodown.douyin.R
import com.videodown.douyin.presentation.components.AppDialog
import com.videodown.douyin.presentation.components.AppDialogButtonRow
import com.videodown.douyin.presentation.components.AppIcons

private data class VideoTrackOption(
    val group: Tracks.Group,
    val index: Int,
    val selected: Boolean,
    val label: String,
)

@Composable
internal fun VideoTrackDialog(player: Player, onDismiss: () -> Unit) {
    val audio = player.currentTracks.groups.options(C.TRACK_TYPE_AUDIO)
    val subtitles = player.currentTracks.groups.options(C.TRACK_TYPE_TEXT)
    AppDialog(
        onDismissRequest = onDismiss,
        icon = AppIcons.Settings,
        title = stringResource(R.string.player_tracks),
        actions = {
            AppDialogButtonRow {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
    ) {
        if (audio.isNotEmpty()) {
            Text(stringResource(R.string.player_audio_tracks))
            TrackButton(stringResource(R.string.player_track_auto), audio.none { it.selected }) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build()
                onDismiss()
            }
            audio.forEach { option -> TrackButton(option.label, option.selected) {
                player.select(option, C.TRACK_TYPE_AUDIO); onDismiss()
            } }
        }
        if (subtitles.isNotEmpty()) {
            Text(stringResource(R.string.player_subtitle_tracks))
            TrackButton(stringResource(R.string.player_subtitles_off), subtitles.none { it.selected }) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                onDismiss()
            }
            subtitles.forEach { option -> TrackButton(option.label, option.selected) {
                player.select(option, C.TRACK_TYPE_TEXT); onDismiss()
            } }
        }
        if (audio.isEmpty() && subtitles.isEmpty()) Text(stringResource(R.string.player_no_extra_tracks))
    }
}

@Composable
private fun TrackButton(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label + if (selected) stringResource(R.string.player_speed_current) else "")
    }
}

private fun List<Tracks.Group>.options(type: Int): List<VideoTrackOption> = flatMap { group ->
    if (group.type != type) return@flatMap emptyList()
    (0 until group.length).map { index ->
        val format = group.getTrackFormat(index)
        VideoTrackOption(group, index, group.isTrackSelected(index), format.trackLabel(index))
    }
}

private fun Format.trackLabel(index: Int): String = label?.takeIf { it.isNotBlank() }
    ?: language?.takeIf { it.isNotBlank() }?.uppercase()
    ?: id?.takeIf { it.isNotBlank() }
    ?: "#${index + 1}"

private fun Player.select(option: VideoTrackOption, type: Int) {
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .clearOverridesOfType(type)
        .setTrackTypeDisabled(type, false)
        .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, listOf(option.index)))
        .build()
}
