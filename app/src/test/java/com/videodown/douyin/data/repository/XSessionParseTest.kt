package com.videodown.douyin.data.repository

import com.videodown.douyin.core.AppError
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.data.remote.StubHttpServer
import com.videodown.douyin.data.remote.StubResponse
import com.videodown.douyin.data.remote.XFixtures
import com.videodown.douyin.data.remote.XParseFailure
import com.videodown.douyin.data.remote.XVideoHttpClient
import com.videodown.douyin.domain.model.DownloadFailureKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 原生 X 接口的登录态分支：Cookie + CSRF 头、无登录态直接跳过、被拒升级成登录问题。
 *
 * 不碰 Android，也不访问真实 X：Cookie 由 lambda 提供，接口由本地 stub server 返回。
 */
class XSessionParseTest {
    private val tweet = "https://x.com/fixture/status/${XFixtures.ID}"
    private val session = "auth_token=fixture; ct0=csrf; lang=en"

    private fun client(server: StubHttpServer, cookies: (suspend () -> String?)? = null) = XVideoHttpClient(
        guestUrl = server.baseUrl + "/guest",
        graphqlUrl = server.baseUrl + "/graphql",
        syndicationUrl = server.baseUrl + "/embed",
        sessionCookies = cookies,
    )

    @Test fun `no stored session skips the authenticated attempt without any request`() = runBlocking {
        StubHttpServer(emptyMap()) { _, _ -> StubResponse(500) }.use { server ->
            val repo = XVideoRepository(client(server) { null })
            assertNull(repo.parseWithSession(tweet))
            assertTrue(server.paths.isEmpty())
        }
    }

    @Test fun `an incomplete session counts as signed out`() = runBlocking {
        listOf("auth_token=fixture", "ct0=csrf", "auth_token=; ct0=csrf", "auth_token=fixture; ct0=").forEach { header ->
            StubHttpServer(emptyMap()) { _, _ -> StubResponse(500) }.use { server ->
                val repo = XVideoRepository(client(server) { header })
                assertNull("$header must not count as a session", repo.parseWithSession(tweet))
                assertTrue(server.paths.isEmpty())
            }
        }
    }

    @Test fun `authenticated parse sends cookies csrf and no guest token`() = runBlocking {
        val seen = CopyOnWriteArrayList<Map<String, String>>()
        StubHttpServer(emptyMap()) { path, headers ->
            seen += headers
            if (path == "/guest") StubResponse(body = """{"guest_token":"12345"}""")
            else StubResponse(body = XFixtures.graphql())
        }.use { server ->
            val result = XVideoRepository(client(server) { session }).parseWithSession(tweet) as AppResult.Success
            assertEquals(3, result.value.videos.size)
            assertFalse("a signed parse must not activate a guest token", server.paths.any { it == "/guest" })
            val sent = seen.single()
            assertEquals(session, sent["cookie"])
            assertEquals("csrf", sent["x-csrf-token"])
            assertEquals("OAuth2Session", sent["x-twitter-auth-type"])
            assertNull(sent["x-guest-token"])
            assertTrue(server.paths.single().startsWith("/graphql"))
        }
    }

    @Test fun `a sensitive photo post becomes a downloadable image with the session`() = runBlocking {
        StubHttpServer(emptyMap()) { _, _ -> StubResponse(body = photoOnlyBody()) }.use { server ->
            val result = XVideoRepository(client(server) { session }).parseWithSession(tweet) as AppResult.Success
            val video = result.value.selectedVideo
            assertTrue("a photo-only post is an image entry", video.isImagePost)
            assertEquals("https://pbs.twimg.com/media/HXRAvPzWwAADVjC.jpg?name=orig", video.variants.single().url)
        }
    }

    @Test fun `a tombstone under the session keeps the restricted reason`() = runBlocking {
        StubHttpServer(emptyMap()) { _, _ ->
            StubResponse(body = """{"data":{"tweetResult":{"result":{"__typename":"TweetTombstone"}}}}""")
        }.use { server ->
            val error = (XVideoRepository(client(server) { session }).parseWithSession(tweet) as AppResult.Failure).error
                as AppError.Parse
            assertEquals(XParseFailure.RESTRICTED.storageKey, error.reasonKey)
            assertEquals(DownloadFailureKind.LOGIN_REQUIRED, error.failureKind)
        }
    }

    @Test fun `a rejected session reports a login problem rather than a network error`() = runBlocking {
        StubHttpServer(emptyMap()) { _, _ -> StubResponse(401) }.use { server ->
            val error = (XVideoRepository(client(server) { session }).parseWithSession(tweet) as AppResult.Failure).error
                as AppError.Parse
            assertEquals(XParseFailure.SESSION_AUTH_FAILED.storageKey, error.reasonKey)
            assertEquals(DownloadFailureKind.LOGIN_REQUIRED, error.failureKind)
        }
    }

    /** 一条只有图片的推文：匿名接口只回 tombstone，带登录态才拿得到。 */
    private fun photoOnlyBody(): String = buildJsonObject {
        put("data", buildJsonObject {
            put("tweetResult", buildJsonObject {
                put("result", buildJsonObject {
                    put("__typename", "Tweet")
                    put("rest_id", XFixtures.ID)
                    put("core", buildJsonObject {
                        put("user_results", buildJsonObject {
                            put("result", buildJsonObject {
                                put("rest_id", "42")
                                put("legacy", buildJsonObject {
                                    put("id_str", "42"); put("name", "Shexyo"); put("screen_name", "ShexyoArt")
                                })
                            })
                        })
                    })
                    put("legacy", buildJsonObject {
                        put("id_str", XFixtures.ID)
                        put("full_text", "momo seiko https://t.co/NtA6FTsFiC")
                        put("extended_entities", buildJsonObject {
                            put("media", Json.parseToJsonElement(
                                """[{"type":"photo","media_url_https":"https://pbs.twimg.com/media/HXRAvPzWwAADVjC.jpg"}]"""))
                        })
                    })
                })
            })
        })
    }.toString()
}
