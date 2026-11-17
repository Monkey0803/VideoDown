package com.videodown.douyin.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * E1：`b23.tv` 短链只在跳到可信 B 站视频页时才被采用。
 *
 * 用本地 302 服务驱动：可信目标直接接受；跳到其它主机、跳到 B 站的非视频页、以及根本没有
 * 跳转的响应都返回 null——不能把开放重定向当成解析入口。
 */
class BilibiliShortLinkResolverTest {
    private val resolver = BilibiliShortLinkResolver(OkHttpClient.Builder().retryOnConnectionFailure(false).build())

    @Test fun `a redirect to a bilibili video page is resolved`() = runBlocking {
        StubHttpServer(mapOf(
            "/s/ok" to StubResponse(status = 302, location = "https://www.bilibili.com/video/BV1GJ411x7h7"),
        )).use { server ->
            val resolved = resolver.resolve("${server.baseUrl}/s/ok")
            assertEquals("https://www.bilibili.com/video/BV1GJ411x7h7", resolved?.url)
            assertEquals("BV1GJ411x7h7", resolved?.videoId)
        }
    }

    @Test fun `a redirect that keeps the part number is resolved with it`() = runBlocking {
        StubHttpServer(mapOf(
            "/s/part" to StubResponse(status = 302, location = "https://www.bilibili.com/video/BV1GJ411x7h7?p=4"),
        )).use { server ->
            assertEquals(4, resolver.resolve("${server.baseUrl}/s/part")?.page)
        }
    }

    @Test fun `a redirect to another host is refused`() = runBlocking {
        StubHttpServer(mapOf(
            "/s/evil" to StubResponse(status = 302, location = "https://evil.example/video/BV1GJ411x7h7"),
        )).use { server ->
            assertNull(resolver.resolve("${server.baseUrl}/s/evil"))
        }
    }

    @Test fun `a bilibili page that is not a video page is refused`() = runBlocking {
        StubHttpServer(mapOf(
            "/s/bangumi" to StubResponse(status = 302, location = "https://www.bilibili.com/bangumi/play/ss1001"),
        )).use { server ->
            // 跳转目标主机可信但不是视频页：不继续跟，也不当成视频。
            assertNull(resolver.resolve("${server.baseUrl}/s/bangumi", maxHops = 1))
        }
    }

    @Test fun `a response without a redirect is refused`() = runBlocking {
        StubHttpServer(mapOf("/s/plain" to StubResponse(status = 200, body = "not a redirect"))).use { server ->
            assertNull(resolver.resolve("${server.baseUrl}/s/plain"))
        }
    }
}
