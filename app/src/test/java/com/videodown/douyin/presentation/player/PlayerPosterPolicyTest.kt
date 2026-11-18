package com.videodown.douyin.presentation.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPosterPolicyTest {
    @Test fun `poster stays until the first video frame`() {
        assertTrue(shouldShowPlayerPoster(posterAvailable = true, firstFrameRendered = false))
        assertFalse(shouldShowPlayerPoster(posterAvailable = true, firstFrameRendered = true))
    }

    @Test fun `missing poster and audio never create a video poster`() {
        assertFalse(shouldShowPlayerPoster(posterAvailable = false, firstFrameRendered = false))
        assertFalse(shouldShowPlayerPoster(posterAvailable = true, firstFrameRendered = false, isAudio = true))
    }

    @Test fun `local player poster stays out of fullscreen and picture in picture`() {
        assertFalse(shouldShowPlayerPoster(posterAvailable = true, firstFrameRendered = false, fullscreen = true))
        assertFalse(shouldShowPlayerPoster(
            posterAvailable = true,
            firstFrameRendered = false,
            inPictureInPicture = true,
        ))
    }
}
