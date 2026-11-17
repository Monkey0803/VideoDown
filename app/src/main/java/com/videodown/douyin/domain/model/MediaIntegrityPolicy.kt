package com.videodown.douyin.domain.model

/** 任务要求文件里必须出现的轨道。 */
enum class ExpectedTrack { VIDEO, AUDIO }

/**
 * 完整性判定结果。
 *
 * 判定只回答「缺什么」，具体抛什么异常、删哪个文件由数据层决定；这样 JVM 用例可以穷举，
 * 而真实解码行为仍由设备用例覆盖。
 */
sealed interface MediaIntegrity {
    /** 轨道齐全，且声明时长接近结尾处有有效样本。 */
    data object Complete : MediaIntegrity

    /** 缺少任务要求的轨道（分轨合并后少音轨是最常见的形态）。 */
    data class MissingTracks(val missing: Set<ExpectedTrack>) : MediaIntegrity

    /** 声明了时长 [durationUs]，但在接近结尾处找不到有效样本：文件被提前截断。 */
    data class Truncated(val durationUs: Long) : MediaIntegrity

    /**
     * 容器没有声明时长（`KEY_DURATION` 缺失或为 0）。
     *
     * 这类文件无法做尾部检查，只能保留「容器可打开 + 首样本存在」的原有校验；
     * 直播、未知时长流属于这一档，明确按未覆盖边界处理，而不是假装校验过。
     */
    data object UnknownDuration : MediaIntegrity
}

/**
 * I2：视频 / 音频的轨道与尾部完整性策略。
 *
 * 纯 Kotlin：不打开文件、不调用 MediaExtractor。数据层把「实际存在的轨道」和
 * 「接近结尾处能读到的最后一个样本时间戳」交给这里，结论只由 [MediaIntegrityPolicy.evaluate] 给出。
 */
object MediaIntegrityPolicy {
    /** 尾部检查从声明时长的 90% 处开始。 */
    const val TAIL_FRACTION = 0.9

    /**
     * 尾部最多连续读多少个样本。
     *
     * 关键帧间隔较大时（例如 10 秒一个 I 帧），`seekTo(previous-sync)` 仍会落在很靠前的位置，
     * 需要向后推进到目标时间点；这个上限保证检查始终有界，不做逐帧完整解码。
     */
    const val MAX_TAIL_SAMPLES = 512

    /**
     * 这个任务必须包含哪些轨道。
     *
     * - 音频任务只要音轨（没有视频是正常的）。
     * - 明确请求画面 + 声音的组合（`+` 选择器、单独音频 URL / 格式、`SEPARATE` 类型）要求两条都有。
     * - 单一格式只要求视频轨：它可能本来就是纯视频流，强行要求音轨会把合法结果判成失败。
     */
    fun expectedTracks(media: MediaDownload?): Set<ExpectedTrack> = when {
        media == null -> setOf(ExpectedTrack.VIDEO)
        media.audioOnly -> setOf(ExpectedTrack.AUDIO)
        media.audioUrl != null ||
            !media.audioFormatId.isNullOrBlank() ||
            media.type == MediaSourceType.SEPARATE ||
            media.formatSelector.contains('+') -> setOf(ExpectedTrack.VIDEO, ExpectedTrack.AUDIO)
        else -> setOf(ExpectedTrack.VIDEO)
    }

    /** 尾部目标时间点：声明时长的 90%。 */
    fun tailThresholdUs(durationUs: Long): Long =
        if (durationUs <= 0L) 0L else durationUs / 10L * 9L

    /**
     * 综合判定。
     *
     * [tailSampleUs] 是数据层从尾部目标点向后推进时读到的**最后一个**有效样本时间戳；
     * 一直没读到有效样本时为 null。轨道齐全但尾部读不到，按截断处理。
     */
    fun evaluate(
        expected: Set<ExpectedTrack>,
        present: Set<ExpectedTrack>,
        durationUs: Long,
        tailSampleUs: Long?,
    ): MediaIntegrity {
        val missing = expected - present
        if (missing.isNotEmpty()) return MediaIntegrity.MissingTracks(missing)
        if (durationUs <= 0L) return MediaIntegrity.UnknownDuration
        if (tailSampleUs == null) return MediaIntegrity.Truncated(durationUs)
        return if (tailSampleUs >= tailThresholdUs(durationUs)) MediaIntegrity.Complete
        else MediaIntegrity.Truncated(durationUs)
    }
}
