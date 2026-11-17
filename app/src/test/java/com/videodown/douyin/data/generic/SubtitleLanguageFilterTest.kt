package com.videodown.douyin.data.generic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E4：字幕表只暴露文本字幕。
 *
 * B 站的 `danmaku`（弹幕 XML）会被引擎列进字幕表，但它不是文本字幕：暴露给用户只会得到一条
 * 无意义的轨。这里同时覆盖对象与数组两种引擎形状，以及机器字幕。
 */
class SubtitleLanguageFilterTest {
    private fun parse(json: String) = GenericMetadataParser().parse(
        Json.parseToJsonElement(json).jsonObject,
        "https://www.bilibili.com/video/BV1GJ411x7h7",
    ).selectedVideo

    @Test fun `danmaku is filtered out of Bilibili subtitles`() {
        val video = parse(
            """
            {"id":"BV1GJ411x7h7","title":"字幕","extractor_key":"BiliBili",
             "formats":[{"format_id":"v","url":"https://cdn.org/v.mp4","ext":"mp4","vcodec":"avc1","acodec":"aac"}],
             "subtitles":{"danmaku":[{"ext":"xml","url":"https://cdn.org/danmaku.xml"}],
                          "zh-CN":[{"ext":"srt","url":"https://cdn.org/zh.srt"}],
                          "en-US":[{"ext":"vtt","url":"https://cdn.org/en.vtt"}],
                          "live_chat":[{"ext":"json","url":"https://cdn.org/chat.json"}]},
             "automatic_captions":{"danmaku":[{"ext":"xml","url":"https://cdn.org/danmaku.xml"}],
                                   "ja":[{"ext":"vtt","url":"https://cdn.org/ja.vtt"}]}}
            """.trimIndent(),
        )
        assertEquals(listOf("zh-CN", "en-US"), video.subtitleLanguages)
        assertEquals(listOf("ja"), video.autoCaptionLanguages)
        assertTrue("弹幕不能出现在任何字幕列表里", video.subtitleLanguages.none { it == "danmaku" })
    }

    @Test fun `danmaku is filtered when the engine reports subtitle names as an array`() {
        val video = parse(
            """
            {"id":"x","title":"字幕","formats":[{"url":"https://cdn.org/v.mp4","ext":"mp4"}],
             "subtitles":["danmaku","zh-Hans"]}
            """.trimIndent(),
        )
        assertEquals(listOf("zh-Hans"), video.subtitleLanguages)
    }

    @Test fun `a video without subtitles keeps an empty list`() {
        val video = parse(
            """{"id":"x","title":"无字幕","formats":[{"url":"https://cdn.org/v.mp4","ext":"mp4"}]}""",
        )
        assertTrue(video.subtitleLanguages.isEmpty())
        assertTrue(video.autoCaptionLanguages.isEmpty())
    }
}
