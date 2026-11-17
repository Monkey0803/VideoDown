package com.videodown.douyin.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 展开 `b23.tv` 短链。
 *
 * 手动逐跳处理 3xx：只接受跳到可信 B 站主机（`bilibili.com` / `m.bilibili.com` / `b23.tv`）的
 * 重定向，并且最终目标必须是 [BilibiliLink.trustedTarget] 认可的单个视频页。这样即使短链服务
 * 被当成开放重定向，也不会把解析带到任意网站，或把番剧/直播页当成普通投稿。
 */
class BilibiliShortLinkResolver(private val client: OkHttpClient) {
    suspend fun resolve(url: String, maxHops: Int = 3): BilibiliVideoLink? = withContext(Dispatchers.IO) {
        var current = url
        repeat(maxHops) {
            // 入口地址由调用方确认过是 b23.tv 短链，只校验每一跳的目标。
            val next = nextLocation(current) ?: return@withContext null
            BilibiliLink.trustedTarget(next)?.let { return@withContext it }
            val host = next.toHttpUrlOrNull()?.host?.lowercase() ?: return@withContext null
            if (!BilibiliLink.isTrustedRedirectHost(host)) return@withContext null
            current = next
        }
        null
    }

    /** 只发一跳、不自动跟随，拿回 Location 的绝对地址。 */
    private fun nextLocation(url: String): String? = try {
        // 关闭自动跳转，把每一跳都放进我们的可信主机检查里。
        val noFollow = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        noFollow.newCall(
            Request.Builder().url(url).get().header("User-Agent", BILIBILI_USER_AGENT).build(),
        ).execute().use { response ->
            response.header("Location")?.let { location ->
                response.request.url.resolve(location)?.toString()
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        /** 与解析/下载共用同一份浏览器标识，避免短链服务对无 UA 的请求返回验证页。 */
        const val BILIBILI_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
