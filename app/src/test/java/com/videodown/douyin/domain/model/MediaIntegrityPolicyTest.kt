package com.videodown.douyin.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I2：轨道预期与尾部完整性的判定。
 *
 * 真实解码由设备用例覆盖；这里穷举「哪些任务要求两条轨」「多晚算接近结尾」「缺一半/被截断」
 * 这些决定，避免把合法的纯视频结果误判成失败，也避免把截断文件当成功。
 */
class MediaIntegrityPolicyTest {

    private fun media(
        audioOnly: Boolean = false,
        type: MediaSourceType = MediaSourceType.DIRECT,
        formatSelector: String = "137",
        audioFormatId: String? = null,
        audioUrl: String? = null,
    ) = MediaDownload(
        type = type,
        pageUrl = "https://example.test/post",
        mediaId = "media",
        formatId = formatSelector,
        audioFormatId = audioFormatId,
        audioUrl = audioUrl,
        audioOnly = audioOnly,
    )

    @Test fun `an audio task only needs an audio track`() {
        assertEquals(setOf(ExpectedTrack.AUDIO), MediaIntegrityPolicy.expectedTracks(media(audioOnly = true)))
    }

    @Test fun `a single format only requires the video track`() {
        assertEquals(setOf(ExpectedTrack.VIDEO), MediaIntegrityPolicy.expectedTracks(media(formatSelector = "137")))
        assertEquals("direct links have no request descriptor", setOf(ExpectedTrack.VIDEO), MediaIntegrityPolicy.expectedTracks(null))
    }

    @Test fun `an explicit video plus audio selection requires both tracks`() {
        assertEquals(setOf(ExpectedTrack.VIDEO, ExpectedTrack.AUDIO),
            MediaIntegrityPolicy.expectedTracks(media(formatSelector = "137+140")))
        assertEquals(setOf(ExpectedTrack.VIDEO, ExpectedTrack.AUDIO),
            MediaIntegrityPolicy.expectedTracks(media(audioFormatId = "140")))
        assertEquals(setOf(ExpectedTrack.VIDEO, ExpectedTrack.AUDIO),
            MediaIntegrityPolicy.expectedTracks(media(audioUrl = "https://example.test/a.m4a")))
        assertEquals(setOf(ExpectedTrack.VIDEO, ExpectedTrack.AUDIO),
            MediaIntegrityPolicy.expectedTracks(media(type = MediaSourceType.SEPARATE)))
    }

    @Test fun `the tail threshold sits at ninety percent of the declared duration`() {
        assertEquals(0L, MediaIntegrityPolicy.tailThresholdUs(0L))
        assertEquals(9_000_000L, MediaIntegrityPolicy.tailThresholdUs(10_000_000L))
        assertEquals(450_000L, MediaIntegrityPolicy.tailThresholdUs(500_000L))
    }

    @Test fun `a complete file needs every expected track and a tail sample`() {
        val complete = MediaIntegrityPolicy.evaluate(
            expected = setOf(ExpectedTrack.VIDEO, ExpectedTrack.AUDIO),
            present = setOf(ExpectedTrack.VIDEO, ExpectedTrack.AUDIO),
            durationUs = 10_000_000L,
            tailSampleUs = 9_200_000L,
        )
        assertEquals(MediaIntegrity.Complete, complete)
        assertEquals("exactly at the threshold still counts", MediaIntegrity.Complete,
            MediaIntegrityPolicy.evaluate(setOf(ExpectedTrack.VIDEO), setOf(ExpectedTrack.VIDEO), 10_000_000L, 9_000_000L))
    }

    @Test fun `a merged file without its audio track reports the missing track`() {
        val verdict = MediaIntegrityPolicy.evaluate(
            expected = setOf(ExpectedTrack.VIDEO, ExpectedTrack.AUDIO),
            present = setOf(ExpectedTrack.VIDEO),
            durationUs = 10_000_000L,
            tailSampleUs = 9_500_000L,
        )
        assertTrue(verdict is MediaIntegrity.MissingTracks)
        assertEquals(setOf(ExpectedTrack.AUDIO), (verdict as MediaIntegrity.MissingTracks).missing)
    }

    @Test fun `no tail sample means the file was cut short`() {
        assertEquals(MediaIntegrity.Truncated(10_000_000L),
            MediaIntegrityPolicy.evaluate(setOf(ExpectedTrack.VIDEO), setOf(ExpectedTrack.VIDEO), 10_000_000L, null))
        assertEquals(MediaIntegrity.Truncated(10_000_000L),
            MediaIntegrityPolicy.evaluate(setOf(ExpectedTrack.VIDEO), setOf(ExpectedTrack.VIDEO), 10_000_000L, 4_000_000L))
    }

    @Test fun `a container without a declared duration is reported as unknown instead of truncated`() {
        assertEquals(MediaIntegrity.UnknownDuration,
            MediaIntegrityPolicy.evaluate(setOf(ExpectedTrack.AUDIO), setOf(ExpectedTrack.AUDIO), 0L, null))
    }
}
