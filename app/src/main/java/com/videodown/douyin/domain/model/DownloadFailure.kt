package com.videodown.douyin.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadFailureKind { NETWORK, LOGIN_REQUIRED, ACCESS_DENIED, LINK_EXPIRED, STORAGE_FULL, STORAGE_ACCESS, FORMAT_CHANGED, UNSUPPORTED, RATE_LIMITED, WIFI_REQUIRED, BACKGROUND_SERVICE, RUNTIME, NO_AUDIO, UNKNOWN }

enum class RecoveryAction(val label: String) { RETRY("重试下载"), REPARSE("重新解析原网址"), BROWSER("打开网页登录/验证"), STORAGE("管理存储空间"), SETTINGS("检查下载设置"), NONE("暂无可用操作") }

data class FailureGuidance(val title: String, val description: String, val action: RecoveryAction, val secondaryAction: RecoveryAction? = null)

fun failureGuidance(kind: DownloadFailureKind): FailureGuidance = when (kind) {
    DownloadFailureKind.NETWORK -> FailureGuidance("网络连接失败", "检查网络连接后重试，已保存的断点会保留。", RecoveryAction.RETRY)
    DownloadFailureKind.LOGIN_REQUIRED -> FailureGuidance("需要登录或验证", "打开原网页完成登录或验证，再用网页登录态解析并选择视频。", RecoveryAction.BROWSER)
    DownloadFailureKind.ACCESS_DENIED -> FailureGuidance("网址过期或访问受限", "先重新解析原网址获取新地址；若网站要求登录，可使用网页辅助。", RecoveryAction.REPARSE, RecoveryAction.BROWSER)
    DownloadFailureKind.LINK_EXPIRED -> FailureGuidance("下载网址已失效", "重新解析原网址获取可用地址，再选择所需视频和画质。", RecoveryAction.REPARSE)
    DownloadFailureKind.STORAGE_FULL -> FailureGuidance("存储空间不足", "清理残留临时文件或删除不需要的视频后继续。合并音视频需要额外空间。", RecoveryAction.STORAGE)
    DownloadFailureKind.STORAGE_ACCESS -> FailureGuidance("无法写入视频文件", "检查存储空间和应用存储是否可用，再重试。", RecoveryAction.STORAGE)
    DownloadFailureKind.FORMAT_CHANGED -> FailureGuidance("视频或画质发生变化", "原有断点可能不适用于新视频，请重新解析并选择画质。", RecoveryAction.REPARSE)
    DownloadFailureKind.NO_AUDIO -> FailureGuidance("原视频没有音轨", "该视频不包含可提取的音频，请选择另一个有声音的视频。原视频已保留。", RecoveryAction.NONE)
    DownloadFailureKind.UNSUPPORTED -> FailureGuidance("当前内容暂不支持", "DRM 和直播无法保存。普通网页可尝试登录或播放后查找视频。", RecoveryAction.NONE)
    DownloadFailureKind.RATE_LIMITED -> FailureGuidance("网站暂时限制请求", "请稍后再试，也可降低同时下载数量，避免频繁请求。", RecoveryAction.SETTINGS, RecoveryAction.RETRY)
    DownloadFailureKind.WIFI_REQUIRED -> FailureGuidance("等待 Wi-Fi", "已启用仅 Wi-Fi 下载。连接 Wi-Fi 后可继续，或调整下载设置。", RecoveryAction.SETTINGS, RecoveryAction.RETRY)
    DownloadFailureKind.BACKGROUND_SERVICE -> FailureGuidance("后台下载已停止", "返回应用后重试，已保存的断点会保留。若反复停止，请检查系统对应用后台运行的限制。", RecoveryAction.RETRY)
    DownloadFailureKind.RUNTIME -> FailureGuidance("解析引擎暂不可用", "检查可用空间；若安装包不完整，请重新安装完整版本。", RecoveryAction.STORAGE)
    DownloadFailureKind.UNKNOWN -> FailureGuidance("下载未完成", "可以保留断点重试；若仍失败，请重新解析原网址。", RecoveryAction.RETRY, RecoveryAction.REPARSE)
}

/** Fallback only for older history that predates typed failure categories. */
fun legacyFailureKind(message: String?): DownloadFailureKind? {
    if (message.isNullOrBlank()) return null
    return when {
        message.contains("空间不足") -> DownloadFailureKind.STORAGE_FULL
        message.contains("Wi-Fi") -> DownloadFailureKind.WIFI_REQUIRED
        message.contains("HTTP 401") || message.contains("要求登录") -> DownloadFailureKind.LOGIN_REQUIRED
        message.contains("HTTP 403") -> DownloadFailureKind.ACCESS_DENIED
        message.contains("HTTP 404") || message.contains("HTTP 410") -> DownloadFailureKind.LINK_EXPIRED
        message.contains("HTTP 429") -> DownloadFailureKind.RATE_LIMITED
        message.contains("DRM") || message.contains("直播") -> DownloadFailureKind.UNSUPPORTED
        message.contains("已变化") || message.contains("续传位置不一致") -> DownloadFailureKind.FORMAT_CHANGED
        message.contains("网络") || message.contains("超时") -> DownloadFailureKind.NETWORK
        else -> DownloadFailureKind.UNKNOWN
    }
}

/** Applies the same retry boundary to older history and typed engine failures. */
val DownloadRecord.canRetryDownload: Boolean
    get() = (failureKind ?: legacyFailureKind(errorMessage)) !in setOf(DownloadFailureKind.UNSUPPORTED, DownloadFailureKind.NO_AUDIO)

/**
 * Typed failures an automatic resume may retry on its own.
 *
 * Kept deliberately narrow: only a network interruption or a full disk is a condition that clears
 * by itself. Authentication, expired links, engine and rate-limit failures need the user.
 */
val AUTO_RESUME_FAILURE_KINDS = setOf(DownloadFailureKind.NETWORK, DownloadFailureKind.STORAGE_FULL)

/** True when the record is resumable and the interruption was not the user's own pause. */
val DownloadRecord.canAutoResume: Boolean
    get() = canRetryDownload && when (status) {
        DownloadStatus.PAUSED -> pauseCause != null && pauseCause != DownloadPauseCause.USER
        DownloadStatus.FAILED -> (failureKind ?: legacyFailureKind(errorMessage)) in AUTO_RESUME_FAILURE_KINDS
        else -> false
    }
