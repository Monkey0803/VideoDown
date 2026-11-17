package com.videodown.douyin.data.generic

import com.videodown.douyin.data.download.DownloadNetworkPolicy
import com.videodown.douyin.data.download.DownloadPaths
import com.videodown.douyin.data.download.EngineRateControl
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.TransferPhase
import java.io.File

/**
 * 引擎下载的最小接口（yt-dlp / FFmpeg）。
 *
 * 抽成接口只是为了让 [com.videodown.douyin.data.download.DownloadEngine] 的重试与冷却逻辑能在
 * JVM 用例里用一个假引擎驱动：真实实现要起 subprocess，只有设备和真机用例跑得了。
 * 实现方负责校验成品（容器、轨道、DRM）并返回最终文件；重试、冷却与落库由引擎调度侧统一负责。
 */
interface EngineTransfer {
    suspend fun download(
        record: DownloadRecord,
        paths: DownloadPaths,
        policy: DownloadNetworkPolicy,
        rateLimitBytesPerSecond: Long? = null,
        /** 共享带宽预算；多个任务同时下载时由它把总速率压回全局限速。 */
        rateControl: EngineRateControl? = null,
        progress: suspend (downloaded: Long, total: Long, speed: Long, phase: TransferPhase) -> Unit,
    ): File
}
