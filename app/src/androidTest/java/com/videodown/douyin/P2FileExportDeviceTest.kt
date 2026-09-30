package com.videodown.douyin

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.data.download.DownloadPaths
import com.videodown.douyin.data.export.DocumentTreeExporter
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.sync.Mutex
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class P2FileExportDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val tree = DocumentsContract.buildTreeDocumentUri("com.videodown.douyin.test.export", "root")
    private lateinit var temporary: File
    private lateinit var exporter: DocumentTreeExporter
    private lateinit var source: File
    @Before fun setup() = runBlocking {
        InstrumentationRegistry.getInstrumentation().uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        resolver.call(Uri.parse("content://com.videodown.douyin.test.export"), "fixture-reset", null, null)
        temporary = File(context.cacheDir, "p2-export-${System.nanoTime()}").apply { mkdirs() }
        val paths = DownloadPaths(temporary)
        source = File(paths.downloadDir, "fixture.mp4").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
        val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(temporary, "history.json")))
        history.upsert(DownloadRecord("saved", "media", "fixture", fileName = source.name, filePath = source.path,
            partialPath = source.path + ".part", status = DownloadStatus.COMPLETED))
        exporter = DocumentTreeExporter(resolver, history, paths, Mutex(), File(temporary, "export-journal"))
    }
    @After fun close() {
        if (::temporary.isInitialized) temporary.deleteRecursively()
        InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
    }
    private fun names(): List<String> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, "root")
        return resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }
    @Test fun actualProviderCopyPreservesBytesAndCollisionCreatesNewDocument() = runBlocking {
        val first = exporter.export("saved", tree.toString()).getOrThrow()
        val second = exporter.export("saved", tree.toString()).getOrThrow()
        assertNotEquals(first, second)
        assertEquals(listOf("fixture (2).mp4", "fixture.mp4"), names())
        listOf(first, second).forEach { output ->
            assertArrayEquals(source.readBytes(), resolver.openInputStream(Uri.parse(output))!!.use { it.readBytes() })
        }
        assertTrue(source.isFile)
    }
    @Test fun failedProviderWriteDeletesOnlyNewDocumentAndPreservesExistingCopy() = runBlocking {
        exporter.export("saved", tree.toString()).getOrThrow()
        resolver.call(Uri.parse("content://com.videodown.douyin.test.export"), "fixture-fail", null, null)
        assertTrue(exporter.export("saved", tree.toString()).isFailure)
        assertEquals(listOf("fixture.mp4"), names())
        assertTrue(source.isFile)
    }
    @Test fun interruptedCopyJournalDeletesOnlyUnfinishedDestination() = runBlocking {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        val partial = DocumentsContract.createDocument(resolver, parent, "video/mp4", "partial.mp4")!!
        resolver.openOutputStream(partial)!!.use { it.write(byteArrayOf(1, 2, 3)) }
        val journal = File(temporary, "export-journal/interrupted.pending")
        journal.parentFile!!.mkdirs(); journal.writeText(partial.toString())
        exporter.recoverInterruptedExports()
        assertTrue(names().isEmpty()); assertFalse(journal.exists()); assertTrue(source.isFile)
    }
    @Test fun unavailableOldProviderDoesNotBlockNewDestinationAndKeepsRecoveryRecord() = runBlocking {
        val pending = File(temporary, "export-journal/unavailable.pending")
        pending.parentFile!!.mkdirs()
        pending.writeText("content://missing.p2.provider/tree/root/document/partial")
        exporter.recoverInterruptedExports()
        assertTrue(pending.isFile)
        exporter.export("saved", tree.toString()).getOrThrow()
        assertEquals(listOf("fixture.mp4"), names())
        assertEquals(1, exporter.pendingCleanupCount())
        assertTrue(pending.isFile); assertTrue(source.isFile)
    }
    @Test fun cancellationDuringCompletedIoHandoffStillDeletesCurrentDestination() = runBlocking {
        val queued = object : CoroutineDispatcher() {
            val tasks = ConcurrentLinkedQueue<Runnable>()
            override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + queued)
        val job = scope.launch { exporter.export("saved", tree.toString()) }
        queued.tasks.remove().run()
        withTimeout(5000) { while (queued.tasks.isEmpty()) delay(10) }
        // The IO result is queued for the owner; cancel before the owner accepts it.
        assertEquals(listOf("fixture.mp4"), names())
        job.cancel()
        // A second caller enters before the cancelled owner's queued continuation.
        // It must skip the live journal and must not reuse the URI of the first copy.
        val second = exporter.export("saved", tree.toString()).getOrThrow()
        assertEquals(listOf("fixture (2).mp4", "fixture.mp4"), names())
        withTimeout(5000) {
            while (!job.isCompleted) { queued.tasks.poll()?.run(); delay(10) }
        }
        assertEquals(listOf("fixture (2).mp4"), names()); assertTrue(source.isFile)
        assertArrayEquals(source.readBytes(), resolver.openInputStream(Uri.parse(second))!!.use { it.readBytes() })
        assertEquals(0, exporter.pendingCleanupCount())
        scope.cancel()
    }
    @Test fun invalidTreeAndMissingRecordDoNotCreateDocuments() = runBlocking {
        assertTrue(exporter.export("saved", "file:///tmp/untrusted").isFailure)
        assertTrue(exporter.export("missing", tree.toString()).isFailure)
        assertTrue(names().isEmpty()); assertTrue(source.isFile)
    }
}
