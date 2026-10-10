package com.videodown.douyin.presentation.home

import androidx.lifecycle.ViewModelStore
import com.videodown.douyin.core.*
import com.videodown.douyin.data.remote.VideoLinkExtractor
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class BatchImportTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val calls = mutableListOf<String>()
    private val starts = mutableListOf<Pair<String, String>>()
    private var failParse = true
    private var failQueue = false
    private var duplicate = false
    private var suspended = false
    private val low = VideoVariant("https://cdn.test/low.mp4", "360P")
    private val high = VideoVariant("https://cdn.test/high.mp4", "1080P")
    private fun video(id: String) = DouyinVideo(id, id, variants = listOf(low, high), platform = VideoPlatform.GENERIC)
    private val repository = object : VideoRepository {
        override suspend fun parseShareText(shareText: String) = AppResult.Success(video("unused"))
        override suspend fun parseVideos(shareText: String): AppResult<ParsedVideos> {
            calls += shareText
            if (suspended && shareText.contains("wait")) awaitCancellation()
            if (failParse && shareText.contains("bad")) return AppResult.Failure(AppError.Network("连接失败"))
            return AppResult.Success(ParsedVideos(listOf(video(shareText.substringAfterLast('/')), video("second"))))
        }
    }
    private val downloader = object : Downloader {
        override val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
        override suspend fun findDuplicate(video: DouyinVideo, variant: VideoVariant) = if (duplicate && video.awemeId == "ok") DownloadRecord("old", "ok", "old") else null
        override suspend fun start(video: DouyinVideo, variant: VideoVariant, subtitles: SubtitleSelection): String {
            if (failQueue && video.awemeId == "second") error("failed")
            starts += video.awemeId to variant.label; return video.awemeId
        }
        override suspend fun pause(id: String) {}
        override suspend fun resume(id: String) {}
        override suspend fun cancel(id: String, removeRecord: Boolean) {}
        override suspend fun resumeAll() {}
        override suspend fun pauseAll() {}
        override suspend fun restart(id: String) {}
        override fun observeRecord(id: String) = MutableStateFlow<DownloadRecord?>(null)
    }
    private lateinit var vm: BatchImportViewModel
    @Before fun setup() { Dispatchers.setMain(dispatcher); vm = BatchImportViewModel(repository, downloader, { it.variants.first() }); store.put("batch", vm) }
    @After fun close() { store.clear(); Dispatchers.resetMain() }
    @Test fun `extracts distinct URLs and excludes embedded credentials`() {
        assertEquals(listOf("https://example.test/a", "https://example.test/b"),
            VideoLinkExtractor.extractAll("分享 https://example.test/a， https://example.test/a https://user:secret@example.test/private https://example.test/b。").map { it.url })
    }
    @Test fun `partial parsing preserves successful multi media choices when retrying failed URL`() = runTest(dispatcher) {
        vm.input("https://example.test/ok https://example.test/bad https://example.test/ok")
        vm.prepareAndParse(); advanceUntilIdle()
        assertEquals(2, calls.size); assertEquals(ImportStatus.READY, vm.state.value.rows[0].status)
        val key = vm.state.value.rows[0].media.first().key
        vm.choose(key, high); vm.select(key, false)
        failParse = false; vm.parseRemaining(); advanceUntilIdle()
        assertEquals(3, calls.size); assertTrue(calls.last().contains("bad"))
        assertEquals(high, vm.state.value.rows[0].media.first().choice)
        assertFalse(vm.state.value.rows[0].media.first().selected)
        assertTrue(vm.state.value.rows.all { it.status == ImportStatus.READY })
    }
    @Test fun `stop leaves current URL pending and continues without reparsing ready rows`() = runTest(dispatcher) {
        suspended = true; vm.input("https://example.test/ok https://example.test/wait")
        vm.prepareAndParse(); runCurrent(); assertTrue(vm.state.value.parsing)
        vm.stop(); advanceUntilIdle()
        assertEquals(ImportStatus.READY, vm.state.value.rows.first().status)
        assertEquals(ImportStatus.PENDING, vm.state.value.rows.last().status)
        suspended = false; vm.parseRemaining(); advanceUntilIdle()
        assertEquals(listOf("https://example.test/ok", "https://example.test/wait", "https://example.test/wait"), calls)
    }
    @Test fun `queue failures keep only failed media selected and retry never requeues successes`() = runTest(dispatcher) {
        vm.input("https://example.test/ok"); vm.prepareAndParse(); advanceUntilIdle()
        val key = vm.state.value.rows.first().media.first().key; vm.choose(key, high)
        failQueue = true; vm.submit(); advanceUntilIdle()
        assertEquals(listOf("ok" to "1080P"), starts); assertEquals(1, vm.state.value.selectedCount)
        failQueue = false; vm.submit(); advanceUntilIdle()
        assertEquals(listOf("ok" to "1080P", "second" to "360P"), starts)
        assertEquals(0, vm.state.value.selectedCount)
    }
    @Test fun `duplicate confirmation rechecks records removed while dialog open`() = runTest(dispatcher) {
        vm.input("https://example.test/ok"); vm.prepareAndParse(); advanceUntilIdle()
        duplicate = true; vm.submit(); advanceUntilIdle()
        assertEquals(1, vm.state.value.duplicateCount); assertTrue(starts.isEmpty())
        duplicate = false; vm.submit(false); advanceUntilIdle()
        assertEquals(2, starts.size); assertEquals(0, vm.state.value.selectedCount)
    }
    @Test fun `duplicate skip and explicit again honor permedia choices`() = runTest(dispatcher) {
        vm.input("https://example.test/ok"); vm.prepareAndParse(); advanceUntilIdle()
        duplicate = true; vm.submit(); advanceUntilIdle(); vm.submit(false); advanceUntilIdle()
        assertEquals(listOf("second" to "360P"), starts)
        vm.selectAll(true); vm.submit(); advanceUntilIdle(); vm.submit(true); advanceUntilIdle()
        assertEquals(3, starts.size)
    }
    @Test fun `over limit never silently truncates or starts requests`() = runTest(dispatcher) {
        vm.input((1..51).joinToString(" ") { "https://example.test/$it" }); vm.prepareAndParse(); advanceUntilIdle()
        assertTrue(calls.isEmpty()); assertTrue(vm.state.value.rows.isEmpty())
        // The state reports which notice applies; the dialog words it.
        assertEquals(ImportNotice.TooManyLinks, vm.state.value.notice)
    }
}
