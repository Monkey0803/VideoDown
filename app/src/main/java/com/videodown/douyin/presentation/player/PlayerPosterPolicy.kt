package com.videodown.douyin.presentation.player

/** 首帧承接策略；纯逻辑独立出来，避免三个播放器入口再次出现不一致。 */
internal fun shouldShowPlayerPoster(
    posterAvailable: Boolean,
    firstFrameRendered: Boolean,
    isAudio: Boolean = false,
    fullscreen: Boolean = false,
    inPictureInPicture: Boolean = false,
): Boolean = posterAvailable && !firstFrameRendered && !isAudio && !fullscreen && !inPictureInPicture
