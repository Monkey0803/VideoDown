package com.videodown.douyin

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.generic.validateVideo
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.presentation.MainActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real picker grants and OS proxy-FD quota; never fills physical user storage. */
class P2ExportFailureDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private val authority = "com.videodown.douyin.test.quotaexport"
    private fun fixtureCall(method: String): android.os.Bundle? {
        // Test APK owner configures its quota; no document access or shell identity is granted.
        return resolver.call(Uri.parse("content://com.videodown.douyin.test.quotacontrol"), method, null, null)
    }
    private fun report(value: String) = instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "$value\n") })
    private fun capture(name: String) {
        var observed = instrumentation.uiAutomation.rootInActiveWindow
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while(observed == null && android.os.SystemClock.elapsedRealtime() < deadline) { Thread.sleep(50); observed = instrumentation.uiAutomation.rootInActiveWindow }
        val root = observed ?: error("No UI root after accessibility initialization")
        check(root.packageName?.toString() in setOf(context.packageName, "com.android.documentsui", "com.google.android.documentsui", "android"))
        val dir = File(context.cacheDir, "p2-export-failure-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap -> File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        fun render(node: AccessibilityNodeInfo): String = buildString {
            append("<node text=\"${android.text.TextUtils.htmlEncode(node.text?.toString().orEmpty())}\" desc=\"${android.text.TextUtils.htmlEncode(node.contentDescription?.toString().orEmpty())}\" bounds=\"${android.graphics.Rect().also(node::getBoundsInScreen)}\">\n")
            for(i in 0 until node.childCount) node.getChild(i)?.let { append(render(it)) }; append("</node>\n")
        }
        File(dir, "$name.xml").writeText(render(root))
    }
    private fun find(node: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        if(labels.any { node.text?.toString()?.equals(it, true) == true || node.contentDescription?.toString()?.equals(it, true) == true }) return node
        for(i in 0 until node.childCount) node.getChild(i)?.let { find(it, labels)?.let { item -> return item } }; return null
    }
    private suspend fun tapSystem(vararg labels: String) {
        report("SYSTEM_TAP_WAIT: ${labels.toList()}")
        fun drawerList(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(node.viewIdResourceName?.endsWith(":id/roots_list") == true) return node
            for(i in 0 until node.childCount) node.getChild(i)?.let { drawerList(it)?.let { list -> return list } }; return null
        }
        var found: AccessibilityNodeInfo? = null
        withTimeout(7000) { while(found == null) {
            found = instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                val scope = if(labels.contentEquals(arrayOf("P2 空间配额测试")) || labels.contentEquals(arrayOf("SHARK PRS-A0"))) drawerList(root) else root
                scope?.let { find(it, labels.toList()) }?.takeIf { it.isVisibleToUser && it.isEnabled }
            }
            if(found == null) delay(50)
        } }
        var node = found!!; while(!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); report("SYSTEM_TAP_DONE: ${labels.toList()}"); delay(200)
    }
    private suspend fun selectQuotaRoot() {
        val other = compose.onAllNodesWithText("选择其他文件夹").fetchSemanticsNodes().isNotEmpty()
        compose.onNodeWithText(if(other) "选择其他文件夹" else "选择文件夹").performClick()
        withTimeout(5000) { while(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() !in setOf("com.android.documentsui", "com.google.android.documentsui")) delay(50) }
        withTimeout(5000) {
            while(true) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val bounds = android.graphics.Rect().also { root?.getBoundsInScreen(it) }
                if(root?.packageName?.toString() in setOf("com.android.documentsui", "com.google.android.documentsui") && bounds.left == 0 && bounds.width() == context.resources.displayMetrics.widthPixels) break
                delay(50)
            }
        }
        capture("picker-before-grant")
        // Always select the own root; a cached recent location can expose a disabled Use button.
        tapSystem("显示根目录")
        capture("picker-roots-drawer")
        tapSystem("P2 空间配额测试")
        tapSystem("使用此文件夹", "Use this folder"); tapSystem("允许", "Allow")
    }
    private suspend fun selectNewExternalFolder(name: String) {
        compose.onNodeWithText("选择文件夹").performClick()
        withTimeout(5000) { while(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() !in setOf("com.android.documentsui", "com.google.android.documentsui")) delay(50) }
        tapSystem("显示根目录"); tapSystem("SHARK PRS-A0")
        tapSystem("新建文件夹")
        fun editable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(node.isEditable) return node
            for(i in 0 until node.childCount) node.getChild(i)?.let { editable(it)?.let { field -> return field } }; return null
        }
        var input: AccessibilityNodeInfo? = null
        withTimeout(5000) { while(input == null) { input = instrumentation.uiAutomation.rootInActiveWindow?.let(::editable); if(input == null) delay(50) } }
        assertTrue(input!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
        }))
        tapSystem("确定", "创建", "OK", "Create")
        tapSystem("使用此文件夹", "Use this folder"); tapSystem("允许", "Allow")
    }
    private fun documents(tree: Uri): List<Uri> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)!!.use { cursor ->
            buildList { while(cursor.moveToNext()) add(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))) }
        }
    }
    private fun waitMessage(fragment: String) {
        compose.waitUntil(10000) { compose.onAllNodesWithText(fragment, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(fragment, substring = true).assertIsDisplayed()
    }
    @Test fun revokedActualTreeGrantRequiresReselectionAndRecovers() = runBlocking { exercise(accessLost = true) }
    @Test fun realProxyFdEnospcReportsSpaceAndRecoversWithoutChangingSource() = runBlocking { exercise(accessLost = false) }
    private suspend fun exercise(accessLost: Boolean) {
        val accessibility = instrumentation.uiAutomation.serviceInfo
        accessibility.flags = accessibility.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        instrumentation.uiAutomation.serviceInfo = accessibility
        assertFalse(context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val previousTree = ServiceLocator.preferences.exportTreeUri
        val previousGrants = resolver.persistedUriPermissions.map { it.uri }.toSet()
        fixtureCall("quota-reset")
        if(accessLost) fixtureCall("quota-unlimited")
        val id = "export-failure-${UUID.randomUUID()}"
        val file = File(ServiceLocator.downloadPaths.downloadDir, "$id.mp4")
        val bytes = instrumentation.context.assets.open("landscape-fixture.mp4").use { it.readBytes() }
        var ownTree: Uri? = null
        val externalFolder = "P2-access-recovery-${UUID.randomUUID()}"
        var externalTree: Uri? = null
        var primaryFailure: Throwable? = null
        try {
            file.writeBytes(bytes)
            ServiceLocator.historyRepository.upsert(DownloadRecord(id, id, id, fileName=file.name, filePath=file.path, status=DownloadStatus.COMPLETED))
            val initialTree = DocumentsContract.buildTreeDocumentUri(authority, "root")
            assertFalse("Fixture initial URI must not supply a persisted grant", resolver.persistedUriPermissions.any { it.uri == initialTree })
            try { documents(initialTree); fail("Initial URI must not authorize document access") }
            catch (_: SecurityException) { report("BEFORE_PICKER: own tree query denied; initial URI provides navigation only") }
            ServiceLocator.preferences.exportTreeUri = DocumentsContract.buildDocumentUri(authority, "root").toString()
            compose.onNodeWithText("已存").performClick()
            compose.onNodeWithText(id).performTouchInput { longClick() }; compose.onNodeWithText("导出到文件夹").performClick()
            selectQuotaRoot()
            compose.waitUntil(7000) { ServiceLocator.preferences.exportTreeUri != null }
            ownTree = Uri.parse(ServiceLocator.preferences.exportTreeUri!!)
            assertTrue(resolver.persistedUriPermissions.any { it.uri == ownTree && it.isReadPermission && it.isWritePermission })
            withTimeout(5000) { while(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != context.packageName) delay(50) }
            compose.waitForIdle(); delay(600) // System activity exit animation must finish before revocation / screenshots.
            capture("actual-grant-stable")
            report("ACTUAL_TREE_GRANTED: test provider persistent read/write granted by DocumentsUI")
            if(accessLost) {
                waitMessage("已导出 1 项，原文件已保留")
                compose.onNodeWithText("关闭").performClick()
                resolver.releasePersistableUriPermission(ownTree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val diagnostic = checkNotNull(fixtureCall("quota-revoke"))
                report("OWNER_REVOKE_DIAGNOSTIC: " + diagnostic.keySet().sorted().joinToString { "$it=${diagnostic.get(it)}" })
                assertFalse(resolver.persistedUriPermissions.any { it.uri == ownTree })
                try { documents(ownTree); fail("Owner revocation must genuinely deny SAF query") }
                catch (_: SecurityException) { report("AFTER_OWNER_REVOKE: persistent and temporary tree access denied by Android") }
                compose.onNodeWithText(id).performTouchInput { longClick() }; compose.onNodeWithText("导出到文件夹").performClick()
                compose.onNodeWithText("导出到上次文件夹").performClick()
                waitMessage("文件夹不可写或授权已失效，请重新选择文件夹")
                compose.onNodeWithText("选择文件夹").assertIsDisplayed()
                compose.onNodeWithText("重试失败项到上次文件夹").assertDoesNotExist()
                assertNull(ServiceLocator.preferences.exportTreeUri)
                capture("access-lost-visible")
                selectNewExternalFolder(externalFolder)
                compose.waitUntil(7000) { ServiceLocator.preferences.exportTreeUri != null }
                externalTree = Uri.parse(ServiceLocator.preferences.exportTreeUri!!)
                assertEquals(externalFolder, DocumentsContract.getTreeDocumentId(externalTree).substringAfterLast('/').substringAfterLast(':'))
                assertTrue(resolver.persistedUriPermissions.any { it.uri == externalTree && it.isReadPermission && it.isWritePermission })
            } else {
                waitMessage("目标空间不足，请释放空间或选择其他文件夹后重试")
                compose.onNodeWithText("重试失败项到上次文件夹").assertIsDisplayed()
                assertEquals(ownTree.toString(), ServiceLocator.preferences.exportTreeUri)
                val stats = checkNotNull(fixtureCall("quota-stats"))
                assertTrue("Real partial bytes must precede ENOSPC", stats.getLong("bytesWritten") > 0)
                assertTrue("OS proxy FD must have returned ENOSPC", stats.getInt("enospcCount") > 0)
                report("QUOTA_OS_STATS: bytesWritten=${stats.getLong("bytesWritten")}, enospcCount=${stats.getInt("enospcCount")}")
                assertTrue(documents(ownTree).isEmpty())
                capture("enospc-visible")
                fixtureCall("quota-unlimited")
                compose.onNodeWithText("重试失败项到上次文件夹").performClick()
            }
            assertArrayEquals(bytes, file.readBytes())
            waitMessage("已导出 1 项，原文件已保留")
            val outputs = documents(externalTree ?: ownTree)
            assertEquals(1, outputs.size)
            outputs.forEach { assertArrayEquals(bytes, resolver.openInputStream(it)!!.use { stream -> stream.readBytes() }) }
            validateVideo(file)
            capture(if(accessLost) "access-recovered" else "enospc-recovered")
            report("${if(accessLost) "REVOKED_GRANT" else "INJECTED_OS_ENOSPC"}_RECOVERED: actual UI retry copies=$outputs; original bytes/media unchanged")
            compose.onNodeWithText("关闭").performClick()
        } catch(error: Throwable) {
            primaryFailure = error
            runCatching { capture(if(accessLost) "access-failure-terminal" else "quota-failure-terminal") }.onFailure(error::addSuppressed)
            throw error
        } finally {
            withContext(NonCancellable) {
                var cleanupError: Throwable? = null
                suspend fun cleanup(action: suspend () -> Unit) {
                    try { action() } catch(error: Throwable) {
                        if(cleanupError == null) cleanupError = error else cleanupError!!.addSuppressed(error)
                    }
                }
                cleanup {
                    externalTree?.let { tree ->
                        assertEquals(externalFolder, DocumentsContract.getTreeDocumentId(tree).substringAfterLast('/').substringAfterLast(':'))
                        documents(tree).forEach { DocumentsContract.deleteDocument(resolver, it) }
                        assertTrue(documents(tree).isEmpty())
                        assertTrue(DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))))
                    }
                }
                cleanup { fixtureCall("quota-reset") }
                cleanup { ServiceLocator.preferences.exportTreeUri = previousTree }
                for(tree in listOfNotNull(ownTree, externalTree).filterNot { it in previousGrants }) cleanup {
                    resolver.persistedUriPermissions.firstOrNull { it.uri == tree }?.let { grant ->
                        resolver.releasePersistableUriPermission(tree,
                            (if(grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                                (if(grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))
                    }
                }
                cleanup { ServiceLocator.historyRepository.delete(id) }
                cleanup { check(!file.exists() || file.delete()) { "Own source cleanup failed" } }
                cleanupError?.let { error -> primaryFailure?.addSuppressed(error) ?: throw error }
                report("CLEANUP: own provider files/record/source removed; prior tree and grants preserved")
            }
        }
    }
}
