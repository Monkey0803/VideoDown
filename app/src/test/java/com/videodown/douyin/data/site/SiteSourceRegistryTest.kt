package com.videodown.douyin.data.site

import com.videodown.douyin.domain.model.canonicalSitePageUrl
import com.videodown.douyin.domain.model.SiteCapabilities
import com.videodown.douyin.domain.model.SiteChannel
import com.videodown.douyin.domain.model.SiteSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteSourceRegistryTest {
    @Test fun `kubb exposes every non-live channel`() {
        val kubb = SiteSourceRegistry().get("kubb")!!
        assertEquals(16, kubb.channels.size)
        assertFalse(kubb.channels.any { it.id == "tvlive" })
        assertTrue(kubb.capabilities.onlinePlayback)
        assertTrue(kubb.capabilities.download)
        assertTrue(kubb.capabilities.anonymousOnly)
        assertFalse(kubb.capabilities.live)
        assertEquals("https://m.kubb.tv/movie", kubb.channelUrl(kubb.channels.first()))
    }

    @Test fun `kubb navigation keeps public pages and rejects account routes`() {
        val kubb = SiteSourceRegistry.KUBB
        assertTrue(kubb.allowsNavigation("https://www.kubb.tv/play/abc?id=episode"))
        assertFalse(kubb.allowsNavigation("https://m.kubb.tv/login"))
        assertFalse(kubb.allowsNavigation("https://m.kubb.tv/account/profile"))
        assertFalse(kubb.allowsNavigation("https://m.kubb.tv/vip/upgrade"))
        assertFalse(kubb.allowsNavigation("https://example.com/play/abc"))
        assertFalse(kubb.acceptsMediaUrl("file://pipecdn.vip/movie.m3u8"))
    }

    @Test fun `registry accepts independent adapters and rejects duplicate stable ids`() {
        val second = SiteSource(
            id = "fixture",
            homeUrl = "https://fixture.example",
            channels = listOf(SiteChannel("featured", "/featured")),
            navigationHosts = setOf("fixture.example"),
            capabilities = SiteCapabilities(onlinePlayback = true, download = false),
        )
        val adapter = SiteSourceAdapter(second)
        val registry = SiteSourceRegistry(listOf(SiteSourceRegistry.KUBB_ADAPTER, adapter))
        assertEquals(second, registry.get("fixture"))
        assertEquals(adapter, registry.adapter("fixture"))
        assertTrue(runCatching { SiteSourceRegistry(listOf(adapter, adapter)) }.isFailure)
    }

    @Test fun `canonical page keeps episode query and drops fragment`() {
        assertEquals(
            "https://www.kubb.tv/play/abc?id=episode",
            canonicalSitePageUrl("HTTPS://WWW.KUBB.TV:443/play/abc?id=episode#comments"),
        )
    }
}
