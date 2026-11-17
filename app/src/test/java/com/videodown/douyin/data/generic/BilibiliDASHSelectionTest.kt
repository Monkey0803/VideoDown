package com.videodown.douyin.data.generic

import com.videodown.douyin.domain.model.MediaSourceType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E2：B 站 DASH 的「画面 + 声音」合成一个画质选择。
 *
 * 用 yt-dlp 的 BiliBili 结果形状（视频流与音频流分开、另有一条合并好的低清 MP4）驱动元数据解析，
 * 断言每个纯视频格式都配上了最佳音轨、并且请求会以 `formatSelector` 的形式让引擎合并；
 * 合并后的轨道完整性由 I2 的 `validateMediaFile` 在下载完成时校验（设备用例覆盖）。
 */
class BilibiliDASHSelectionTest {
    private fun parse(json: String) = GenericMetadataParser().parse(
        Json.parseToJsonElement(json).jsonObject,
        "https://www.bilibili.com/video/BV1GJ411x7h7",
    ).selectedVideo

    @Test fun `every DASH quality is paired with the best audio stream`() {
        val video = parse(
            """
            {"id":"BV1GJ411x7h7","title":"B站测试","extractor_key":"BiliBili","webpage_url":"https://www.bilibili.com/video/BV1GJ411x7h7",
             "http_headers":{"Referer":"https://www.bilibili.com/","User-Agent":"UA"},
             "formats":[
               {"format_id":"100026","url":"https://cdn.org/v1080.m4s","protocol":"http_dash_segments","ext":"mp4","vcodec":"avc1.640032","acodec":"none","width":1920,"height":1080,"tbr":3000},
               {"format_id":"30032","url":"https://cdn.org/v360.m4s","protocol":"http_dash_segments","ext":"mp4","vcodec":"avc1.64001e","acodec":"none","width":640,"height":360,"tbr":700},
               {"format_id":"30280","url":"https://cdn.org/a128.m4s","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","tbr":128},
               {"format_id":"30216","url":"https://cdn.org/a64.m4s","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","tbr":64},
               {"format_id":"mp4-360","url":"https://cdn.org/muxed.mp4","ext":"mp4","vcodec":"avc1","acodec":"mp4a","width":640,"height":360}]}
            """.trimIndent(),
        )
        assertEquals(listOf("100026", "30032", "mp4-360"), video.variants.map { it.media!!.formatId })
        video.variants.filter { it.media!!.formatId != "mp4-360" }.forEach { variant ->
            assertEquals("${variant.label} must merge audio", MediaSourceType.SEPARATE, variant.media!!.type)
            assertEquals("${variant.label} must take the best audio", "30280", variant.media!!.audioFormatId)
            assertEquals("${variant.media!!.formatId}+30280", variant.media!!.formatSelector)
            assertEquals("https://cdn.org/a128.m4s", variant.media!!.audioUrl)
            assertTrue("engine tasks own the merge", variant.media!!.usesEngine)
        }
        val muxed = video.variants.single { it.media!!.formatId == "mp4-360" }
        assertNull("a muxed format needs no separate audio", muxed.media!!.audioFormatId)
        assertEquals(MediaSourceType.DIRECT, muxed.media!!.type)
        assertEquals(
            "B站音频只有一条时也要能单独保存",
            listOf("30280", "30216"),
            video.audioVariants.map { it.media!!.formatId },
        )
        assertEquals("BiliBili", video.variants.first().media!!.extractor)
        assertTrue("quality labels expose the resolution", video.variants.first().label.startsWith("1080P"))
    }

    @Test fun `a DRM protected format is filtered and all-DRM content is rejected`() {
        val mixed = parse(
            """
            {"id":"mixed","title":"混合","extractor_key":"BiliBili",
             "formats":[
               {"format_id":"drm-v","url":"https://cdn.org/v-drm","protocol":"http_dash_segments","ext":"mp4","vcodec":"avc1","acodec":"none","has_drm":true},
               {"format_id":"clean-v","url":"https://cdn.org/v","protocol":"http_dash_segments","ext":"mp4","vcodec":"avc1","acodec":"none","width":1280,"height":720},
               {"format_id":"a","url":"https://cdn.org/a","ext":"m4a","vcodec":"none","acodec":"aac"}]}
            """.trimIndent(),
        )
        assertEquals("a DRM stream must never appear as a choice", listOf("clean-v"), mixed.variants.map { it.media!!.formatId })
        assertEquals("clean-v+a", mixed.variants.single().media!!.formatSelector)

        val rejected = runCatching {
            parse(
                """
                {"id":"drm","title":"DRM","extractor_key":"BiliBili",
                 "formats":[
                   {"format_id":"v","url":"https://cdn.org/v","protocol":"http_dash_segments","ext":"mp4","vcodec":"avc1","acodec":"none","has_drm":true},
                   {"format_id":"a","url":"https://cdn.org/a","ext":"m4a","vcodec":"none","acodec":"aac"}]}
                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue("all-DRM content must be rejected, got $rejected", rejected is IllegalArgumentException)
    }
}
