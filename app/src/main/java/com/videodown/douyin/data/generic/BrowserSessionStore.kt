package com.videodown.douyin.data.generic

import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

/** WebView owns persistence. Never serialize cookies in a DownloadRecord. */
class BrowserSessionStore {
    companion object {
        /** Query each origin through WebView; never copy a page Cookie header to another host. */
        fun extractorCookieUrls(pageUrl: String, referer: String? = null): List<String> {
            val urls = listOfNotNull(pageUrl, referer)
            val hosts = urls.mapNotNull { it.toHttpUrlOrNull()?.host?.lowercase() }
            val twitter = hosts.any { it == "twitter.com" || it.endsWith(".twitter.com") }
            val x = twitter || hosts.any { it == "x.com" || it.endsWith(".x.com") }
            val xOrigins = if (x) listOf("https://x.com/", "https://api.x.com/1.1/") else emptyList()
            val twitterOrigins = if (twitter) listOf("https://twitter.com/", "https://api.twitter.com/1.1/") else emptyList()
            // B 站：页面地址与 api.bilibili.com 都查一遍，引擎的网页/API 请求各自带对应主机的 Cookie。
            val bilibili = urls.any { com.videodown.douyin.data.remote.BilibiliSession.appliesTo(it) }
            val bilibiliOrigins = if (bilibili) com.videodown.douyin.data.remote.BilibiliSession.cookieOrigins else emptyList()
            return (urls + xOrigins + twitterOrigins + bilibiliOrigins).distinct()
        }
    }

    suspend fun cookie(url: String): String? = withContext(Dispatchers.Main) {
        CookieManager.getInstance().getCookie(url)
    }

    /**
     * X 的登录态是否还在本机。
     *
     * 每次调用都现查 [CookieManager]，**不缓存**：有效期由 X 决定，过期后系统不再返回该 Cookie，
     * 这里自然就变成 false。因此 App 不需要、也不应该自己记一个过期时间。
     */
    suspend fun xSignedIn(): Boolean = XSession.origins.any { url ->
        XSession.signedIn(cookie(url))
    }

    /**
     * B 站的登录态是否还在本机。
     *
     * 与 X 一样每次现查 CookieManager、绝不缓存：有效期由 B 站决定，`SESSDATA` 消失自然变成未登录。
     */
    suspend fun bilibiliSignedIn(): Boolean = com.videodown.douyin.data.remote.BilibiliSession.cookieOrigins.any { url ->
        com.videodown.douyin.data.remote.BilibiliSession.signedIn(cookie(url))
    }

    suspend fun export(urls: List<String>, file: File) {
        val lines = urls.distinct().mapNotNull { url ->
            val parsed = url.toHttpUrlOrNull() ?: return@mapNotNull null
            val value = cookie(url) ?: return@mapNotNull null
            value.split(';').mapNotNull { pair ->
                val name = pair.substringBefore('=').trim()
                val content = pair.substringAfter('=', "").trim()
                if (name.isEmpty() || (name + content).any { it == '\t' || it == '\n' || it == '\r' }) null
                else "${parsed.host}\tFALSE\t/\t${if (parsed.isHttps) "TRUE" else "FALSE"}\t0\t$name\t$content"
            }.joinToString("\n")
        }
        file.writeText("# Netscape HTTP Cookie File\n" + lines.joinToString("\n") + "\n")
    }

    suspend fun clear() = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { continuation ->
            CookieManager.getInstance().removeAllCookies { if (continuation.isActive) continuation.resume(Unit) }
        }
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }
}
