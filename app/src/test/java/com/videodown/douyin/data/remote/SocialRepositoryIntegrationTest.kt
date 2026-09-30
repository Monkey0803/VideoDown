package com.videodown.douyin.data.remote

import com.videodown.douyin.core.AppResult
import com.videodown.douyin.data.repository.DouyinRepositoryImpl
import com.videodown.douyin.data.repository.MultiPlatformVideoRepository
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.VideoPlatform
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class SocialRepositoryIntegrationTest {
    @Test fun `weibo router exposes GIFs as separate downloadable videos`() = runBlocking {
        StubHttpServer(emptyMap()) { path, _ ->
            if (path.startsWith("/ajax/statuses/show")) StubResponse(body = WeiboGifParserTest.MULTI_GIF_POST) else null
        }.use { server ->
            val repository = MultiPlatformVideoRepository(DouyinRepositoryImpl(DouyinApiClient()),
                SocialVideoHttpClient(weiboBase = server.baseUrl, passportBase = server.baseUrl))
            val result = repository.parseVideos("https://weibo.com/1234567890/RkkIUuy8h")
            assertTrue(result.toString(), result is AppResult.Success)
            val videos = (result as AppResult.Success).value.videos
            assertEquals(2, videos.size)
            assertTrue(videos.all { it.variants.size == 1 && it.platform == VideoPlatform.WEIBO })
            assertNotEquals(videos[0].awemeId, videos[1].awemeId)
            val legacy = repository.parseUrl("https://weibo.com/1234567890/RkkIUuy8h")
            assertEquals(videos.first(), (legacy as AppResult.Success).value)
        }
    }

    @Test fun `weibo initializes anonymous visitor cookies then parses the same post`() = runBlocking {
        StubHttpServer(emptyMap()) { path, headers ->
            when {
                path.startsWith("/ajax/statuses/show") -> if (headers["cookie"]?.contains("SUB=test_guest") == true)
                    StubResponse(body = SocialVideoParserTest.WEIBO) else StubResponse(403, """{"error":"Forbidden"}""")
                path == "/visitor/genvisitor" -> StubResponse(body = """gen_callback({"data":{"tid":"test","new_tid":true,"confidence":100}})""")
                path.startsWith("/visitor/visitor") -> StubResponse(body = "cross_domain({});", headers = mapOf("Set-Cookie" to "SUB=test_guest; Path=/"))
                else -> null
            }
        }.use { server ->
            val repository = MultiPlatformVideoRepository(DouyinRepositoryImpl(DouyinApiClient()),
                SocialVideoHttpClient(weiboBase = server.baseUrl, passportBase = server.baseUrl))
            val result = repository.parseUrl("https://weibo.com/1234567890/RklMe8iOM")
            assertTrue(result.toString(), result is AppResult.Success)
            assertEquals(VideoPlatform.WEIBO, (result as AppResult.Success).value.platform)
            assertEquals(2, server.paths.count { it.startsWith("/ajax/statuses/show?id=RklMe8iOM") })
            assertTrue(server.paths.any { it.startsWith("/visitor/visitor?a=incarnate") })
        }
    }

    @Test fun `kuaishou follows a real HTTP redirect and parses the share page`() = runBlocking {
        StubHttpServer(mapOf(
            "/f/test" to StubResponse(302, location = "/fw/photo/3xs8rvdz44kheme"),
            "/fw/photo/3xs8rvdz44kheme" to StubResponse(body = SocialVideoParserTest.KUAISHOU),
        )).use { server ->
            val client = SocialVideoHttpClient.newClient().newBuilder().addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(request.newBuilder().url(server.baseUrl + request.url.encodedPath).build())
            }.build()
            val repository = MultiPlatformVideoRepository(DouyinRepositoryImpl(DouyinApiClient()), SocialVideoHttpClient(client))
            val result = repository.parseShareText("分享快手视频：https://www.kuaishou.com/f/test")
            assertTrue(result.toString(), result is AppResult.Success)
            assertEquals("3xs8rvdz44kheme", (result as AppResult.Success).value.awemeId)
            assertEquals(listOf("/f/test", "/fw/photo/3xs8rvdz44kheme"), server.paths)
        }
    }

    @Test fun `router preserves douyin parsing and raw numeric video ids`() = runBlocking {
        val id = "7123456789012345678"
        val json = """{"aweme_detail":{"aweme_id":"$id","desc":"抖音回归","video":{"play_addr":{"url_list":["https://video.example/douyin.mp4"],"width":720,"height":1280}}}}"""
        StubHttpServer(mapOf("/share/video/$id/" to StubResponse(body = "<script>window._ROUTER_DATA = $json;</script>"),
            "/aweme/v1/web/aweme/detail/" to StubResponse(body = json))).use { server ->
            val repository = MultiPlatformVideoRepository(DouyinRepositoryImpl(DouyinApiClient(server.baseUrl, server.baseUrl)))
            listOf(id, "https://www.douyin.com/video/$id").forEach {
                val result = repository.parseShareText(it)
                assertTrue(result.toString(), result is AppResult.Success)
                assertEquals(VideoPlatform.DOUYIN, (result as AppResult.Success).value.platform)
            }
        }
    }

    @Test fun `visitor cookies obey parent domains paths and secure boundaries`() {
        val jar = VisitorCookieJar()
        val passport = "https://passport.weibo.com/visitor".toHttpUrl()
        // Cookie parsing uses Android's public-suffix asset; exercise matching here with a constructed cookie.
        jar.saveFromResponse(passport, listOf(Cookie.Builder().name("SUB").value("guest")
            .domain("weibo.com").path("/ajax").secure().build()))
        assertEquals("SUB", jar.loadForRequest("https://weibo.com/ajax/statuses/show".toHttpUrl()).single().name)
        assertTrue(jar.loadForRequest("https://www.kuaishou.com/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://weibo.com.evil.example/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://weibo.com/status/123".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://weibo.com/ajax/statuses/show".toHttpUrl()).isEmpty())
    }

    @Test fun `old history still decodes as douyin while platform metadata survives persistence`() {
        val old = Json.decodeFromString<DownloadRecord>("""{"id":"old","awemeId":"7123456789","title":"已有记录"}""")
        assertEquals(VideoPlatform.DOUYIN, old.platform)
        assertNull(old.downloadReferer)
        val newer = old.copy(createdAt = 1L, updatedAt = 2L, platform = VideoPlatform.WEIBO, sourcePageUrl = "https://m.weibo.cn/status/12345678",
            downloadReferer = "https://weibo.com/")
        assertEquals(newer, Json.decodeFromString<DownloadRecord>(Json.encodeToString(DownloadRecord.serializer(), newer)))
    }
}
