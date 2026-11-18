package com.videodown.douyin.data.repository

import com.videodown.douyin.core.AppError
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.data.generic.SessionVideoParser
import com.videodown.douyin.data.remote.StubHttpServer
import com.videodown.douyin.data.remote.StubResponse
import com.videodown.douyin.data.remote.XFixtures
import com.videodown.douyin.data.remote.XVideoHttpClient
import com.videodown.douyin.data.remote.XParseFailure
import com.videodown.douyin.domain.model.DownloadFailureKind
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.ParsedVideos
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.domain.repository.VideoRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** X 的登录态重试路由：只验顺序、归属与失败归类，不碰 Android 与真实网络。 */
class MultiPlatformXSessionTest {
    private val url = "https://x.com/fixture/status/${XFixtures.ID}"

    private fun nativeClient(server: StubHttpServer, client: okhttp3.OkHttpClient = okhttp3.OkHttpClient()) = XVideoHttpClient(
        client = client,
        guestUrl = server.baseUrl + "/guest",
        graphqlUrl = server.baseUrl + "/graphql",
        syndicationUrl = server.baseUrl + "/embed",
    )

    private fun engineVideo(id: String = "11111") = DouyinVideo(id, "引擎结果",
        variants = listOf(VideoVariant("https://video.twimg.com/$id/playlist.m3u8", "1080P · HLS")),
        shareUrl = url, platform = VideoPlatform.GENERIC)

    private class Calls {
        val order = CopyOnWriteArrayList<String>()
        var sessionUrl: String? = null
    }

    private fun repository(
        server: StubHttpServer,
        calls: Calls,
        anonymous: suspend () -> AppResult<ParsedVideos>,
        session: (suspend (String) -> AppResult<ParsedVideos>?)?,
        nativeSession: (suspend (String) -> AppResult<ParsedVideos>?)? = null,
        client: okhttp3.OkHttpClient = okhttp3.OkHttpClient(),
    ): MultiPlatformVideoRepository {
        val fallback = object : VideoRepository {
            override suspend fun parseShareText(shareText: String): AppResult<DouyinVideo> =
                AppResult.Failure(AppError.Parse("", reasonKey = "test:anonymous"))
            override suspend fun parseVideos(shareText: String): AppResult<ParsedVideos> {
                calls.order.add("anonymous")
                return anonymous()
            }
        }
        return MultiPlatformVideoRepository(
            DouyinStub,
            x = XVideoRepository(nativeClient(server, client)),
            fallback = fallback,
            sessionFallback = { target ->
                calls.order.add("session")
                calls.sessionUrl = target
                session?.invoke(target)
            },
            xNativeSession = nativeSession?.let { parser ->
                SessionVideoParser { target ->
                    calls.order.add("native-session")
                    calls.sessionUrl = target
                    parser(target)
                }
            },
        )
    }

    /** 只占位：X 用例永远不会走到抖音解析。 */
    private object DouyinStub : com.videodown.douyin.domain.repository.DouyinRepository {
        override suspend fun parseShareText(shareText: String) =
            AppResult.Failure(AppError.Parse("", reasonKey = "test:douyin"))
        override suspend fun parseUrl(url: String) =
            AppResult.Failure(AppError.Parse("", reasonKey = "test:douyin"))
    }

    private fun nativeSuccess() = StubHttpServer(emptyMap()) { path, _ -> when {
        path == "/guest" -> StubResponse(body = """{"guest_token":"12345"}""")
        path.startsWith("/graphql") -> StubResponse(body = XFixtures.graphql())
        else -> null
    } }

    private fun nativeLoginRequired() = StubHttpServer(emptyMap()) { path, _ ->
        if (path == "/guest") StubResponse(body = """{"guest_token":"12345"}""")
        else StubResponse(body = """{"data":{"tweetResult":{"result":{"__typename":"TweetUnavailable","reason":"Protected"}}}}""")
    }

    @Test fun `anonymous success never asks for the saved session`() = runBlocking {
        val calls = Calls()
        nativeSuccess().use { server ->
            val repo = repository(server, calls, anonymous = { AppResult.Success(ParsedVideos(listOf(engineVideo()))) },
                session = { error("session must not be used") },
                nativeSession = { error("native session must not be used") })
            val result = repo.parseVideos(url)
            assertTrue(result is AppResult.Success)
            assertEquals(listOf("anonymous"), calls.order)
            assertNull(calls.sessionUrl)
        }
    }

    @Test fun `native login-required asks the saved session first`() = runBlocking {
        val calls = Calls()
        nativeLoginRequired().use { server ->
            val repo = repository(server, calls, anonymous = { error("anonymous engine must not run") },
                session = { AppResult.Success(ParsedVideos(listOf(engineVideo("22222")))) })
            val result = repo.parseVideos(url) as AppResult.Success
            assertEquals(listOf("session"), calls.order)
            assertEquals(url, calls.sessionUrl)
            val video = result.value.videos.single()
            assertEquals("${XFixtures.ID}_22222", video.awemeId)
            assertEquals(VideoPlatform.X, video.platform)
            assertEquals(url, video.shareUrl)
        }
    }

    @Test fun `failed session falls back to the anonymous engine`() = runBlocking {
        val calls = Calls()
        nativeLoginRequired().use { server ->
            val repo = repository(server, calls,
                anonymous = { AppResult.Success(ParsedVideos(listOf(engineVideo("33333")))) },
                session = { AppResult.Failure(AppError.Parse("", DownloadFailureKind.LOGIN_REQUIRED, reasonKey = "engine:auth")) })
            val result = repo.parseVideos(url) as AppResult.Success
            assertEquals(listOf("session", "anonymous"), calls.order)
            assertEquals("${XFixtures.ID}_33333", result.value.videos.single().awemeId)
        }
    }

    @Test fun `anonymous failure is followed by a session attempt`() = runBlocking {
        val calls = Calls()
        nativeSuccess().use { server ->
            val repo = repository(server, calls,
                anonymous = { AppResult.Failure(AppError.Parse("", DownloadFailureKind.NETWORK, reasonKey = "engine:network")) },
                session = { AppResult.Success(ParsedVideos(listOf(engineVideo("44444")))) })
            val result = repo.parseVideos(url) as AppResult.Success
            assertEquals(listOf("anonymous", "session"), calls.order)
            // 原生成功 + 登录态补到画质：引擎媒体按 ID 追加进 X 结果。
            assertTrue(result.value.videos.any { it.awemeId == "${XFixtures.ID}_44444" })
        }
    }

    @Test fun `rejected session is reported as a login problem`() = runBlocking {
        val calls = Calls()
        nativeLoginRequired().use { server ->
            val repo = repository(server, calls,
                anonymous = { AppResult.Failure(AppError.Parse("", DownloadFailureKind.NETWORK, reasonKey = "engine:network")) },
                session = { AppResult.Failure(AppError.Parse("", DownloadFailureKind.LOGIN_REQUIRED, reasonKey = "engine:auth")) })
            val error = (repo.parseVideos(url) as AppResult.Failure).error as AppError.Parse
            assertEquals(XParseFailure.SESSION_AUTH_FAILED.storageKey, error.reasonKey)
            assertEquals(DownloadFailureKind.LOGIN_REQUIRED, error.failureKind)
            assertEquals(VideoPlatform.X, error.platform)
        }
    }

    @Test fun `access restriction from the session keeps the access wording`() = runBlocking {
        val calls = Calls()
        nativeLoginRequired().use { server ->
            val repo = repository(server, calls,
                anonymous = { AppResult.Failure(AppError.Parse("", DownloadFailureKind.ACCESS_DENIED, reasonKey = "engine:access")) },
                session = { AppResult.Failure(AppError.Parse("", DownloadFailureKind.ACCESS_DENIED, reasonKey = "engine:access")) })
            val error = (repo.parseVideos(url) as AppResult.Failure).error as AppError.Parse
            assertEquals("engine:access", error.reasonKey)
        }
    }

    @Test fun `no usable session keeps today's failure`() = runBlocking {
        val calls = Calls()
        nativeLoginRequired().use { server ->
            val repo = repository(server, calls,
                anonymous = { AppResult.Failure(AppError.Parse("", DownloadFailureKind.LOGIN_REQUIRED, reasonKey = "engine:auth")) },
                session = { null })
            val error = (repo.parseVideos(url) as AppResult.Failure).error as AppError.Parse
            assertEquals("engine:auth", error.reasonKey)
            assertEquals(listOf("session", "anonymous"), calls.order)
        }
    }

    @Test fun `non-login native failure tries the anonymous engine first`() = runBlocking {
        val calls = Calls()
        StubHttpServer(emptyMap()) { path, _ ->
            if (path == "/guest") StubResponse(500) else StubResponse(500)
        }.use { server ->
            val repo = repository(server, calls,
                anonymous = { AppResult.Success(ParsedVideos(listOf(engineVideo("55555")))) },
                session = { AppResult.Success(ParsedVideos(listOf(engineVideo("66666")))) })
            val result = repo.parseVideos(url)
            assertTrue(result is AppResult.Success)
            assertEquals(listOf("anonymous"), calls.order)
        }
    }

    @Test fun `short link is expanded before the session attempt`() = runBlocking {
        val calls = Calls()
        val tweet = "https://x.com/fixture/status/${XFixtures.ID}"
        // 短链本身没有本地服务器可访问，用拦截器把 t.co 的 302 直接回给客户端。
        val shortLinks = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != "t.co") return@addInterceptor chain.proceed(request)
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(302).message("Found").header("Location", tweet)
                .body("".toResponseBody()).build()
        }.build()
        StubHttpServer(emptyMap()) { path, _ -> when {
            path == "/guest" -> StubResponse(body = """{"guest_token":"12345"}""")
            path.startsWith("/graphql") -> StubResponse(401)
            else -> null
        } }.use { server ->
            val repo = repository(server, calls,
                anonymous = { error("anonymous engine must not run") },
                session = { AppResult.Success(ParsedVideos(listOf(engineVideo("77777")))) },
                client = shortLinks)
            val result = repo.parseVideos("https://t.co/short") as AppResult.Success
            assertEquals(tweet, calls.sessionUrl)
            assertEquals("${XFixtures.ID}_77777", result.value.videos.single().awemeId)
            assertEquals(tweet, result.value.videos.single().shareUrl)
        }
    }

    @Test fun `a restricted post asks the native session before any engine`() = runBlocking {
        val calls = Calls()
        nativeTombstone().use { server ->
            val repo = repository(server, calls,
                anonymous = { error("the engine must not run once the native session succeeded") },
                session = { error("the engine session must not run either") },
                nativeSession = { AppResult.Success(ParsedVideos(listOf(engineVideo("88888")))) })
            val result = repo.parseVideos(url) as AppResult.Success
            assertEquals(listOf("native-session"), calls.order)
            assertEquals(url, calls.sessionUrl)
            assertEquals("${XFixtures.ID}_88888", result.value.videos.single().awemeId)
        }
    }

    @Test fun `a restricted post keeps the login reason when no session is stored`() = runBlocking {
        val calls = Calls()
        nativeTombstone().use { server ->
            val repo = repository(server, calls,
                anonymous = { AppResult.Failure(AppError.Parse("", DownloadFailureKind.UNSUPPORTED, reasonKey = "engine:unsupported")) },
                session = { null })
            val error = (repo.parseVideos(url) as AppResult.Failure).error as AppError.Parse
            // 引擎只会说「不支持」；原生知道「需要登录」，登录入口据此出现。
            assertEquals(XParseFailure.RESTRICTED.storageKey, error.reasonKey)
            assertEquals(DownloadFailureKind.LOGIN_REQUIRED, error.failureKind)
            assertEquals(listOf("session", "anonymous"), calls.order)
        }
    }

    @Test fun `a no-video post reports the native reason instead of the engine's generic one`() = runBlocking {
        val calls = Calls()
        nativeNoMedia().use { server ->
            val repo = repository(server, calls,
                anonymous = { AppResult.Failure(AppError.Parse("", DownloadFailureKind.UNSUPPORTED, reasonKey = "engine:unsupported")) },
                session = { null })
            val error = (repo.parseVideos(url) as AppResult.Failure).error as AppError.Parse
            assertEquals(XParseFailure.NO_VIDEO.storageKey, error.reasonKey)
            assertEquals(DownloadFailureKind.NO_VIDEO, error.failureKind)
        }
    }

    @Test fun `a rejected native session is reported as a login problem`() = runBlocking {
        val calls = Calls()
        nativeTombstone().use { server ->
            val repo = repository(server, calls,
                anonymous = { AppResult.Failure(AppError.Parse("", DownloadFailureKind.UNSUPPORTED, reasonKey = "engine:unsupported")) },
                session = { null },
                nativeSession = {
                    AppResult.Failure(AppError.Parse("", DownloadFailureKind.LOGIN_REQUIRED,
                        reasonKey = XParseFailure.LOGIN_REQUIRED.storageKey))
                })
            val error = (repo.parseVideos(url) as AppResult.Failure).error as AppError.Parse
            // 有登录态仍被拒：文案要说「登录可能失效或账号看不到」，并指引重新登录。
            assertEquals(XParseFailure.SESSION_AUTH_FAILED.storageKey, error.reasonKey)
            assertEquals(DownloadFailureKind.LOGIN_REQUIRED, error.failureKind)
        }
    }

    @Test fun `a signed-in post with no media keeps the no-video reason`() = runBlocking {
        val calls = Calls()
        nativeTombstone().use { server ->
            val repo = repository(server, calls,
                anonymous = { AppResult.Failure(AppError.Parse("", DownloadFailureKind.UNSUPPORTED, reasonKey = "engine:unsupported")) },
                session = { null },
                nativeSession = {
                    AppResult.Failure(AppError.Parse("", DownloadFailureKind.NO_VIDEO,
                        reasonKey = XParseFailure.NO_VIDEO.storageKey, platform = VideoPlatform.X))
                })
            val error = (repo.parseVideos(url) as AppResult.Failure).error as AppError.Parse
            // 登录后已确认「没有视频」：不能又退回「需要登录」。
            assertEquals(XParseFailure.NO_VIDEO.storageKey, error.reasonKey)
            assertEquals(DownloadFailureKind.NO_VIDEO, error.failureKind)
        }
    }

    private fun nativeTombstone() = StubHttpServer(emptyMap()) { path, _ -> when {
        path == "/guest" -> StubResponse(body = """{"guest_token":"12345"}""")
        else -> StubResponse(body = """{"data":{"tweetResult":{"result":{"__typename":"TweetTombstone"}}}}""")
    } }

    private fun nativeNoMedia() = StubHttpServer(emptyMap()) { path, _ -> when {
        path == "/guest" -> StubResponse(body = """{"guest_token":"12345"}""")
        // 原生先走 GraphQL（rest_id），拿不到媒体时再走嵌入页接口（id_str + mediaDetails）。
        path.startsWith("/embed") -> StubResponse(body = """{"__typename":"Tweet","id_str":"${XFixtures.ID}","text":"momo seiko"}""")
        else -> StubResponse(body = XFixtures.graphql(buildJsonObject {
            put("__typename", "Tweet"); put("rest_id", XFixtures.ID)
            put("legacy", buildJsonObject { put("id_str", XFixtures.ID); put("full_text", "momo seiko") })
        }))
    } }
}
