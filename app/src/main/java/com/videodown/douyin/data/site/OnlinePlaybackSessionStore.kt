package com.videodown.douyin.data.site

import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.OnlinePlaybackSession
import com.videodown.douyin.domain.model.VideoVariant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class OnlinePlaybackSessionStore {
    private val sessions = ConcurrentHashMap<String, OnlinePlaybackSession>()

    fun create(
        siteId: String,
        pageUrl: String,
        video: DouyinVideo,
        variant: VideoVariant,
        referer: String,
        userAgent: String,
        resumePositionMs: Long,
    ): OnlinePlaybackSession {
        val id = UUID.randomUUID().toString()
        return OnlinePlaybackSession(id, siteId, pageUrl, video, variant, referer, userAgent, resumePositionMs)
            .also { sessions[id] = it }
    }

    fun get(id: String): OnlinePlaybackSession? = sessions[id]
    fun remove(id: String) { sessions.remove(id) }
}
