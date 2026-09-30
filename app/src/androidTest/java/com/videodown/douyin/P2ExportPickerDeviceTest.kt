package com.videodown.douyin

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.*
import com.videodown.douyin.data.download.DownloadPaths
import com.videodown.douyin.data.export.DocumentTreeExporter
import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.presentation.MainActivity
import com.videodown.douyin.presentation.library.*
import com.videodown.douyin.presentation.theme.VideoDownTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

class P2ExportPickerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private var previousTree: String? = null
    private var previousGrants: Map<String, Int> = emptyMap()
    private lateinit var directory: File
    private var outputName = ""
    private var audioName = ""
    private lateinit var audioFile: File
    private lateinit var model: FileExportViewModel
    @Before fun setup() = runBlocking {
        val context = compose.activity.applicationContext
        previousTree = ServiceLocator.preferences.exportTreeUri
        previousGrants = context.contentResolver.persistedUriPermissions.associate { grant ->
            grant.uri.toString() to ((if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))
        }
        ServiceLocator.preferences.exportTreeUri = null
        directory = File(context.cacheDir, "p2-picker-${System.nanoTime()}").apply { mkdirs() }
        val paths = DownloadPaths(directory)
        outputName = "p2-picker-${System.nanoTime()}.mp4"
        val file = File(paths.downloadDir, outputName).apply { writeBytes(ByteArray(5000) { it.toByte() }) }
        val history = DownloadHistoryRepositoryImpl(DownloadHistoryStore(File(directory, "history.json")))
        history.upsert(DownloadRecord("picker", "media", "picker", fileName = file.name, filePath = file.path,
            partialPath = file.path + ".part", status = DownloadStatus.COMPLETED))
        audioName = "p2-picker-${System.nanoTime()}.m4a"
        audioFile = File(paths.downloadDir, audioName)
        InstrumentationRegistry.getInstrumentation().context.assets.open("audio-fixture.m4a").use { input -> audioFile.outputStream().use { input.copyTo(it) } }
        history.upsert(DownloadRecord("audio", "audio", "audio", fileName = audioFile.name, filePath = audioFile.path,
            partialPath = audioFile.path + ".part", status = DownloadStatus.COMPLETED, media = MediaDownload(audioOnly = true)))
        model = FileExportViewModel(DocumentTreeExporter(context.contentResolver, history, paths,
            kotlinx.coroutines.sync.Mutex(), File(directory, "journal")))
        compose.runOnUiThread { compose.activity.setContent { VideoDownTheme { FileExportDialog(listOf("picker", "audio"), model, {}) } } }
    }
    @After fun close() {
        automation.dropShellPermissionIdentity()
        val resolver = compose.activity.contentResolver
        ServiceLocator.preferences.exportTreeUri?.let { uri ->
            runCatching {
                val tree = Uri.parse(uri)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) if (cursor.getString(1) in setOf(outputName, audioName)) {
                        DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)))
                    }
                }
            }
            val addedFlags = (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) and
                (previousGrants[uri] ?: 0).inv()
            if (addedFlags != 0) runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), addedFlags) }
        }
        ServiceLocator.preferences.exportTreeUri = previousTree
        if (::directory.isInitialized) directory.deleteRecursively()
    }
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun tap(vararg labels: String) {
        val deadline = System.currentTimeMillis() + 7000
        while (System.currentTimeMillis() < deadline) {
            // DocumentsUI remembers its last directory across runs.
            if (labels.contentEquals(arrayOf("Documents")) && find(automation.rootInActiveWindow) {
                    it.text?.toString() == "Files in Documents"
                } != null) return
            var node = find(automation.rootInActiveWindow) { item -> labels.any { label ->
                item.text?.toString()?.equals(label, true) == true || item.contentDescription?.toString()?.equals(label, true) == true
            } }
            while (node != null) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                node = node.parent
            }
            Thread.sleep(100)
        }
        fun describe(node: AccessibilityNodeInfo?): String {
            if (node == null) return "null"
            return "${node.text}|${node.contentDescription}|${node.viewIdResourceName}\n" +
                (0 until node.childCount).joinToString("") { describe(node.getChild(it)) }
        }
        error("System picker control not found: ${labels.toList()}\n" + describe(automation.rootInActiveWindow))
    }
    @Test fun systemTreePickerGrantsPersistentAccessAndExportsWithoutShellPermission() {
        compose.onNodeWithText("选择文件夹").performClick()
        tap("Documents")
        tap("Use this folder", "使用此文件夹")
        tap("Allow", "允许")
        compose.waitUntil(10000) { model.state.value.finished }
        assertTrue(model.state.value.failedIds.isEmpty())
        val remembered = ServiceLocator.preferences.exportTreeUri!!
        val grants = compose.activity.contentResolver.persistedUriPermissions
        assertTrue(grants.any { it.uri.toString() == remembered && it.isReadPermission && it.isWritePermission })
        assertEquals(remembered, AppPreferences(compose.activity).exportTreeUri)
        compose.onNodeWithText("已导出 2 项，原文件已保留").assertIsDisplayed()
        val resolver = compose.activity.contentResolver
        val tree = Uri.parse(remembered)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var checkedAudio = false
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)!!.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == audioName) {
                // ExternalStorageProvider derives MIME from its own extension map (m4a may be audio/mpeg).
                assertTrue(cursor.getString(2).startsWith("audio/"))
                assertTrue(cursor.getString(1).endsWith(".m4a"))
                val output = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                assertArrayEquals(audioFile.readBytes(), resolver.openInputStream(output)!!.use { it.readBytes() })
                checkedAudio = true
            }
        }
        assertTrue(checkedAudio)
        assertTrue(audioFile.isFile)
    }
}
