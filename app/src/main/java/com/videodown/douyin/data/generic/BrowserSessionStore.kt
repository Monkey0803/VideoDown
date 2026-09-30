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
            return (urls + xOrigins + twitterOrigins).distinct()
        }
    }

    suspend fun cookie(url: String): String? = withContext(Dispatchers.Main) {
        CookieManager.getInstance().getCookie(url)
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
