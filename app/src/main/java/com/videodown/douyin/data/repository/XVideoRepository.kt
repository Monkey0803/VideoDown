package com.videodown.douyin.data.repository

import com.videodown.douyin.core.*
import com.videodown.douyin.data.remote.*
import com.videodown.douyin.domain.model.ParsedVideos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

class XVideoRepository(private val http: XVideoHttpClient = XVideoHttpClient()) {
    suspend fun parse(url: String): AppResult<ParsedVideos> = try {
        withTimeout(55_000) { parseWithinTimeout(url) }
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        AppResult.Failure(AppError.Network("X 解析超时，请检查网络后重试"))
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        currentCoroutineContext().ensureActive()
        AppResult.Failure(AppError.Network("无法访问 X，请检查网络后重试"))
    }

    private suspend fun parseWithinTimeout(url: String): AppResult<ParsedVideos> {
        val resolved = if (url.toHttpUrlOrNull()?.host == "t.co") http.expandShortLink(url) else url
        val link = XVideoLink.parse(resolved) ?: return AppResult.Failure(AppError.InvalidLink("请粘贴 X / Twitter 推文的视频链接"))
        val parser = XVideoJsonParser()
        suspend fun attempt(embedded: Boolean): XParseResult = try {
            val response = if (embedded) http.syndication(link.postId) else http.graphql(link.postId)
            if (response.status in 200..299) {
                if (embedded) parser.syndication(response.body, link) else parser.graphql(response.body, link)
            } else XParseResult.Failure(if (response.status == 429) "X 请求过于频繁，请稍后重试"
                else "X 访问失败（HTTP ${response.status}），请稍后重试")
        } catch (e: CancellationException) { throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            XParseResult.Failure(if (e is XHttpException && e.status == 429) "X 请求过于频繁，请稍后重试"
                else "无法访问 X，请检查网络后重试")
        }
        val primary = attempt(false)
        val result = if (primary is XParseResult.Failure && primary.allowFallback) attempt(true) else primary
        return when (result) {
            is XParseResult.Success -> AppResult.Success(result.value)
            is XParseResult.Failure -> AppResult.Failure(AppError.Parse(result.message))
        }
    }
}
