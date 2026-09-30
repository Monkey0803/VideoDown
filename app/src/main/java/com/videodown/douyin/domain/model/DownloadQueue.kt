package com.videodown.douyin.domain.model

/** Running slots include download, merge, verification, and retry backoff. */
data class DownloadQueueState(val maxConcurrent: Int = 3, val runningIds: List<String> = emptyList(), val pendingIds: List<String> = emptyList()) {
    fun position(id: String): Int? = pendingIds.indexOf(id).takeIf { it >= 0 }?.plus(1)
}
