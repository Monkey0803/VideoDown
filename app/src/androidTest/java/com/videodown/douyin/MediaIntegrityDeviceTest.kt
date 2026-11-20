package com.videodown.douyin

import android.media.MediaExtractor
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.data.download.Mp4Structure
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


    /**
     * 平台解析器拒绝、但容器本身与播放都正常的文件。
     *
     * `ctts-flags-fixture.mp4` 是 `faststart-fixture.mp4` 把 `ctts` 的 flags 改成 1（该字段应为 0）
     * 得到的——X 的动图 MP4 就是这个形态：MPEG4 解析器整条轨道放弃（`trackCount` 为 0，
     * 文件级 DRM 摘要直接抛 `IllegalArgumentException`），字节却能被 ExoPlayer 正常播放。
     */
    @Test fun aContainerThePlatformExtractorRefusesFallsBackToItsStructure() {
        val file = fixture("ctts-flags-fixture.mp4")
        assertEquals("平台解析器必须仍然读不出轨道", 0, platformTrackCount(file))

        // 兜底判定：结构完整 + 有视频轨道 -> 通过。
        assertEquals(Mp4Structure.Complete(setOf(ExpectedTrack.VIDEO)), Mp4Structure.inspect(file))
        validateMediaFile(file, setOf(ExpectedTrack.VIDEO))
        // 任务要求音轨时仍然必须失败：容器里只有一条视频轨。
        assertEquals("MISSING_TRACK", failureOf { validateMediaFile(file, setOf(ExpectedTrack.VIDEO, ExpectedTrack.AUDIO)) }.kind)
    }

    @Test fun theRefusedContainerStillPlaysInTheAppsOwnPlayer() {
        val file = fixture("ctts-flags-fixture.mp4")
        var player: ExoPlayer? = null
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
            }
        }
        val active = player ?: error("player was not created")
        try {
            val deadline = System.currentTimeMillis() + PLAYBACK_TIMEOUT_MS
            var state = Player.STATE_IDLE
            var durationMs = 0L
            while (System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    state = active.playbackState
                    durationMs = active.duration
                }
                if (state == Player.STATE_READY) break
                if (state == Player.STATE_ENDED) error("player ended before becoming ready")
                Thread.sleep(200)
            }
            assertEquals("the app's own player must be able to prepare the file", Player.STATE_READY, state)
            assertTrue("the player must read a positive duration, got $durationMs", durationMs > 0L)
        } finally {
            instrumentation.runOnMainSync { active.release() }
        }
    }

    @Test fun aTruncatedContainerThePlatformRefusesIsStillRejected() {
        val error = failureOf {
            validateMediaFile(truncated("ctts-flags-fixture.mp4", 0.6), setOf(ExpectedTrack.VIDEO))
        }
        assertEquals("TRUNCATED", error.kind)
    }

    private fun platformTrackCount(file: File): Int {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            extractor.trackCount
        } finally {
            extractor.release()
        }
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

    private companion object {
        const val PLAYBACK_TIMEOUT_MS = 15_000L
    }
}
