package com.videodown.douyin.domain.repository

import com.videodown.douyin.core.AppResult
import com.videodown.douyin.domain.model.DouyinVideo

interface DouyinRepository : VideoRepository {
    /**
     * Accepts anything the Douyin share sheet produces — a bare `v.douyin.com` link, a
     * long `douyin.com/video/...` link, or a full share caption containing one of those
     * plus emoji and hashtags — and resolves it into full video metadata including all
     * selectable renditions.
     */
    override suspend fun parseShareText(shareText: String): AppResult<DouyinVideo>

    /** Convenience for parsing a raw URL that is already known to be a Douyin link. */
    override suspend fun parseUrl(url: String): AppResult<DouyinVideo>
}
