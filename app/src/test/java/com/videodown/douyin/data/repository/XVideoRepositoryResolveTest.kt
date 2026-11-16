package com.videodown.douyin.data.repository

import com.videodown.douyin.data.remote.XVideoHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** 登录态重试前把短链展开成推文地址；用拦截器代替真实网络。 */
class XVideoRepositoryResolveTest {
    private val tweet = "https://x.com/fixture/status/2096916397947257248"

    private fun repository(destination: String?, seen: MutableList<String> = CopyOnWriteArrayList()): XVideoRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            seen.add(request.url.toString())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (destination == null) 404 else 302).message("Found")
                .header("Location", destination.orEmpty())
                .body("".toResponseBody()).build()
        }.build()
        return XVideoRepository(XVideoHttpClient(client))
    }

    @Test fun `a post url is returned unchanged without any request`() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        assertEquals(tweet, repository(destination = null, seen = seen).resolve(tweet))
        assertEquals(emptyList<String>(), seen)
    }

    @Test fun `a short link is expanded to the post url`() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        assertEquals(tweet, repository(tweet, seen).resolve("https://t.co/short"))
        assertEquals(listOf("https://t.co/short"), seen)
    }

    @Test fun `a short link to another site is refused`() = runBlocking {
        assertNull(repository("https://unrelated.example/status/1").resolve("https://t.co/bad"))
    }

    @Test fun `non-post and non-X urls resolve to nothing`() = runBlocking {
        val seen = CopyOnWriteArrayList<String>()
        val repo = repository(tweet, seen)
        assertNull(repo.resolve("https://x.com/fixture"))
        assertNull(repo.resolve("https://example.com/video/1"))
        assertEquals(emptyList<String>(), seen)
    }
}
