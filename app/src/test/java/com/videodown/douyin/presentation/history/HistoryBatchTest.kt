package com.videodown.douyin.presentation.history

import androidx.lifecycle.ViewModelStore
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.repository.Downloader
import kotlinx.coroutines.*
import com.videodown.douyin.domain.repository.DownloadHistoryRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryBatchTest {
    private val dispatcher = StandardTestDispatcher()
    private val history = object : DownloadHistoryRepository {
        private val records = MutableStateFlow<List<DownloadRecord>>(emptyList())
        override fun observeAll() = records
        override fun observeRecord(id: String) = records.map { list -> list.firstOrNull { it.id == id } }
        override suspend fun snapshot() = records.value
        override suspend fun getById(id: String) = records.value.firstOrNull { it.id == id }
        override suspend fun upsert(record: DownloadRecord) { records.value = records.value.filterNot { it.id == record.id } + record }
        override suspend fun delete(id: String) { records.value = records.value.filterNot { it.id == id } }
        override suspend fun clear(deleteFiles: Boolean) { records.value = emptyList() }
        override suspend fun recoverInterruptedDownloads() = Unit
    }
    private val store = ViewModelStore()
    private val paused = mutableListOf<String>()
    private val resumed = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private val exported = mutableListOf<String>()
    private val downloader = object : Downloader {
        override val progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
        override suspend fun start(video: DouyinVideo, variant: VideoVariant) = error("Unexpected")
        override suspend fun pause(id: String) { paused += id }
        override suspend fun resume(id: String) { resumed += id }
        override suspend fun cancel(id: String, removeRecord: Boolean) { deleted += id; history.delete(id) }
        override suspend fun resumeAll() = Unit
        override suspend fun pauseAll() = Unit
        override suspend fun restart(id: String) = Unit
        override fun observeRecord(id: String) = history.observeRecord(id)
    }
    private lateinit var vm: HistoryViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        vm = HistoryViewModel(history, downloader) { record ->
            exported += record.id
            if (record.id == "bad") Result.failure(IllegalArgumentException("本地视频文件不存在")) else Result.success(Unit)
        }
        store.put("history", vm)
    }
    @After fun close() { store.clear(); Dispatchers.resetMain() }
    private suspend fun add(id: String, status: DownloadStatus) { history.upsert(DownloadRecord(id, id, id, status = status)) }
    @Test fun `selected controls use current live status and do not touch unselected items`() = runTest(dispatcher) {
        add("running", DownloadStatus.RUNNING); add("paused", DownloadStatus.PAUSED); add("other", DownloadStatus.RUNNING); runCurrent()
        vm.pauseSelected(setOf("running", "paused")); vm.pauseSelected(setOf("running")); advanceUntilIdle()
        assertEquals(listOf("running"), paused)
        vm.resumeSelected(setOf("running", "paused")); advanceUntilIdle()
        assertEquals(listOf("paused"), resumed)
    }
    @Test fun `live progress status wins over persisted status during bulk control`() = runTest(dispatcher) {
        add("a", DownloadStatus.RUNNING); add("b", DownloadStatus.PAUSED)
        downloader.progress.value = mapOf("a" to DownloadProgress("a", status = DownloadStatus.PAUSED),
            "b" to DownloadProgress("b", status = DownloadStatus.RUNNING))
        runCurrent()
        assertEquals(1, vm.state.value.activeCount)
        vm.pauseSelected(setOf("a", "b")); advanceUntilIdle(); assertEquals(listOf("b"), paused)
        vm.resumeSelected(setOf("a", "b")); advanceUntilIdle(); assertEquals(listOf("a"), resumed)
    }
    @Test fun `bulk resume skips typed and legacy unsupported failures while resuming network failure`() = runTest(dispatcher) {
        history.upsert(DownloadRecord("drm", "drm", "drm", status = DownloadStatus.FAILED, failureKind = DownloadFailureKind.UNSUPPORTED))
        history.upsert(DownloadRecord("live", "live", "live", status = DownloadStatus.FAILED, errorMessage = "暂不支持直播"))
        history.upsert(DownloadRecord("network", "network", "network", status = DownloadStatus.FAILED, failureKind = DownloadFailureKind.NETWORK))
        runCurrent()
        vm.resumeSelected(linkedSetOf("drm", "live", "network")); advanceUntilIdle()
        assertEquals(listOf("network"), resumed)
        assertEquals(1, vm.state.value.batchResult?.succeeded)
        assertEquals(2, vm.state.value.batchResult?.skipped)
    }
    @Test fun `batch deletion keeps records outside captured selection`() = runTest(dispatcher) {
        add("a", DownloadStatus.COMPLETED); add("b", DownloadStatus.PAUSED); add("other", DownloadStatus.COMPLETED); runCurrent()
        vm.deleteSelected(setOf("a", "b")); advanceUntilIdle()
        assertEquals(listOf("a", "b"), deleted)
        assertEquals(listOf("other"), history.snapshot().map { it.id })
        assertFalse(vm.state.value.batchBusy)
    }
    @Test fun `gallery failure does not stop remaining items and reports skipped stale selection`() = runTest(dispatcher) {
        add("bad", DownloadStatus.COMPLETED); add("ok", DownloadStatus.COMPLETED); add("paused", DownloadStatus.PAUSED); runCurrent()
        vm.exportSelected(linkedSetOf("bad", "missing", "paused", "ok")); advanceUntilIdle()
        assertEquals(listOf("bad", "ok"), exported)
        assertEquals("保存到相册：成功 1 项，跳过 2 项，失败 1 项（本地视频文件不存在）", vm.state.value.message)
        assertEquals(listOf(BatchFailure("bad", "bad", "本地视频文件不存在")), vm.state.value.batchResult?.failures)
        assertEquals(1, vm.state.value.batchResult?.succeeded)
        assertEquals(4, vm.state.value.batchDone)
        assertFalse(vm.state.value.batchBusy)
    }
}
