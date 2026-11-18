package com.videodown.douyin.data.repository

import com.videodown.douyin.core.*
import com.videodown.douyin.data.remote.*
import com.videodown.douyin.domain.model.ParsedVideos
import com.videodown.douyin.domain.model.VideoPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

class XVideoRepository(private val http: XVideoHttpClient = XVideoHttpClient()) {
    /**
     * 把分享短链展开成真正的推文地址；已经是推文地址就原样返回。
     *
     * 登录态重试需要它：随包引擎按页面地址取媒体，合并时也要用推文地址，才能写对 X 平台和媒体 ID。
     * 返回 null 表示这不是一条可解析的推文（或短链跳到了非 X 站点），调用方应当跳过登录态尝试。
     */
    suspend fun resolve(url: String): String? = try {
        if (url.toHttpUrlOrNull()?.host == "t.co") http.expandShortLink(url).takeIf { XVideoLink.parse(it) != null }
        else url.takeIf { XVideoLink.parse(it) != null }
    } catch (_: IOException) {
        null
    }

    suspend fun parse(url: String): AppResult<ParsedVideos> = try {
        withTimeout(55_000) { parseWithinTimeout(url) }
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        AppResult.Failure(AppError.Network("", reasonKey = "x:timeout"))
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        currentCoroutineContext().ensureActive()
        AppResult.Failure(AppError.Network("", reasonKey = "x:network"))
    }

    /**
     * 用本机 WebView 的 X 登录态再取一次同一条推文。
     *
     * 返回 `null` 表示没有可用登录态（未登录、Cookie 不完整或不是推文地址），调用方应当照常走匿名路径。
     * 敏感内容/年龄门禁的帖子在匿名接口里只会得到 `TweetTombstone`，只有带上 `auth_token` + `ct0`
     * 才能拿到真实媒体（包括纯图片帖的原图）。
     */
    suspend fun parseWithSession(url: String): AppResult<ParsedVideos>? = try {
        withTimeout(55_000) { parseSessionWithinTimeout(url) }
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        AppResult.Failure(AppError.Network("", reasonKey = "x:timeout"))
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        currentCoroutineContext().ensureActive()
        AppResult.Failure(AppError.Network("", reasonKey = "x:network"))
    }

    private suspend fun parseSessionWithinTimeout(url: String): AppResult<ParsedVideos>? {
        val resolved = if (url.toHttpUrlOrNull()?.host == "t.co") http.expandShortLink(url) else url
        val link = XVideoLink.parse(resolved) ?: return null
        val session = http.sessionCookieHeader() ?: return null
        val parser = XVideoJsonParser()
        val result = try {
            val response = http.graphqlWithSession(link.postId, session)
            if (response.status in 200..299) parser.graphql(response.body, link)
            // 已登录请求被拒：Cookie 还在但服务端不认，和匿名「没登录」不是一件事。
            else XParseResult.Failure(
                if (response.status == 429) XParseFailure.RATE_LIMITED else XParseFailure.SESSION_AUTH_FAILED,
                allowFallback = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            XParseResult.Failure(
                if (e is XHttpException && e.status in listOf(401, 403)) XParseFailure.SESSION_AUTH_FAILED
                else XParseFailure.NETWORK,
                allowFallback = false,
            )
        }
        return when (result) {
            is XParseResult.Success -> AppResult.Success(result.value)
            is XParseResult.Failure -> AppResult.Failure(
                AppError.Parse("", result.failure.failureKind,
                    reasonKey = com.videodown.douyin.core.reasonKeyOf(result.failure), platform = VideoPlatform.X),
            )
        }
    }

    private suspend fun parseWithinTimeout(url: String): AppResult<ParsedVideos> {
        val resolved = if (url.toHttpUrlOrNull()?.host == "t.co") http.expandShortLink(url) else url
        val link = XVideoLink.parse(resolved) ?: return AppResult.Failure(AppError.InvalidLink("", reasonKey = "input:x_link"))
        val parser = XVideoJsonParser()
        suspend fun attempt(embedded: Boolean): XParseResult = try {
            val response = if (embedded) http.syndication(link.postId) else http.graphql(link.postId)
            if (response.status in 200..299) {
                if (embedded) parser.syndication(response.body, link) else parser.graphql(response.body, link)
            } else XParseResult.Failure(
                // 429 is worth retrying later; every other status is reported as an HTTP error.
                if (response.status == 429) XParseFailure.RATE_LIMITED else XParseFailure.NETWORK,
                allowFallback = response.status == 429,
                httpStatus = response.status.takeIf { it != 429 },
            )
        } catch (e: CancellationException) { throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            XParseResult.Failure(
                if (e is XHttpException && e.status == 429) XParseFailure.RATE_LIMITED else XParseFailure.NETWORK,
                httpStatus = (e as? XHttpException)?.status?.takeIf { it != 429 },
            )
        }
        val primary = attempt(false)
        val result = if (primary is XParseResult.Failure && primary.allowFallback) attempt(true) else primary
        return when (result) {
            is XParseResult.Success -> AppResult.Success(result.value)
            is XParseResult.Failure -> AppResult.Failure(
                // A failed request is an HTTP error; the status is carried as a number.
                result.httpStatus?.let { com.videodown.douyin.core.AppError.Http(it, platform = VideoPlatform.X) }
                    ?: AppError.Parse("", result.failure.failureKind,
                        reasonKey = com.videodown.douyin.core.reasonKeyOf(result.failure), platform = VideoPlatform.X),
            )
        }
    }
}
