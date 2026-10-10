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
        AppResult.Failure(AppError.Network("", reasonKey = "x:timeout"))
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        currentCoroutineContext().ensureActive()
        AppResult.Failure(AppError.Network("", reasonKey = "x:network"))
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
                result.httpStatus?.let { com.videodown.douyin.core.AppError.Http(it) }
                    ?: AppError.Parse("", reasonKey = com.videodown.douyin.core.reasonKeyOf(result.failure)),
            )
        }
    }
}
