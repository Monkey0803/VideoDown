package com.videodown.douyin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.data.generic.VideoEngineException
import com.videodown.douyin.data.generic.validateMediaFile
import com.videodown.douyin.domain.model.ExpectedTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * I2：真实的轨道与尾部完整性校验。
 *
 * - 完整素材必须通过，避免把合法结果误判成失败（尤其是纯视频文件不能被要求带音轨）；
 * - 分轨合并少了音轨要报 `MISSING_TRACK`；
 * - 截断的 MP4 / WebM / 音频必须失败：`faststart-fixture.mp4` 的 `moov` 在文件头，
 *   截掉尾部后容器仍能打开、声明时长不变，只有近结尾的样本消失，正好覆盖「服务器提前断流」。
 */
@RunWith(AndroidJUnit4::class)
class MediaIntegrityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun fixture(name: String): File {
        val target = File(context.cacheDir, "integrity-$name")
        instrumentation.context.assets.open(name).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private fun truncated(name: String, keep: Double): File {
        val source = fixture(name)
        val bytes = source.readBytes()
        return File(context.cacheDir, "truncated-${(keep * 100).toInt()}-$name").apply {
            writeBytes(bytes.copyOf((bytes.size * keep).toInt()))
        }
    }

    private fun failureOf(block: () -> Unit): VideoEngineException {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("expected a media failure, got $error", error is VideoEngineException)
        return error as VideoEngineException
    }

    @Test fun completeFixturesPassWithTheTracksTheTaskAskedFor() {
        // 60 秒无声视频：只要求视频轨。
        validateMediaFile(fixture("faststart-fixture.mp4"), setOf(ExpectedTrack.VIDEO))
        // 视频 + AAC：分轨合并后的典型结果。
        validateMediaFile(fixture("aac-video-fixture.mp4"), setOf(ExpectedTrack.VIDEO, ExpectedTrack.AUDIO))
        // WebM 的 VP8 + Opus。
        validateMediaFile(fixture("opus-video-fixture.webm"), setOf(ExpectedTrack.VIDEO, ExpectedTrack.AUDIO))
        // 音频任务只要求音轨。
        validateMediaFile(fixture("audio-fixture.m4a"), setOf(ExpectedTrack.AUDIO))
    }

    @Test fun aMergedResultWithoutItsAudioTrackIsRejected() {
        // landscape 素材本身就是无声的：把它当成「合并后应该两条轨」的结果必须失败。
        val error = failureOf {
            validateMediaFile(fixture("faststart-fixture.mp4"), setOf(ExpectedTrack.VIDEO, ExpectedTrack.AUDIO))
        }
        assertEquals("MISSING_TRACK", error.kind)

        val audioOnly = failureOf {
            validateMediaFile(fixture("audio-fixture.m4a"), setOf(ExpectedTrack.VIDEO))
        }
        assertEquals("MISSING_TRACK", audioOnly.kind)
    }

    @Test fun aTruncatedMp4NearTheEndIsRejected() {
        val error = failureOf {
            validateMediaFile(truncated("faststart-fixture.mp4", 0.6), setOf(ExpectedTrack.VIDEO))
        }
        assertEquals("TRUNCATED", error.kind)
    }

    @Test fun aTruncatedWebmIsRejected() {
        val error = runCatching {
            validateMediaFile(truncated("opus-video-fixture.webm", 0.6), setOf(ExpectedTrack.VIDEO, ExpectedTrack.AUDIO))
        }.exceptionOrNull()
        assertTrue("truncated WebM must never pass validation, got $error", error != null)
    }

    @Test fun aTruncatedAudioFileIsRejected() {
        val error = runCatching {
            validateMediaFile(truncated("audio-fixture.m4a", 0.6), setOf(ExpectedTrack.AUDIO))
        }.exceptionOrNull()
        assertTrue("truncated audio must never pass validation, got $error", error != null)
    }
}
