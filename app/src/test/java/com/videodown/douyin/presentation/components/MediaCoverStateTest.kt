package com.videodown.douyin.presentation.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCoverStateTest {
    @Test fun `no model never shimmers`() {
        assertFalse(shouldShimmer(null, loaded = false))
        assertFalse(shouldShimmer(null, loaded = true))
    }

    @Test fun `blank addresses never shimmer`() {
        assertFalse(shouldShimmer("", loaded = false))
        assertFalse(shouldShimmer("   ", loaded = false))
    }

    @Test fun `a real address shimmers until loaded`() {
        assertTrue(shouldShimmer("https://cdn.test/a.jpg", loaded = false))
        assertFalse(shouldShimmer("https://cdn.test/a.jpg", loaded = true))
    }

    @Test fun `non string models are treated as loadable`() {
        assertTrue(shouldShimmer(Any(), loaded = false))
        assertFalse(shouldShimmer(Any(), loaded = true))
    }
}
