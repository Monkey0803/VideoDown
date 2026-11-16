package com.videodown.douyin.presentation.home

import androidx.lifecycle.ViewModelStore
import com.videodown.douyin.core.AppResult
import com.videodown.douyin.domain.model.ArchiveRequest
import com.videodown.douyin.domain.model.DownloadProgress
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.ParsedVideos
import com.videodown.douyin.domain.model.SubtitleSelection
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.domain.model.VideoVariant
import com.videodown.douyin.domain.model.selectionKey
import com.videodown.douyin.domain.repository.Downloader
import com.videodown.douyin.domain.repository.VideoRepository
import com.videodown.douyin.data.generic.ImageMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeArchiveTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val downloader = ArchiveDownloader()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { store.clear(); Dispatchers.resetMain() }

    /** 真实解析器的图片条目形如 `{postId}_img{n}`；`postId` 由这个后缀反推。 */
    private fun image(index: Int): DouyinVideo {
        val base = ImageMedia.entry(
            url = "https://cdn.example/$index.jpg",
            pageUrl = "https://page.example/post",
            extension = "jpg",
            sizeBytes = 1024L,
            platform = VideoPlatform.WEIBO,
        )
        val id = "post_img$index"
        return base.copy(
            awemeId = id,
            variants = base.variants.map { variant -> variant.copy(media = variant.media?.copy(mediaId = id)) },
        )
    }

    private fun viewModel(parse: suspend () -> ParsedVideos): HomeViewModel {
        val repo = object : VideoRepository {
            override suspend fun parseShareText(shareText: String): AppResult<DouyinVideo> = error("Use media list")
            override suspend fun parseVideos(shareText: String) = AppResult.Success(parse())
        }
        return HomeViewModel(repo, downloader, { it.defaultVariant }, { null }).also { store.put("test", it) }
    }

    @Test fun `an image post offers packaging all of its images as one task`() = runTest(dispatcher) {
        val vm = viewModel { ParsedVideos(listOf(image(1), image(2), image(3))) }
        vm.parseSharedText("https://page.example/post"); runCurrent()

        assertTrue(vm.state.value.canDownloadArchive)
        vm.downloadArchive(); runCurrent()

        val request = downloader.started!!
        assertEquals(3, request.entries.size)
        assertEquals("post", request.postId)
        assertEquals(VideoPlatform.WEIBO, request.platform)
        assertEquals("https://page.example/post", request.postPageUrl)
        assertEquals(HomeInfo.ArchiveQueued, vm.state.value.info)
        assertEquals("archive-task", vm.state.value.activeDownloadId)
        assertNull(vm.state.value.error)
    }

    @Test fun `only the checked images are packaged when at least two are selected`() = runTest(dispatcher) {
        val entries = listOf(image(1), image(2), image(3))
        val vm = viewModel { ParsedVideos(entries) }
        vm.parseSharedText("post"); runCurrent()
        vm.toggleMedia(entries[0].selectionKey())
        vm.toggleMedia(entries[2].selectionKey())
        vm.downloadArchive(); runCurrent()

        assertEquals(listOf(entries[0].variants.single().url, entries[2].variants.single().url),
            downloader.started!!.entries.map { it.url })
    }

    @Test fun `a single image or a video post does not offer packaging`() = runTest(dispatcher) {
        val singleImage = viewModel { ParsedVideos(listOf(image(1))) }
        singleImage.parseSharedText("post"); runCurrent()
        assertFalse(singleImage.state.value.canDownloadArchive)

        val video = DouyinVideo("post", "视频", variants = listOf(VideoVariant("https://cdn.example/a.mp4", "720P")),
            platform = VideoPlatform.X)
        val videoVm = viewModel { ParsedVideos(listOf(video)) }
        videoVm.parseSharedText("post"); runCurrent()
        assertFalse(videoVm.state.value.canDownloadArchive)
        videoVm.downloadArchive(); runCurrent()
        assertNull(downloader.started)
    }

    @Test fun `a duplicate package asks first and only starts after confirmation`() = runTest(dispatcher) {
        val existing = DownloadRecord("old", "post", "图文", fileName = "old.zip",
            status = com.videodown.douyin.domain.model.DownloadStatus.COMPLETED, filePath = "/tmp/old.zip")
        downloader.duplicate = existing
        val vm = viewModel { ParsedVideos(listOf(image(1), image(2))) }
        vm.parseSharedText("post"); runCurrent()

        vm.downloadArchive(); runCurrent()
        assertNull("nothing may start before the user confirms", downloader.started)
        assertNotNull(vm.state.value.archiveDuplicate)

        vm.confirmArchiveDuplicate(); runCurrent()
        assertEquals(2, downloader.started!!.entries.size)
        assertNull(vm.state.value.archiveDuplicate)
        assertNull(vm.state.value.pendingArchive)
    }

    @Test fun `dismissing the duplicate dialog drops the pending request`() = runTest(dispatcher) {
        downloader.duplicate = DownloadRecord("old", "post", "图文", status = com.videodown.douyin.domain.model.DownloadStatus.PAUSED)
        val vm = viewModel { ParsedVideos(listOf(image(1), image(2))) }
        vm.parseSharedText("post"); runCurrent()
        vm.downloadArchive(); runCurrent()

        vm.dismissArchiveDuplicate()
        assertNull(vm.state.value.archiveDuplicate)
        assertNull(vm.state.value.pendingArchive)
        vm.confirmArchiveDuplicate(); runCurrent()
        assertNull(downloader.started)
    }

    private class ArchiveDownloader : Downloader {
        var started: ArchiveRequest? = null
        var duplicate: DownloadRecord? = null
        override val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
        override suspend fun start(video: DouyinVideo, variant: VideoVariant, subtitles: SubtitleSelection): String = "video-task"
        override suspend fun startArchive(request: ArchiveRequest): String { started = request; return "archive-task" }
        override suspend fun findArchiveDuplicate(request: ArchiveRequest): DownloadRecord? = duplicate
        override suspend fun pause(id: String) = Unit
        override suspend fun resume(id: String) = Unit
        override suspend fun cancel(id: String, removeRecord: Boolean) = Unit
        override suspend fun resumeAll() = Unit
        override suspend fun pauseAll() = Unit
        override suspend fun restart(id: String) = Unit
        override fun observeRecord(id: String) = flowOf<DownloadRecord?>(null)
    }
}
