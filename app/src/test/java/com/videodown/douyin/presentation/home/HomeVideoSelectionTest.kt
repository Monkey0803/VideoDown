package com.videodown.douyin.presentation.home

import androidx.lifecycle.ViewModelStore
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.core.AppError
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
class HomeVideoSelectionTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val first = DouyinVideo("post_one", "第一个", variants = listOf(VideoVariant("https://video.twimg.com/one.mp4", "1080P", sizeBytes = 100)), platform = VideoPlatform.X)
    private val second = DouyinVideo("post_two", "第二个", variants = listOf(VideoVariant("https://video.twimg.com/two.mp4", "360P", sizeBytes = 200)), platform = VideoPlatform.X)
    private val downloader = RecordingDownloader()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { store.clear(); Dispatchers.resetMain() }

    private fun viewModel(parse: suspend () -> ParsedVideos, size: suspend (VideoVariant) -> Long? = { null }): HomeViewModel {
        val repo = object : VideoRepository {
            override suspend fun parseShareText(shareText: String): AppResult<DouyinVideo> = error("Use media list")
            override suspend fun parseVideos(shareText: String) = AppResult.Success(parse())
        }
        return HomeViewModel(repo, downloader, { it.defaultVariant }, size).also { store.put("test", it) }
    }

    @Test fun `selected index and switching media update actual download input`() = runTest(dispatcher) {
        val vm = viewModel({ ParsedVideos(listOf(first, second), 1) })
        vm.parseSharedText("test"); runCurrent()
        assertEquals(second, vm.state.value.video)
        vm.selectVideo(0); vm.selectVariant(second.variants.single())
        assertEquals(first.variants.single(), vm.state.value.selectedVariant)
        vm.download(); runCurrent()
        assertEquals(first.awemeId, downloader.started!!.first.awemeId)
        assertEquals(first.variants.single().url, downloader.started!!.second.url)
        vm.selectVideo(1)
        assertNull(vm.state.value.activeDownloadId)
        assertEquals(second.variants.single(), vm.state.value.selectedVariant)
    }

    @Test fun `back cancels pending parse and cannot restore stale result`() = runTest(dispatcher) {
        val response = CompletableDeferred<ParsedVideos>()
        val vm = viewModel({ response.await() })
        vm.parseSharedText("test"); runCurrent(); assertTrue(vm.state.value.isParsing)
        vm.clearResult(); response.complete(ParsedVideos(listOf(first))); runCurrent()
        assertNull(vm.state.value.video)
        assertFalse(vm.state.value.isParsing)
        assertTrue(vm.state.value.videos.isEmpty())
    }

    @Test fun `reparsing same browser URL retains new request after immediate failure`() = runTest(dispatcher) {
        val url = "https://example.com/video"
        val repo = object : VideoRepository {
            override suspend fun parseShareText(shareText: String): AppResult<DouyinVideo> = error("Use media list")
            override suspend fun parseVideos(shareText: String) = AppResult.Failure(AppError.Parse("受限", DownloadFailureKind.ACCESS_DENIED))
        }
        val vm = HomeViewModel(repo, downloader, { it.defaultVariant }, { null }).also { store.put("test", it) }
        vm.requestBrowserAssistance(url)
        vm.onBrowserRequestConsumed()
        val initial = vm.state.value.parseRequestId
        repeat(2) { index ->
            vm.parseSharedText(url)
            runCurrent()
            assertFalse(vm.state.value.isParsing)
            assertEquals(url, vm.state.value.input)
            assertEquals(initial + index + 1, vm.state.value.parseRequestId)
            assertEquals(DownloadFailureKind.ACCESS_DENIED, vm.state.value.errorKind)
            assertNull(vm.state.value.browserRequestUrl)
        }
        val latest = vm.state.value.parseRequestId
        vm.requestBrowserAssistance(url)
        assertEquals(url, vm.state.value.browserRequestUrl)
        assertEquals(latest, vm.state.value.parseRequestId)
    }

    @Test fun `late size probe for previous media never changes selected media`() = runTest(dispatcher) {
        val unknown = first.copy(variants = first.variants.map { it.copy(sizeBytes = null) })
        val response = CompletableDeferred<Long>()
        val vm = viewModel({ ParsedVideos(listOf(unknown, second)) }, { response.await() })
        vm.parseSharedText("test"); runCurrent()
        vm.selectVideo(1); response.complete(9999); runCurrent()
        assertEquals(second, vm.state.value.video)
        assertEquals(200L, vm.state.value.selectedVariant?.sizeBytes)
        assertFalse(vm.state.value.isLoadingSizes)
    }

    private class RecordingDownloader : Downloader {
        var started: Pair<DouyinVideo, VideoVariant>? = null
        override val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
        override suspend fun start(video: DouyinVideo, variant: VideoVariant): String { started = video to variant; return "task" }
        override suspend fun pause(id: String) = Unit
        override suspend fun resume(id: String) = Unit
        override suspend fun cancel(id: String, removeRecord: Boolean) = Unit
        override suspend fun resumeAll() = Unit
        override suspend fun pauseAll() = Unit
        override suspend fun restart(id: String) = Unit
        override fun observeRecord(id: String) = flowOf<DownloadRecord?>(null)
    }
}
