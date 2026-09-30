package com.videodown.douyin.data.generic

import org.junit.Assert.*
import org.junit.Test

class BrowserSessionStoreTest {
    @Test fun `X session checks API origin through its own WebView cookie lookup`() {
        assertEquals(listOf("https://x.com/a/status/12345", "https://x.com/", "https://api.x.com/1.1/"),
            BrowserSessionStore.extractorCookieUrls("https://x.com/a/status/12345"))
    }

    @Test fun `unrelated pages do not request X session cookies`() {
        assertEquals(listOf("https://example.com/video", "https://example.com/"),
            BrowserSessionStore.extractorCookieUrls("https://example.com/video", "https://example.com/"))
    }

    @Test fun `twitter links also query the current X API origin`() {
        assertTrue(BrowserSessionStore.extractorCookieUrls("https://twitter.com/a/status/12345")
            .contains("https://api.x.com/1.1/"))
    }
}
