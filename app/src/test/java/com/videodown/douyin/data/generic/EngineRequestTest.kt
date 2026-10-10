package com.videodown.douyin.data.generic

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRequestTest {

    private fun request(playlist: Boolean = false, browser: Boolean = false, referer: String? = null, userAgent: String? = null) =
        EngineRequest.parse(
            url = "https://example.com/watch",
            browserSession = browser,
            cookieFile = if (browser) "/tmp/session.txt" else null,
            referer = referer,
            userAgent = userAgent,
            playlist = playlist,
        )

    @Test
    fun `a plain parse asks for exactly the linked video`() {
        val json = request()
        assertEquals("parse", (json["mode"] as JsonPrimitive).content)
        assertEquals("https://example.com/watch", (json["url"] as JsonPrimitive).content)
        assertNull("Without the collection switch nothing may ask for a playlist", json["playlist"])
        assertNull(json["playlist_limit"])
    }

    @Test
    fun `the collection switch carries the flag and the documented cap`() {
        val json = request(playlist = true)
        assertEquals(true, (json["playlist"] as JsonPrimitive).content.toBoolean())
        assertEquals(EngineRequest.PLAYLIST_LIMIT, (json["playlist_limit"] as JsonPrimitive).content.toInt())
        assertEquals(20, EngineRequest.PLAYLIST_LIMIT)
    }

    @Test
    fun `headers are only sent when there is something to send`() {
        val bare = request()
        assertEquals("{}", bare["headers"].toString())
        val withHeaders = request(referer = "https://example.com/", userAgent = "Agent/1")
        val headers = withHeaders["headers"]!!.toString()
        assertTrue(headers, headers.contains("\"Referer\":\"https://example.com/\""))
        assertTrue(headers, headers.contains("\"User-Agent\":\"Agent/1\""))
    }

    @Test
    fun `cookies are attached only for a browser session with a cookie file`() {
        assertNull(request(browser = false)["cookies"])
        assertEquals("/tmp/session.txt", (request(browser = true)["cookies"] as JsonPrimitive).content)
    }

    @Test
    fun `the collection switch never leaks into an unrelated field`() {
        val json = request(playlist = true)
        assertFalse(json.containsKey("output"))
        assertFalse(json.containsKey("format"))
        assertFalse(json.containsKey("media_id"))
    }

    private fun download(subtitles: List<String> = emptyList(), automatic: Boolean = false, audioOnly: Boolean = false) =
        EngineRequest.download(
            url = "https://example.com/watch",
            output = "/tmp/media.%(ext)s",
            format = "bv*+ba/b",
            audioOnly = audioOnly,
            mediaId = "entry-2",
            referer = "https://example.com/",
            userAgent = "Agent/1",
            browserSession = false,
            cookieFile = null,
            subtitles = subtitles,
            automaticCaptions = automatic,
        )

    @Test fun `a plain download asks for no subtitles`() {
        val json = download()
        assertEquals("download", (json["mode"] as JsonPrimitive).content)
        assertNull(json["subtitles"])
        assertNull(json["subtitle_langs"])
        assertNull(json["auto_captions"])
    }

    @Test fun `the chosen languages travel as a list`() {
        val json = download(subtitles = listOf("zh-Hans", "en"))
        assertEquals(true, (json["subtitles"] as JsonPrimitive).content.toBoolean())
        assertEquals("""["zh-Hans","en"]""", json["subtitle_langs"].toString())
        assertNull("Real subtitles must not also ask for machine captions", json["auto_captions"])
    }

    @Test fun `machine captions are declared as such`() {
        val json = download(subtitles = listOf("de"), automatic = true)
        assertEquals(true, (json["auto_captions"] as JsonPrimitive).content.toBoolean())
    }

    @Test fun `an audio-only target never asks for an embedded subtitle`() {
        val json = download(subtitles = listOf("zh"), audioOnly = true)
        assertEquals("true", (json["audio_only"] as JsonPrimitive).content)
        assertNull("There is nothing to embed an m4a into", json["subtitles"])
        assertNull(json["subtitle_langs"])
    }

    @Test fun `the download keeps its own identity fields`() {
        val json = download(subtitles = listOf("en"))
        assertEquals("entry-2", (json["media_id"] as JsonPrimitive).content)
        assertEquals("/tmp/media.%(ext)s", (json["output"] as JsonPrimitive).content)
        assertEquals("""{"Referer":"https://example.com/","User-Agent":"Agent/1"}""", json["headers"].toString())
        assertNull(json["cookies"])
    }
}
