package com.videodown.douyin.data.download

import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.ArchiveEntry
import com.videodown.douyin.domain.model.ArchiveRequest
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.LivePhotoMotion
import com.videodown.douyin.domain.model.MediaKind
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.domain.model.VideoPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class LivePhotoDownloadTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `still and mov complete as one record and cancel removes both`() = runBlocking {
        val stillBytes = ByteArray(4096) { (it % 127).toByte() }
        val motionBytes = ByteArray(8192) { (it % 251).toByte() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            RangeAwareTestServer(stillBytes).use { stillServer ->
                RangeAwareTestServer(motionBytes).use { motionServer ->
                    val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(temp.root, "history.json")))
                    val engine = DownloadEngine(
                        history,
                        ResumableDownloader(OkHttpClient()),
                        DownloadPaths(temp.root),
                        scope,
                        "",
                        "test",
                    )
                    val variant = VideoVariant(
                        url = "http://127.0.0.1:${stillServer.port}/still.jpg",
                        label = "原图",
                        kind = MediaKind.IMAGE,
                        livePhotoMotion = LivePhotoMotion(
                            sourceUrl = "http://127.0.0.1:${motionServer.port}/motion.mov",
                        ),
                    )
                    val id = engine.start(DouyinVideo("live-photo", "live-photo", variants = listOf(variant)), variant)
                    val record = withTimeout(5_000) {
                        history.observeRecord(id).first { it?.status == DownloadStatus.COMPLETED }!!
                    }
                    val still = File(record.filePath!!)
                    val motion = File(requireNotNull(record.livePhotoMotion?.filePath))
                    assertTrue(record.isLivePhoto)
                    assertArrayEquals(stillBytes, still.readBytes())
                    assertArrayEquals(motionBytes, motion.readBytes())

                    engine.cancel(id)
                    assertFalse(still.exists())
                    assertFalse(motion.exists())
                    assertTrue(history.getById(id) == null)
                }
            }
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @Test fun `archive keeps matching still and motion entries`() = runBlocking {
        val stillBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) +
            ByteArray(1019) { 1 } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val motionBytes = byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(),
            'y'.code.toByte(), 'p'.code.toByte(), 'q'.code.toByte(), 't'.code.toByte(),
            ' '.code.toByte(), ' '.code.toByte()) + ByteArray(2036) { 2 }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            RangeAwareTestServer(stillBytes).use { stillServer ->
                RangeAwareTestServer(motionBytes).use { motionServer ->
                    val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(temp.root, "archive-history.json")))
                    val engine = DownloadEngine(
                        history,
                        ResumableDownloader(OkHttpClient()),
                        DownloadPaths(temp.root),
                        scope,
                        "",
                        "test",
                    )
                    val id = engine.startArchive(ArchiveRequest(
                        title = "live-photo-archive",
                        postId = "post",
                        platform = VideoPlatform.WEIBO,
                        postPageUrl = "https://weibo.com/1/post",
                        entries = listOf(ArchiveEntry(
                            url = "http://127.0.0.1:${stillServer.port}/still.jpg",
                            extension = "jpg",
                            livePhotoMotion = LivePhotoMotion(
                                sourceUrl = "http://127.0.0.1:${motionServer.port}/motion.mov",
                            ),
                        )),
                    ))
                    val record = withTimeout(5_000) {
                        history.observeRecord(id).first { it?.status == DownloadStatus.COMPLETED }!!
                    }
                    ZipFile(File(record.filePath!!)).use { zip ->
                        assertTrue(zip.getEntry("images/001.jpg") != null)
                        assertTrue(zip.getEntry("livephotos/001.mov") != null)
                        assertArrayEquals(stillBytes, zip.getInputStream(zip.getEntry("images/001.jpg")).readBytes())
                        assertArrayEquals(motionBytes, zip.getInputStream(zip.getEntry("livephotos/001.mov")).readBytes())
                    }
                }
            }
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }
}
