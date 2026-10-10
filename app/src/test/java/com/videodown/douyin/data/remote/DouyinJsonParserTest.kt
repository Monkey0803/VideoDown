package com.videodown.douyin.data.remote

import com.videodown.douyin.core.TestText

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests built on a Douyin share-page payload. The JSON shape mirrors a real
 * `_ROUTER_DATA` response: `loaderData["video_(id)/page"].videoInfoRes.item_list[0]`.
 */
class DouyinJsonParserTest {
    @org.junit.Before
    fun installTextResolver() = com.videodown.douyin.core.TestText.install()


    private val parser = DouyinJsonParser()

    @Test
    fun `parses aweme from an iesdouyin share page`() {
        val video = parser.parseSharePage(sharePageHtml())

        assertNotNull(video)
        video!!
        assertEquals("7123456789012345678", video.awemeId)
        assertEquals("海上日出延时摄影 #风景 #摄影", video.title)
        assertEquals(15_320L, video.durationMs)
        assertEquals("测试创作者", video.author.nickname)
        assertEquals("MS4wLjABAAAAtestsecuid0001", video.author.secUid)
        assertEquals("1000000000000000001", video.author.uid)
        assertEquals("https://p3-sign.douyinpic.com/cover.jpeg", video.coverUrl)
        assertEquals("https://p9-sign.douyinpic.com/avatar.jpeg", video.author.avatarUrl)
    }

    @Test
    fun `exposes every advertised resolution`() {
        val video = parser.parseSharePage(sharePageHtml())!!
        val labels = video.sortedVariants.map { it.label }

        assertTrue("expected 1080P in $labels", labels.contains("1080P"))
        assertTrue("expected 720P in $labels", labels.contains("720P"))
        assertTrue("expected an H.264 option in $labels", labels.any { it.contains("H.264") })
        assertTrue("expected a watermarked option in $labels", labels.any { it.startsWith("douyin_label_watermarked(") })
    }

    @Test
    fun `caps the resolution list at one entry per label`() {
        val video = parser.parseSharePage(sharePageHtml())!!
        val labels = video.sortedVariants.map { it.label }

        assertEquals("labels should be unique: $labels", labels.size, labels.distinct().size)
    }

    @Test
    fun `sorts resolutions from highest to lowest quality`() {
        val video = parser.parseSharePage(sharePageHtml())!!
        val ranks = video.sortedVariants.map { it.qualityRank }

        assertEquals(ranks.sortedDescending(), ranks)
        assertEquals("1080P", video.defaultVariant?.label)
    }

    @Test
    fun `records widths and sizes for each resolution`() {
        val video = parser.parseSharePage(sharePageHtml())!!
        val full = video.sortedVariants.first { it.label == "1080P" }

        assertEquals(1080, full.width)
        assertEquals(1920, full.height)
        assertEquals(4_183_220L, full.sizeBytes)
    }

    @Test
    fun `strips the watermarked path segment when building the play url`() {
        val json = """
            {"aweme_id":"7123456789012345678","desc":"wm","video":{
              "play_addr":{"url_list":["https://v3-web.douyinvod.com/playwm/abc/?ratio=720p"],"width":720,"height":1280}}}
        """.trimIndent()

        val video = parser.parseJson(json)!!
        assertTrue(video.sortedVariants.first().url.contains("/play/"))
        assertTrue(!video.sortedVariants.first().url.contains("/playwm/"))
    }

    @Test
    fun `parses the signed web detail response shape`() {
        val body = """
            {"status_code":0,"aweme_detail":{
              "aweme_id":"7000000000000000001","desc":"web detail","duration":9000,
              "author":{"uid":"1","sec_uid":"SEC","nickname":"作者"},
              "video":{"play_addr":{"url_list":["https://v3-web.douyinvod.com/play/x/?ratio=1080p"],"width":1080,"height":1920,"data_size":123}}}}
        """.trimIndent()

        val video = parser.parseJson(body)
        assertNotNull(video)
        assertEquals("7000000000000000001", video!!.awemeId)
        assertEquals("web detail", video.title)
        assertEquals("作者", video.author.nickname)
        assertEquals("SEC", video.author.secUid)
        assertTrue(video.sortedVariants.isNotEmpty())
    }

    @Test
    fun `prefers the requested aweme over nested recommendations`() {
        val body = """
            {"aweme_detail":{"aweme_id":"7111111111111111111","desc":"requested",
              "video":{"play_addr":{"url_list":["https://cdn/1"],"height":1080}}},
             "related":{"items":[{"aweme_id":"7222222222222222222","desc":"recommended",
              "video":{"play_addr":{"url_list":["https://cdn/2"],"height":720}}}]}}
        """.trimIndent()

        val video = parser.parseJson(body)!!
        assertEquals("7111111111111111111", video.awemeId)
        assertEquals("requested", video.title)
    }

    @Test
    fun `returns null when the payload carries no aweme`() {
        assertNull(parser.parseJson("""{"status_code":0,"item_list":[]}"""))
        assertNull(parser.parseSharePage("<html><body>no router data</body></html>"))
    }

    @Test
    fun `builds the profile deep link and web fallback`() {
        val video = parser.parseSharePage(sharePageHtml())!!
        assertEquals(
            "snssdk1128://user/profile/MS4wLjABAAAAtestsecuid0001",
            video.author.profileDeepLink,
        )
        assertEquals(
            "https://www.douyin.com/user/MS4wLjABAAAAtestsecuid0001",
            video.author.profileUrl,
        )
        assertEquals("snssdk1128://aweme/detail/7123456789012345678", video.videoDeepLink)
    }

    /** Stray braces and quoted braces inside strings must not break brace counting. */
    @Test
    fun `extracts router data when strings contain braces`() {
        val html = """<script>window._ROUTER_DATA = {"a":"}{ not a real brace","b":{"c":1}}</script>"""
        val extracted = parser.extractRouterData(html)
        assertEquals("""{"a":"}{ not a real brace","b":{"c":1}}""", extracted)
    }

    private fun sharePageHtml(): String = """
        <!doctype html><html><head><title>抖音</title></head><body>
        <script>window._ROUTER_DATA = $SHARE_PAGE_JSON</script>
        </body></html>
    """.trimIndent()

    private companion object {
        val SHARE_PAGE_JSON = """
        {
          "loaderData": {
            "video_(id)/page": {
              "videoInfoRes": {
                "item_list": [
                  {
                    "aweme_id": "7123456789012345678",
                    "desc": "海上日出延时摄影 #风景 #摄影",
                    "create_time": 1716200000,
                    "duration": 15320,
                    "share_url": "https://www.iesdouyin.com/share/video/7123456789012345678/",
                    "author": {
                      "uid": "1000000000000000001",
                      "sec_uid": "MS4wLjABAAAAtestsecuid0001",
                      "nickname": "测试创作者",
                      "signature": "记录生活",
                      "avatar_thumb": {
                        "url_list": [
                          "https://p9-sign.douyinpic.com/avatar.jpeg",
                          "https://p3-sign.douyinpic.com/avatar.jpeg"
                        ],
                        "width": 720,
                        "height": 720
                      }
                    },
                    "video": {
                      "width": 1080,
                      "height": 1920,
                      "ratio": "1080p",
                      "duration": 15320,
                      "cover": {
                        "url_list": [
                          "https://p3-sign.douyinpic.com/cover.jpeg",
                          "https://p9-sign.douyinpic.com/cover.jpeg"
                        ]
                      },
                      "play_addr": {
                        "uri": "v0300fg10000test",
                        "url_list": [
                          "https://v3-web.douyinvod.com/video/tos/cn/test-01/?ratio=1080p&a=6383",
                          "https://v26-web.douyinvod.com/video/tos/cn/test-01/?ratio=1080p&a=6383"
                        ],
                        "width": 1080,
                        "height": 1920,
                        "data_size": 4183220
                      },
                      "play_addr_h264": {
                        "uri": "v0300fg10000test",
                        "url_list": [
                          "https://v3-web.douyinvod.com/video/tos/cn/test-01/?ratio=1080p&a=6383&h264=1"
                        ],
                        "width": 1080,
                        "height": 1920,
                        "data_size": 4183220
                      },
                      "download_addr": {
                        "uri": "v0300fg10000test",
                        "url_list": [
                          "https://v3-web.douyinvod.com/video/tos/cn/test-01/?ratio=1080p&a=6383&watermark=1"
                        ],
                        "width": 1080,
                        "height": 1920,
                        "data_size": 4519880
                      },
                      "bit_rate": [
                        {
                          "gear_name": "normal_1080_0",
                          "quality_type": 1,
                          "bit_rate": 2185330,
                          "play_addr": {
                            "uri": "v0300fg10000test",
                            "url_list": [
                              "https://v3-web.douyinvod.com/video/tos/cn/test-01/?br=1080&a=6383"
                            ],
                            "width": 1080,
                            "height": 1920,
                            "data_size": 4183220
                          }
                        },
                        {
                          "gear_name": "normal_720_0",
                          "quality_type": 10,
                          "bit_rate": 1120450,
                          "play_addr": {
                            "uri": "v0300fg10000test",
                            "url_list": [
                              "https://v3-web.douyinvod.com/video/tos/cn/test-01/?br=720&a=6383"
                            ],
                            "width": 720,
                            "height": 1280,
                            "data_size": 2143010
                          }
                        }
                      ]
                    }
                  }
                ]
              }
            }
          },
          "errors": null
        }
        """.trimIndent()
    }
}
