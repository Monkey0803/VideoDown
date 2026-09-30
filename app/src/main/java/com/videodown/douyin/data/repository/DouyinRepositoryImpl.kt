package com.videodown.douyin.data.repository

import android.util.Log
import com.videodown.douyin.core.AppError
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.toAppError
import com.videodown.douyin.data.remote.DouyinApiClient
import com.videodown.douyin.data.remote.DouyinJsonParser
import com.videodown.douyin.data.remote.DouyinLinkExtractor
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.repository.DouyinRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves Douyin share text into full video metadata.
 *
 * Resolution is tried against several sources in order of reliability, because any single
 * Douyin endpoint tends to break for weeks at a time:
 *
 *  1. the anonymous share page (no signature required, most reliable);
 *  2. the signed web detail endpoint (works when a valid cookie/signature is present);
 *  3. the legacy v2 `iteminfo` endpoint (kept only as a last resort).
 */
class DouyinRepositoryImpl(
    private val api: DouyinApiClient,
    private val parser: DouyinJsonParser = DouyinJsonParser(),
) : DouyinRepository {

    override suspend fun parseShareText(shareText: String): AppResult<DouyinVideo> =
        withContext(Dispatchers.IO) {
            val trimmed = shareText.trim()
            if (trimmed.isEmpty()) {
                return@withContext AppResult.Failure(AppError.InvalidLink("请输入抖音分享链接或分享文案"))
            }

            val awemeId = resolveAwemeId(trimmed)
                ?: return@withContext AppResult.Failure(
                    AppError.InvalidLink(
                        "未能识别抖音视频链接。请粘贴包含 v.douyin.com 或 douyin.com/video/… 的分享内容。",
                    ),
                )

            var best: DouyinVideo? = null

            fun consider(video: DouyinVideo?) {
                if (video == null) return
                val current = best
                if (current == null || (current.variants.isEmpty() && video.variants.isNotEmpty())) {
                    best = video
                }
            }

            // Strategy 1: anonymous share page (SSR payload).
            runCatching { api.fetchSharePageHtml(awemeId) }
                .onFailure { Log.w(TAG, "分享页获取失败: ${it.message}") }
                .getOrNull()
                ?.let { html -> consider(parser.parseSharePage(html)) }

            if (best?.variants.isNullOrEmpty()) {
                // Strategy 2: signed web detail endpoint.
                runCatching { api.fetchWebDetailJson(awemeId) }
                    .onFailure { Log.w(TAG, "web detail 获取失败: ${it.message}") }
                    .getOrNull()
                    ?.let { body -> consider(parser.parseJson(body)) }
            }

            if (best?.variants.isNullOrEmpty()) {
                // Strategy 3: legacy v2 endpoint.
                runCatching { api.fetchLegacyItemInfoJson(awemeId) }
                    .onFailure { Log.w(TAG, "legacy iteminfo 获取失败: ${it.message}") }
                    .getOrNull()
                    ?.let { body -> consider(parser.parseJson(body)) }
            }

            val resolved = best
                ?: return@withContext AppResult.Failure(
                    AppError.Parse("已识别视频 ID $awemeId，但未能获取到视频信息。该接口可能已失效或需要 Cookie。"),
                )

            if (resolved.variants.isEmpty()) {
                AppResult.Failure(AppError.Parse("该作品没有可下载的视频流（可能是图文作品或已被删除）。"))
            } else {
                AppResult.Success(resolved)
            }
        }

    override suspend fun parseUrl(url: String): AppResult<DouyinVideo> = parseShareText(url)

    /** Extracts the numeric aweme id, following a `v.douyin.com` redirect when necessary. */
    private fun resolveAwemeId(text: String): String? {
        DouyinLinkExtractor.extractAwemeId(text)?.let { return it }

        val shareUrl = DouyinLinkExtractor.extractShareUrl(text) ?: return null
        if (!DouyinLinkExtractor.isShortLink(shareUrl)) return null

        val expanded = runCatching { api.expandShortLink(shareUrl) }
            .onFailure { Log.w(TAG, "短链展开失败: ${it.message}") }
            .getOrDefault(shareUrl)

        return DouyinLinkExtractor.extractAwemeId(expanded)
            ?: DouyinLinkExtractor.extractAwemeId(api.expandShortLink(expanded))
    }

    /** Converts an unexpected throwable into a user-facing failure. */
    @Suppress("unused")
    private fun Throwable.asFailure(): AppResult.Failure = AppResult.Failure(toAppError())

    private companion object {
        const val TAG = "DouyinRepository"
    }
}
