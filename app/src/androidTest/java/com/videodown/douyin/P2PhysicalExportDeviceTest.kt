package com.videodown.douyin

import android.content.ContentUris
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.presentation.MainActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real media and actual MediaStore / system document-tree authorization. */
class P2PhysicalExportDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private val owned = mutableListOf<DownloadRecord>()
    private val galleryOutputs = mutableListOf<Uri>()
    private val documentOutputs = mutableListOf<Uri>()
    private fun report(text: String) = instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "$text\n") })
    private fun capture(name: String) {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return
        // Only our app or the actual DocumentsUI authorization UI; never lockscreen/notification shade.
        if (root.packageName?.toString() !in setOf(context.packageName, "com.android.documentsui", "com.google.android.documentsui", "android")) return
        val dir = File(context.cacheDir, "p2-export-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap -> File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        fun render(node: AccessibilityNodeInfo): String = buildString {
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            append("<node text=\"${android.text.TextUtils.htmlEncode(node.text?.toString().orEmpty())}\" desc=\"${android.text.TextUtils.htmlEncode(node.contentDescription?.toString().orEmpty())}\" id=\"${node.viewIdResourceName}\" bounds=\"$bounds\">\n")
            for (i in 0 until node.childCount) node.getChild(i)?.let { append(render(it)) }; append("</node>\n")
        }
        File(dir, "$name.xml").writeText(render(root))
    }
    private fun find(node: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        if (labels.any { node.text?.toString()?.equals(it, true) == true || node.contentDescription?.toString()?.equals(it, true) == true }) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { find(it, labels)?.let { match -> return match } }
        return null
    }
    private fun alreadyInDocuments(node: AccessibilityNodeInfo): Boolean {
        if (node.text?.toString() in setOf("Files in Documents", "Documents中的文件", "Documents 中的文件")) return true
        if (node.viewIdResourceName?.endsWith(":id/toolbar") == true && find(node, listOf("Documents", "文档")) != null) return true
        for (i in 0 until node.childCount) if (node.getChild(i)?.let(::alreadyInDocuments) == true) return true
        return false
    }
    private suspend fun tapSystem(vararg labels: String) {
        var node: AccessibilityNodeInfo? = null
        withTimeout(7000) { while (node == null) {
            node = instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, labels.toList()) }
            if (node == null) delay(100)
        } }
        capture("picker-before-${labels.first()}")
        var click = node!!
        while (!click.isClickable && click.parent != null) click = click.parent
        assertTrue("Actual system control ${labels.toList()} must respond", click.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        delay(200); capture("picker-after-${labels.first()}")
    }
    private fun validateReadable(uri: Uri, record: DownloadRecord) {
        assertArrayEquals(File(record.filePath!!).readBytes(), resolver.openInputStream(uri)!!.use { it.readBytes() })
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val type = if (record.isAudio) "audio/" else "video/"
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString("mime")?.startsWith(type) == true }
            assertNotNull("Exported content must retain a readable $type track", track)
            extractor.selectTrack(track!!); assertEquals(track, extractor.sampleTrackIndex)
        } finally { extractor.release() }
        val retriever = MediaMetadataRetriever()
        try { retriever.setDataSource(context, uri); assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 0) }
        finally { retriever.release() }
    }
    private fun gallery(record: DownloadRecord, verify: Boolean = true): Uri? {
        val collection = if (record.isAudio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.RELATIVE_PATH),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(record.fileName), null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                if (verify) {
                    if (cursor.getInt(1) != 0) return null
                    assertEquals(if (record.isAudio) "Music/拾影/" else "Movies/拾影/", cursor.getString(2))
                }
                return ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
        }
        return null
    }
    private fun document(tree: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
        }
        return null
    }
    @Test fun actualGalleryMusicAndDocumentTreeCopiesRemainReadable() = runBlocking {
        assertFalse("Only start from normally unlocked device", context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val previousTree = ServiceLocator.preferences.exportTreeUri
        val previousGrants = resolver.persistedUriPermissions.associate { it.uri.toString() to ((if(it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or (if(it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)) }
        val originalAudio = ServiceLocator.playbackRepository.options(true)
        var selectedTree: Uri? = null
        val ownedFolderName = "P2-export-${UUID.randomUUID()}"
        try {
            ServiceLocator.preferences.exportTreeUri = null
            listOf(false, true).forEach { audio ->
                val id = "p2-export-${UUID.randomUUID()}"
                val file = File(ServiceLocator.downloadPaths.downloadDir, "$id.${if(audio) "m4a" else "mp4"}")
                instrumentation.context.assets.open(if(audio) "background-audio-fixture.m4a" else "landscape-fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
                val record = DownloadRecord(id, id, "导出验收-${if(audio) "音频" else "视频"}-${id.takeLast(8)}", fileName = file.name, filePath = file.path,
                    status = DownloadStatus.COMPLETED, media = if(audio) MediaDownload(audioOnly = true) else null)
                owned += record; ServiceLocator.historyRepository.upsert(record)
            }
            compose.onNodeWithText("已存").performClick()
            for (record in owned) {
                compose.onNodeWithText(record.title).performClick(); capture("player-before-${record.isAudio}")
                compose.onNodeWithContentDescription(if(record.isAudio) "转存到音乐" else "转存到相册").performClick()
                var output: Uri? = null
                withTimeout(10000) { while(output == null) { output = gallery(record); if(output == null) delay(100) } }
                galleryOutputs += output!!; validateReadable(output!!, record)
                capture("gallery-completed-${record.isAudio}")
                report("MEDIASTORE: ${if(record.isAudio) "Music" else "Movies"}, ${record.fileName}, full bytes=${File(record.filePath!!).length()}, track/duration valid, IS_PENDING=0")
                compose.onNodeWithContentDescription("返回").performClick()
            }
            for ((index, record) in owned.withIndex()) {
                compose.onNodeWithText(record.title).performTouchInput { longClick() }
                capture("actions-before-document-${record.isAudio}")
                compose.onNodeWithText("导出到文件夹").performClick()
                if(index == 0) {
                    compose.onNodeWithText("选择文件夹").performClick(); delay(500); capture("system-tree-initial")
                    tapSystem("新建文件夹", "New folder")
                    fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                        if (node.isEditable) return node
                        for(i in 0 until node.childCount) node.getChild(i)?.let { editable(it)?.let { field -> return field } }
                        return null
                    }
                    var field: AccessibilityNodeInfo? = null
                    withTimeout(5000) { while(field == null) { field = instrumentation.uiAutomation.rootInActiveWindow?.let(::editable); if(field == null) delay(100) } }
                    assertTrue(field!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, ownedFolderName)
                    }))
                    capture("system-new-owned-folder")
                    tapSystem("确定", "OK", "创建", "Create")
                    report("SAF_FOLDER: actual picker created own $ownedFolderName")
                    tapSystem("使用此文件夹", "Use this folder")
                    tapSystem("允许", "Allow")
                    withTimeout(10000) { while(ServiceLocator.preferences.exportTreeUri == null) delay(100) }
                    selectedTree = Uri.parse(ServiceLocator.preferences.exportTreeUri!!)
                    assertTrue(resolver.persistedUriPermissions.any { it.uri == selectedTree && it.isReadPermission && it.isWritePermission })
                    report("SAF: actual system folder selection and persistent read/write authorization granted")
                } else compose.onNodeWithText("导出到上次文件夹").performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithText("已导出 1 项，原文件已保留").fetchSemanticsNodes().isNotEmpty() }
                var output: Uri? = null
                withTimeout(10000) { while(output == null) { output = document(selectedTree!!, record.fileName!!); if(output == null) delay(100) } }
                documentOutputs += output!!; validateReadable(output!!, record)
                capture("document-completed-${record.isAudio}")
                report("SAF_COPY: ${record.fileName}, full bytes and media track/duration verified; source retained=${File(record.filePath!!).isFile}")
                compose.onNodeWithText("关闭").performClick()
            }
        } finally {
            withContext(NonCancellable) {
                val cleanupErrors = mutableListOf<String>()
                fun cleanup(label: String, block: () -> Unit) { runCatching(block).onFailure { cleanupErrors += "$label: ${it.javaClass.simpleName}" } }
                try {
                    owned.forEach { record -> cleanup("query own gallery") { gallery(record, verify = false)?.let { if(it !in galleryOutputs) galleryOutputs += it } } }
                    galleryOutputs.forEach { cleanup("delete own gallery") { resolver.delete(it, null, null) } }
                    // Callback may have saved the new tree immediately before a failed assertion.
                    if (selectedTree == null && ServiceLocator.preferences.exportTreeUri != previousTree)
                        selectedTree = ServiceLocator.preferences.exportTreeUri?.let(Uri::parse)
                    selectedTree?.let { tree ->
                        owned.forEach { record -> cleanup("query own document") { document(tree, record.fileName!!)?.let { if(it !in documentOutputs) documentOutputs += it } } }
                        documentOutputs.forEach { cleanup("delete own document") { DocumentsContract.deleteDocument(resolver, it) } }
                        cleanup("delete only empty owned UUID directory") {
                            val documentId = DocumentsContract.getTreeDocumentId(tree)
                            assertEquals(ownedFolderName, documentId.substringAfterLast('/').substringAfterLast(':'))
                            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
                            val count = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)!!.use { it.count }
                            assertEquals("Never delete a non-empty directory", 0, count)
                            assertTrue(DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, documentId)))
                        }
                    }
                } finally {
                    try {
                        selectedTree?.let { tree ->
                            val added = (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) and (previousGrants[tree.toString()] ?: 0).inv()
                            val surviving = resolver.persistedUriPermissions.firstOrNull { it.uri == tree }?.let {
                                (if(it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                                (if(it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                            } ?: 0
                            val release = added and surviving
                            // Deleting the owned tree can already revoke its grant on the real provider.
                            if(release != 0) cleanup("release only added permission") { resolver.releasePersistableUriPermission(tree, release) }
                        }
                    } finally {
                        ServiceLocator.preferences.exportTreeUri = previousTree
                        ServiceLocator.playbackRepository.setOptions(true, originalAudio)
                        owned.forEach { record ->
                            runCatching { ServiceLocator.historyRepository.delete(record.id) }.onFailure { cleanupErrors += "delete own history: ${it.javaClass.simpleName}" }
                            cleanup("delete own source") { File(record.filePath!!).delete() }
                        }
                        cleanup("stop own audio service") { context.stopService(Intent(context, com.videodown.douyin.data.playback.AudioPlaybackService::class.java)) }
                    }
                }
                assertTrue("Cleanup failures after preferences restored: $cleanupErrors", cleanupErrors.isEmpty())
                report("EXPORT_CLEANUP: only own outputs/records/files removed; previous tree and grants preserved")
            }
        }
    }
}
