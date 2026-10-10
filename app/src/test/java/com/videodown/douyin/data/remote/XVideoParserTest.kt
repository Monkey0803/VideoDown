package com.videodown.douyin.data.remote

import com.videodown.douyin.domain.model.VideoPlatform
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class XVideoParserTest {
    private val parser = XVideoJsonParser()
    private val link = XVideoLink(XFixtures.ID)

    @Test fun `accepts original old domain mobile and indexed links but rejects spoofed hosts`() {
        listOf("x.com", "twitter.com", "mobile.twitter.com", "www.x.com").forEach {
            assertEquals(link.copy(mediaIndex = 2), XVideoLink.parse("https://$it/azurlane_staff/status/${link.postId}/video/2?s=20"))
            assertEquals(VideoPlatform.X, VideoLinkExtractor.extract("分享：https://$it/i/status/${link.postId}。")?.platform)
        }
        assertEquals(link, XVideoLink.parse("https://x.com/i/web/status/${link.postId}"))
        listOf("https://x.com.evil.example/user/status/${link.postId}", "https://evil@x.com/user/status/${link.postId}",
            "https://x.com/user", "https://x.com/user/status/${link.postId}/video/0").forEach { assertNull(XVideoLink.parse(it)) }
    }

    @Test fun `embed tokens match JavaScript double rounding including large ids`() {
        mapOf("2096916397947257248" to "52znno1b1i", "1600009574919962625" to "3vmktiebrx",
            "623160978427936768" to "1idpugrdw8w", "1263145271946551300" to "328ad4kt27v",
            "1000000000000000000" to "2f9lc2ug9mm", "9999999999999999999" to "o8nxcsgioad").forEach { (id, token) ->
            assertEquals(id, token, XSyndicationToken.generate(id))
        }
    }

    @Test fun `graphql separates media and preserves original index across photos`() {
        val result = parser.graphql(XFixtures.graphql(), link.copy(mediaIndex = 3)) as XParseResult.Success
        val videos = result.value.videos
        assertEquals(2, videos.size)
        assertEquals(1, result.value.selectedIndex)
        assertNotEquals(videos[0].awemeId, videos[1].awemeId)
        assertEquals(listOf("1080P", "720P"), videos[0].sortedVariants.map { it.label })
        assertEquals(listOf("360P"), videos[1].sortedVariants.map { it.label })
        assertTrue(videos[1].videoWebUrl.endsWith("/video/3"))
        assertEquals("https://x.com/fixture", videos[0].author.profileUrl)
        assertEquals(VideoPlatform.X.referer, videos[0].variants.first().referer)
    }

    @Test fun `syndication and graphql have equivalent identities and formats`() {
        val a = (parser.graphql(XFixtures.graphql(), link) as XParseResult.Success).value
        val b = (parser.syndication(XFixtures.embed(), link) as XParseResult.Success).value
        assertEquals(a, b)
    }

    @Test fun `visibility wrapper and new user shape still resolve author`() {
        val tweet = XFixtures.tweet().toMutableMap().apply {
            put("core", Json.parseToJsonElement("""{"user_results":{"result":{"rest_id":"42","core":{"name":"Fixture","screen_name":"fixture"}}}}"""))
        }
        val wrapped = buildJsonObject { put("__typename", "TweetWithVisibilityResults"); put("tweet", JsonObject(tweet)) }
        val result = parser.graphql(XFixtures.graphql(wrapped), link) as XParseResult.Success
        assertEquals("Fixture", result.value.selectedVideo.author.nickname)
    }

    @Test fun `retweets use original media author and source post`() {
        val original = XFixtures.tweet("999999999999999999")
        val tweet = buildJsonObject {
            put("rest_id", link.postId); put("__typename", "Tweet")
            put("legacy", buildJsonObject { put("retweeted_status_result", buildJsonObject { put("result", original) }) })
        }
        val result = parser.graphql(XFixtures.graphql(tweet), link) as XParseResult.Success
        assertTrue(result.value.selectedVideo.videoWebUrl.contains("/999999999999999999/"))
    }

    @Test fun `quote media stays distinct and explicit index never selects quote accidentally`() {
        val tweet = JsonObject(XFixtures.tweet().toMutableMap().apply {
            put("quoted_status_result", buildJsonObject { put("result", XFixtures.tweet("999999999999999999")) })
        })
        val result = parser.graphql(XFixtures.graphql(tweet), link) as XParseResult.Success
        assertEquals(4, result.value.videos.size)
        assertTrue(result.value.videos.last().mediaLabel!!.startsWith("引用"))
        assertTrue(parser.graphql(XFixtures.graphql(tweet), link.copy(mediaIndex = 2)) is XParseResult.Failure)
    }

    @Test fun `protected deleted and login restricted posts are terminal failures`() {
        listOf("Protected", "NsfwLoggedOut", "NsfwViewerHasNoStatedAge", "Deleted").forEach {
            val post = buildJsonObject { put("__typename", "TweetUnavailable"); put("reason", it) }
            assertFalse((parser.graphql(XFixtures.graphql(post), link) as XParseResult.Failure).allowFallback)
        }
        assertFalse((parser.syndication("""{"__typename":"TweetTombstone"}""", link) as XParseResult.Failure).allowFallback)
    }

    @Test fun `invalid json missing data and a different tweet never become success`() {
        listOf("<html>login</html>", "{}", XFixtures.graphql(XFixtures.tweet("999999999999999999"))).forEach {
            assertTrue(parser.graphql(it, link) is XParseResult.Failure)
        }
        assertTrue(parser.syndication(XFixtures.embed().replace(link.postId, "999999999999999999"), link) is XParseResult.Failure)
    }

    @Test fun `hls and foreign media hosts cannot be saved as mp4`() {
        val onlyHls = XFixtures.embed().replace("video/mp4", "application/x-mpegURL")
        // Assert the typed reason: the wording lives in resources now.
        assertEquals(XParseFailure.UNSUPPORTED_FORMAT, (parser.syndication(onlyHls, link) as XParseResult.Failure).failure)
        val external = XFixtures.embed().replace("video.twimg.com", "video.twimg.com.evil.example")
        assertTrue(parser.syndication(external, link) is XParseResult.Failure)
    }
}

internal object XFixtures {
    const val ID = "2096916397947257248"
    val media = Json.parseToJsonElement("""[
      {"id_str":"11111","type":"video","media_url_https":"https://pbs.twimg.com/cover.jpg","video_info":{"duration_millis":38000,"variants":[
        {"content_type":"application/x-mpegURL","url":"https://video.twimg.com/playlist.m3u8"},
        {"content_type":"video/mp4","bitrate":2000000,"url":"https://video.twimg.com/ext_tw_video/11111/pu/vid/1280x720/one.mp4"},
        {"content_type":"video/mp4","bitrate":9000000,"url":"https://video.twimg.com/ext_tw_video/11111/pu/vid/1920x1080/one.mp4"}]}},
      {"type":"photo","media_url_https":"https://pbs.twimg.com/photo.jpg"},
      {"id_str":"33333","type":"animated_gif","video_info":{"variants":[
        {"content_type":"video/mp4","url":"https://video.twimg.com/tweet_video/640x360/two.mp4"}]}}
    ]""")
    private val user = buildJsonObject { put("id_str", "42"); put("name", "Fixture"); put("screen_name", "fixture") }
    fun tweet(id: String = ID) = buildJsonObject {
        put("__typename", "Tweet"); put("rest_id", id)
        put("core", buildJsonObject { put("user_results", buildJsonObject { put("result", buildJsonObject { put("rest_id", "42"); put("legacy", user) }) }) })
        put("legacy", buildJsonObject { put("id_str", id); put("full_text", "测试视频 https://t.co/example")
            put("extended_entities", buildJsonObject { put("media", media) }) })
    }
    fun graphql(tweet: JsonObject = tweet()) = buildJsonObject {
        put("data", buildJsonObject { put("tweetResult", buildJsonObject { put("result", tweet) }) })
    }.toString()
    fun embed() = buildJsonObject {
        put("__typename", "Tweet"); put("id_str", ID); put("text", "测试视频 https://t.co/example")
        put("user", user); put("mediaDetails", media)
    }.toString()
}
