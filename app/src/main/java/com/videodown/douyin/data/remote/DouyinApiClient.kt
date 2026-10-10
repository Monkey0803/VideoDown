package com.videodown.douyin.data.remote

import com.videodown.douyin.R
import com.videodown.douyin.core.AppText
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * HTTP access to Douyin.
 *
 * Douyin's web API is authenticated by request signatures (`a_bogus`/`X-Bogus`) which change
 * frequently, so this client deliberately prefers the *share page* route:
 *
 *   GET https://www.iesdouyin.com/share/video/{aweme_id}/
 *
 * That page is server-rendered for anonymous browsers and embeds the full aweme JSON in a
 * `_ROUTER_DATA` script tag. Signed endpoints are kept only as fallbacks.
 */
class DouyinApiClient(
    /**
     * Base URLs are injectable so the parse pipeline can be exercised end-to-end in tests
     * against a local server instead of the live Douyin hosts.
     */
    private val iesdouyinBase: String = IESDOUYIN_DOMAIN,
    private val douyinBase: String = DOUYIN_DOMAIN,
) {

    private val cookieJar = InMemoryCookieJar()

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Expands a `v.douyin.com/xxxx` short link into the final URL it redirects to.
     * Returns the input unchanged when it is not a short link or the request fails.
     */
    fun expandShortLink(shortUrl: String): String {
        val request = Request.Builder()
            .url(shortUrl)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                response.request.url.toString()
            }
        } catch (e: IOException) {
            shortUrl
        }
    }

    /** Fetches the anonymous share page that carries `_ROUTER_DATA`. */
    @Throws(IOException::class)
    fun fetchSharePageHtml(awemeId: String): String {
        val request = Request.Builder()
            .url("$iesdouyinBase/share/video/$awemeId/")
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(AppText.get(R.string.douyin_client_http_status, response.code))
            response.body?.string().orEmpty()
        }
    }

    /** Signed web endpoint; used only when the share page yields nothing usable. */
    fun fetchWebDetailJson(awemeId: String): String? = runCatching {
        val url = "$douyinBase/aweme/v1/web/aweme/detail/?" + listOf(
            "aweme_id=$awemeId",
            "device_platform=webapp",
            "aid=6383",
            "channel=channel_pc_web",
            "pc_client_type=1",
            "version_code=190500",
            "version_name=19.5.0",
            "cookie_enabled=true",
            "platform=PC",
            "downlink=10",
        ).joinToString("&")

        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Referer", "$douyinBase/video/$awemeId")
            .header("Accept", "application/json, text/plain, */*")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    /** Legacy v2 endpoint. Kept as a last-resort fallback. */
    fun fetchLegacyItemInfoJson(awemeId: String): String? = runCatching {
        val request = Request.Builder()
            .url("$iesdouyinBase/web/api/v2/aweme/iteminfo/?item_ids=$awemeId")
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private class InMemoryCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val bucket = store.getOrPut(url.host) { mutableListOf() }
            synchronized(bucket) {
                cookies.forEach { cookie ->
                    bucket.removeAll { it.name == cookie.name }
                    bucket.add(cookie)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val bucket = store[url.host] ?: return emptyList()
            val now = System.currentTimeMillis()
            return synchronized(bucket) {
                bucket.removeAll { it.expiresAt < now }
                bucket.filter { it.matches(url) }
            }
        }
    }

    companion object {
        const val DOUYIN_DOMAIN = "https://www.douyin.com"
        const val IESDOUYIN_DOMAIN = "https://www.iesdouyin.com"
        const val REFERER = "https://www.douyin.com/"

        /** A mobile UA makes Douyin serve the lightweight, SSR-rendered share page. */
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"

        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
