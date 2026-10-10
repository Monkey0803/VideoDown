package com.videodown.douyin.data.export

import androidx.lifecycle.ViewModelStore
import com.videodown.douyin.domain.repository.FileExportRepository
import com.videodown.douyin.domain.repository.FileExportFailureKind
import com.videodown.douyin.domain.repository.fileExportFailureKind
import com.videodown.douyin.presentation.library.ExportCleanup
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
    @Test fun `revoked permission invalidates only the failed target and preserves retry IDs`() = runTest {
        val repo = object : FileExportRepository {
            override suspend fun export(id: String, treeUri: String) =
                if (id == "bad") Result.failure<String>(SecurityException("grant revoked")) else Result.success("uri/$id")
        }
        val vm = FileExportViewModel(repo); store.put("exports", vm)
        vm.export(listOf("good", "bad"), "revoked-tree"); advanceUntilIdle()
        assertEquals("revoked-tree", vm.state.value.invalidTreeUri)
        assertEquals(setOf("bad"), vm.state.value.failedIds)
        assertEquals(mapOf(FileExportFailureKind.ACCESS_LOST to 1), vm.state.value.failureCounts)
        // The result reports numbers and reason kinds; the screen composes the sentence.
        val revoked = vm.state.value.result!!
        assertEquals(1, revoked.succeeded)
        assertEquals(1, revoked.failed)
        assertEquals(setOf(FileExportFailureKind.ACCESS_LOST), revoked.reasons)
        assertFalse(revoked.cancelled)
    }
    @Test fun `storage full has actionable feedback and allows retry to the same target`() = runTest {
        var full = true
        val repo = object : FileExportRepository {
            override suspend fun export(id: String, treeUri: String) = if (full)
                Result.failure<String>(java.io.IOException("write failed", java.io.IOException("ENOSPC (No space left on device)")))
                else Result.success("uri/$id")
        }
        val vm = FileExportViewModel(repo); store.put("exports", vm)
        vm.export(listOf("saved"), "tree"); advanceUntilIdle()
        assertNull(vm.state.value.invalidTreeUri)
        assertEquals(setOf(FileExportFailureKind.SPACE_FULL), vm.state.value.result!!.reasons)
        assertEquals(1, vm.state.value.result!!.failed)
        assertEquals(mapOf(FileExportFailureKind.SPACE_FULL to 1), vm.state.value.failureCounts)
        full = false
        vm.export(vm.state.value.failedIds.toList(), "tree"); advanceUntilIdle()
        assertTrue(vm.state.value.failedIds.isEmpty()); assertTrue(vm.state.value.failureCounts.isEmpty())
        assertEquals(1, vm.state.value.result!!.succeeded)
        assertEquals(0, vm.state.value.result!!.failed)
    }
    @Test fun `failure classification handles provider wrappers without mislabelling other IO errors`() {
        assertEquals(FileExportFailureKind.SPACE_FULL, fileExportFailureKind(java.io.IOException("EDQUOT (Disk quota exceeded)")))
        assertEquals(FileExportFailureKind.ACCESS_LOST, fileExportFailureKind(java.io.IOException("provider", SecurityException())))
        assertEquals(FileExportFailureKind.OTHER, fileExportFailureKind(java.io.IOException("network unavailable")))
    }
    @Test fun `cleanup status query failure still finishes with actionable feedback`() = runTest {
        val repo = object : FileExportRepository {
            override suspend fun export(id: String, treeUri: String) = Result.success("uri/$id")
            override suspend fun pendingCleanupCount(): Int = throw SecurityException("provider unavailable")
        }
        val vm = FileExportViewModel(repo); store.put("exports", vm)
        vm.export(listOf("good"), "tree"); advanceUntilIdle()
        assertTrue(vm.state.value.finished); assertFalse(vm.state.value.busy)
        // The cleanup check failed, which the screen words as "could not check"; it is not silence.
        assertEquals(ExportCleanup.Unknown, vm.state.value.result!!.cleanup)
    }
}
