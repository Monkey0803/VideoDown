package com.videodown.douyin.data.export

import androidx.lifecycle.ViewModelStore
import com.videodown.douyin.domain.repository.FileExportRepository
import com.videodown.douyin.presentation.library.FileExportViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class FileExportTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun close() { store.clear(); Dispatchers.resetMain() }
    @Test fun `collisions keep extension and never reuse an existing filename`() {
        assertEquals("video (3).mp4", availableExportName("video.mp4", setOf("video.mp4", "video (2).mp4")))
        assertEquals("audio.m4a", availableExportName("audio.m4a", emptySet()))
        assertEquals("name (2)", availableExportName("name", setOf("name")))
    }
    @Test fun `partial failure continues remaining exports and retry excludes successful IDs`() = runTest {
        val calls = mutableListOf<String>()
        var failing = true
        val repo = object : FileExportRepository {
            override suspend fun export(id: String, treeUri: String): Result<String> {
                calls += id
                return if (id == "bad" && failing) Result.failure(Exception("write failed")) else Result.success("uri/$id")
            }
        }
        val vm = FileExportViewModel(repo); store.put("exports", vm)
        vm.export(listOf("first", "bad", "last", "first"), "tree"); advanceUntilIdle()
        assertEquals(listOf("first", "bad", "last"), calls)
        assertEquals(setOf("bad"), vm.state.value.failedIds)
        assertEquals(3, vm.state.value.done); assertTrue(vm.state.value.finished)
        failing = false
        vm.export(vm.state.value.failedIds.toList(), "another"); advanceUntilIdle()
        assertEquals(listOf("first", "bad", "last", "bad"), calls)
        assertTrue(vm.state.value.failedIds.isEmpty())
    }
    @Test fun `cancellation preserves completed outputs and selects only unfinished IDs`() = runTest {
        val repo = object : FileExportRepository {
            override suspend fun export(id: String, treeUri: String): Result<String> {
                if (id == "wait") awaitCancellation()
                return Result.success("uri/$id")
            }
        }
        val vm = FileExportViewModel(repo); store.put("exports", vm)
        vm.export(listOf("first", "wait", "last"), "tree"); runCurrent()
        assertEquals(1, vm.state.value.done)
        vm.cancel(); advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.finished)
        assertEquals(setOf("wait", "last"), vm.state.value.failedIds)
    }
}
