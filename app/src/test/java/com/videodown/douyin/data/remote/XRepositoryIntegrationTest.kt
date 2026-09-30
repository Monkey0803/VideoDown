package com.videodown.douyin.data.remote

import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.AppError
import com.videodown.douyin.data.repository.DouyinRepositoryImpl
import com.videodown.douyin.data.repository.MultiPlatformVideoRepository
import com.videodown.douyin.data.repository.XVideoRepository
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.repository.VideoRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class XRepositoryIntegrationTest {
    private fun client(server: StubHttpServer) = XVideoHttpClient(guestUrl = server.baseUrl + "/guest",
        graphqlUrl = server.baseUrl + "/graphql", syndicationUrl = server.baseUrl + "/embed")
    private val url = "https://x.com/fixture/status/${XFixtures.ID}"

    @Test fun `X native success also collects engine formats and card media`() = runBlocking {
        StubHttpServer(emptyMap()) { path, _ -> when {
            path == "/guest" -> StubResponse(body = """{"guest_token":"12345"}""")
            path.startsWith("/graphql") -> StubResponse(body = XFixtures.graphql())
            else -> null
        } }.use { server ->
            var engineCalls = 0
            val fallback = object : VideoRepository {
                override suspend fun parseShareText(shareText: String) = AppResult.Failure(AppError.Parse("unused"))
                override suspend fun parseVideos(shareText: String): AppResult<ParsedVideos> {
                    engineCalls++
                    val hls = VideoVariant("https://video.twimg.com/11111/playlist.m3u8", "1080P · HLS",
                        media = MediaDownload(MediaSourceType.HLS, url, "11111", "hls-1080", extractor = "Twitter"))
                    val card = VideoVariant("https://video.twimg.com/card.m3u8", "720P · HLS",
                        media = MediaDownload(MediaSourceType.HLS, url, "44444", "hls-720", extractor = "Twitter"))
                    return AppResult.Success(ParsedVideos(listOf(
                        DouyinVideo("11111", "原帖", variants = listOf(hls), shareUrl = url, platform = VideoPlatform.GENERIC),
                        DouyinVideo("44444", "卡片", variants = listOf(card), shareUrl = url, platform = VideoPlatform.GENERIC))))
                }
            }
            val repo = MultiPlatformVideoRepository(DouyinRepositoryImpl(DouyinApiClient()),
                x = XVideoRepository(client(server)), fallback = fallback)
            val result = repo.parseVideos(url) as AppResult.Success
            assertEquals(1, engineCalls)
            assertEquals(3, result.value.videos.size)
            assertTrue(result.value.videos.first().variants.any { it.media?.type == MediaSourceType.HLS })
            assertEquals("${XFixtures.ID}_44444", result.value.videos.last().awemeId)
        }
    }

    @Test fun `primary success reuses anonymous session and never requests fallback`() = runBlocking {
        val seen = CopyOnWriteArrayList<Map<String, String>>()
        StubHttpServer(emptyMap()) { path, headers ->
            seen.add(headers)
            if (path == "/guest") StubResponse(body = """{"guest_token":"12345"}""", headers = mapOf("Set-Cookie" to "auth_token=must_not_be_used; Path=/"))
            else StubResponse(body = XFixtures.graphql())
        }.use { server ->
            val repo = XVideoRepository(client(server))
            repeat(2) { assertTrue(repo.parse(url) is AppResult.Success) }
            assertEquals(1, server.paths.count { it == "/guest" })
            assertFalse(server.paths.any { it.startsWith("/embed") })
            assertTrue(seen.all { it["user-agent"]!!.contains("Mozilla") && it["cookie"] == null })
            assertEquals("12345", seen[1]["x-guest-token"])
            assertTrue(server.paths[1].contains("tweetId"))
        }
    }

    @Test fun `guest failure graphql rate limit and malformed data use embed fallback`() = runBlocking {
        listOf("guest", "rate", "json", "empty").forEach { scenario ->
            StubHttpServer(emptyMap()) { path, _ -> when {
                path == "/guest" -> if (scenario == "guest") StubResponse(403) else StubResponse(body = """{"guest_token":"12345"}""")
                path.startsWith("/graphql") -> when (scenario) {
                    "rate" -> StubResponse(429)
                    "json" -> StubResponse(body = "<html>error</html>")
                    else -> StubResponse(body = "{}")
                }
                else -> StubResponse(body = XFixtures.embed())
            } }.use { server ->
                val result = XVideoRepository(client(server)).parse("$url/video/3")
                assertTrue(scenario, result is AppResult.Success)
                assertEquals(1, (result as AppResult.Success).value.selectedIndex)
                assertTrue(server.paths.last().startsWith("/embed?id=${XFixtures.ID}&token=52znno1b1i"))
            }
        }
    }

    @Test fun `expired guest renews once before returning success`() = runBlocking {
        var requests = 0
        StubHttpServer(emptyMap()) { path, _ -> when {
            path == "/guest" -> StubResponse(body = """{"guest_token":"12345"}""")
            path.startsWith("/graphql") -> if (requests++ == 0) StubResponse(401) else StubResponse(body = XFixtures.graphql())
            else -> null
        } }.use { server ->
            assertTrue(XVideoRepository(client(server)).parse(url) is AppResult.Success)
            assertEquals(2, server.paths.count { it == "/guest" })
            assertFalse(server.paths.any { it.startsWith("/embed") })
        }
    }

    @Test fun `access restrictions do not trigger anonymous fallback`() = runBlocking {
        StubHttpServer(mapOf("/guest" to StubResponse(body = """{"guest_token":"12345"}"""),
            "/graphql" to StubResponse(body = """{"data":{"tweetResult":{"result":{"__typename":"TweetUnavailable","reason":"Protected"}}}}"""))).use { server ->
            val result = XVideoRepository(client(server)).parse(url)
            assertTrue(result is AppResult.Failure)
            assertTrue((result as AppResult.Failure).error.message.contains("登录"))
            assertFalse(server.paths.any { it.startsWith("/embed") })
        }
    }

    @Test fun `both paths failing returns actionable error`() = runBlocking {
        StubHttpServer(mapOf("/guest" to StubResponse(429), "/embed" to StubResponse(429))).use { server ->
            val result = XVideoRepository(client(server)).parse(url) as AppResult.Failure
            assertTrue(result.error.message.contains("频繁"))
        }
    }

    @Test fun `short links retain explicit video index and reject unrelated redirects`() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        var destination = "$url/video/3"
        val http = XVideoHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            seen.add(request.url.host)
            assertNull(request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", destination).body("".toResponseBody()).build()
        }.build())
        assertEquals(destination, http.expandShortLink("https://t.co/fixture"))
        destination = "https://unrelated.example/status/12345"
        assertTrue(runCatching { http.expandShortLink("https://t.co/fixture") }.isFailure)
        assertEquals(listOf("t.co", "t.co"), seen)
    }

    @Test fun `cancellation closes stalled guest request without starting fallback`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val paths = CopyOnWriteArrayList<String>()
        val rawClient = OkHttpClient.Builder().addInterceptor { chain ->
            paths.add(chain.request().url.encodedPath)
            entered.countDown()
            try {
                while (!chain.call().isCanceled() && !release.await(10, TimeUnit.MILLISECONDS)) { /* Wait for cancellation. */ }
                throw java.io.IOException("Canceled")
            } finally { release.countDown() }
        }.build()
        val task = launch(Dispatchers.Default) { XVideoRepository(XVideoHttpClient(rawClient)).parse(url) }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            withTimeout(2_000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
            assertEquals(listOf("/1.1/guest/activate.json"), paths)
        } finally { release.countDown(); task.cancelAndJoin() }
    }
}
