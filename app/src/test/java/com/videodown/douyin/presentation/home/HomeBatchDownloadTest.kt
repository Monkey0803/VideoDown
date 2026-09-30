package com.videodown.douyin.presentation.home

import androidx.lifecycle.ViewModelStore
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.domain.repository.VideoRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeBatchDownloadTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val first = DouyinVideo("one", "第一段", platform = VideoPlatform.X, variants = listOf(
        VideoVariant("https://cdn/one-high", "1080P", sizeBytes = 100), VideoVariant("https://cdn/one-low", "360P", sizeBytes = 50)))
    private val second = first.copy(awemeId = "two", title = "第二段")
    private val downloader = RecordingDownloader()
    private lateinit var vm: HomeViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val repo = object : VideoRepository {
            override suspend fun parseShareText(shareText: String): AppResult<DouyinVideo> = error("Unexpected")
            override suspend fun parseVideos(shareText: String) = AppResult.Success(ParsedVideos(listOf(first, second)))
        }
        vm = HomeViewModel(repo, downloader, { it.defaultVariant }, { null })
        store.put("home", vm)
        vm.acceptBrowserResult(ParsedVideos(listOf(first, second)))
    }
    @After fun close() { store.clear(); Dispatchers.resetMain() }

    @Test fun `each selected media retains its chosen quality and only checked items enqueue`() = runTest(dispatcher) {
        vm.selectVariant(first.variants[1]); vm.selectVideo(1); vm.selectVariant(second.variants[0]); vm.selectVideo(0)
        assertEquals("360P", vm.state.value.selectedVariant?.label)
        vm.toggleMedia(first.selectionKey()); vm.toggleMedia(second.selectionKey()); vm.downloadSelected(); runCurrent()
        assertEquals(listOf("one" to "360P", "two" to "1080P"), downloader.started)
        assertEquals(2, vm.state.value.queuedCount)
    }
    @Test fun `single duplicate waits for explicit action and cancel starts nothing`() = runTest(dispatcher) {
        downloader.duplicates[first.selectionKey()] = record(first)
        vm.download(); runCurrent()
        assertNotNull(vm.state.value.duplicates); assertTrue(downloader.started.isEmpty())
        vm.dismissDuplicates(); runCurrent(); assertTrue(downloader.started.isEmpty())
    }
    @Test fun `batch skip downloads nonduplicate selections only`() = runTest(dispatcher) {
        downloader.duplicates[first.selectionKey()] = record(first)
        vm.selectAllMedia(); vm.downloadSelected(); runCurrent()
        vm.confirmDuplicates(false); runCurrent()
        assertEquals(listOf("two" to "1080P"), downloader.started)
    }
    @Test fun `force download includes duplicate items and double taps do not enqueue twice`() = runTest(dispatcher) {
        downloader.duplicates[first.selectionKey()] = record(first)
        vm.selectAllMedia(); vm.downloadSelected(); vm.downloadSelected(); runCurrent()
        vm.confirmDuplicates(true); vm.confirmDuplicates(true); runCurrent()
        assertEquals(listOf("one" to "1080P", "two" to "1080P"), downloader.started)
    }
    @Test fun `removed duplicate is rechecked before skipping`() = runTest(dispatcher) {
        downloader.duplicates[first.selectionKey()] = record(first)
        vm.download(); runCurrent(); downloader.duplicates.clear()
        vm.confirmDuplicates(false); runCurrent()
        assertEquals(listOf("one" to "1080P"), downloader.started)
    }
    @Test fun `changing quality clears old active task and remembers new selection`() = runTest(dispatcher) {
        vm.download(); runCurrent(); assertNotNull(vm.state.value.activeDownloadId)
        vm.selectVariant(first.variants[1]); assertNull(vm.state.value.activeDownloadId)
        vm.download(); runCurrent()
        assertEquals(listOf("one" to "1080P", "one" to "360P"), downloader.started)
    }
    @Test fun `empty selection does not enqueue and clear removes pending duplicate prompt`() = runTest(dispatcher) {
        assertTrue(vm.state.value.selectedMedia.isEmpty()); vm.downloadSelected(); runCurrent(); assertTrue(downloader.started.isEmpty())
        downloader.duplicates[first.selectionKey()] = record(first)
        vm.download(); runCurrent(); vm.clearResult(); runCurrent()
        assertNull(vm.state.value.duplicates); assertTrue(vm.state.value.selectedMedia.isEmpty())
    }
    @Test fun `partial queue failure stays on result screen with success count and error`() = runTest(dispatcher) {
        downloader.failOnVideo = "two"
        vm.selectAllMedia(); vm.downloadSelected(); runCurrent()
        assertEquals(listOf("one" to "1080P"), downloader.started)
        assertNull(vm.state.value.queuedCount)
        assertNotNull(vm.state.value.errorMessage)
        assertEquals("已加入 1 个下载任务，其余未加入，请重试", vm.state.value.infoMessage)
        assertFalse(vm.state.value.isSubmitting)
    }
    private fun record(video: DouyinVideo) = DownloadRecord("existing", video.awemeId, video.title,
        platform = video.platform, resolutionLabel = "1080P", status = DownloadStatus.PENDING)
    private class RecordingDownloader : Downloader {
        val started = mutableListOf<Pair<String, String>>()
        var failOnVideo: String? = null
        val duplicates = mutableMapOf<String, DownloadRecord>()
        override val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
        override suspend fun findDuplicate(video: DouyinVideo, variant: VideoVariant) = duplicates[video.selectionKey()]
        override suspend fun start(video: DouyinVideo, variant: VideoVariant): String { if (video.awemeId == failOnVideo) throw IllegalStateException("queue failure"); started += video.awemeId to variant.label; return "task-${started.size}" }
        override suspend fun pause(id: String) = Unit
        override suspend fun resume(id: String) = Unit
        override suspend fun cancel(id: String, removeRecord: Boolean) = Unit
        override suspend fun resumeAll() = Unit
        override suspend fun pauseAll() = Unit
        override suspend fun restart(id: String) = Unit
        override fun observeRecord(id: String) = flowOf<DownloadRecord?>(null)
    }
}
