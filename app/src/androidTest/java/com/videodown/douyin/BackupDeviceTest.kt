package com.videodown.douyin

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.PreferredQuality
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.backup.BackupDocuments
import com.videodown.douyin.data.backup.LinkList
import com.videodown.douyin.data.backup.SettingsBackupCodec
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Backup and restore over a real document provider: the same read/write path the system picker
 * hands back, driven directly so the test does not have to steer the picker UI.
 */
@RunWith(AndroidJUnit4::class)
class BackupDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver: ContentResolver get() = context.contentResolver
    private val root = DocumentsContract.buildTreeDocumentUri("com.videodown.douyin.test.export", "root")
    private val ownedIds = mutableListOf<String>()
    private var originalTheme: String = ""
    private var originalWifiOnly = false
    private var originalConcurrency = 0
    private var originalLimit = 0
    private var originalClipboard = true

    @Before fun setup() {
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        resolver.call(Uri.parse("content://com.videodown.douyin.test.export"), "fixture-reset", null, null)
        val preferences = ServiceLocator.preferences
        originalTheme = preferences.themeMode.name
        originalWifiOnly = preferences.wifiOnly
        originalConcurrency = preferences.maxConcurrentDownloads
        originalLimit = preferences.globalSpeedLimitKbps
        originalClipboard = preferences.clipboardDetect
        compose.waitForIdle()
    }

    @After fun cleanup() = runBlocking {
        val preferences = ServiceLocator.preferences
        // Defensive: a failure in @Before must not be masked by an exception here.
        com.videodown.douyin.core.ThemeMode.entries.firstOrNull { it.name == originalTheme }?.let { preferences.themeMode = it }
        preferences.wifiOnly = originalWifiOnly
        preferences.maxConcurrentDownloads = originalConcurrency
        preferences.globalSpeedLimitKbps = originalLimit
        preferences.clipboardDetect = originalClipboard
        ownedIds.forEach { runCatching { ServiceLocator.historyRepository.delete(it) } }
        runCatching { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        Unit
    }

    /**
     * Creates the file and returns its document URI.
     *
     * Opening it needs the grant the system picker normally attaches; on API 31+ the adopted
     * MANAGE_DOCUMENTS identity is enough, while API 30 denies it — the same limitation that makes
     * the pre-existing `P2FileExportDeviceTest` fail on that device, so this suite is run on the
     * emulator. Production always holds the grant from ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT.
     */
    private fun createDocument(name: String, mime: String): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(root, "root")
        return DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw AssertionError("The fixture provider refused to create $name")
    }

    @Test fun theLinkListTravelsThroughARealDocumentAndTheRowsAreReachable() {
        runBlocking {
            listOf("https://v.douyin.com/aaa/", "https://m.weibo.cn/status/RklMe8iOM").forEachIndexed { index, url ->
                val id = UUID.randomUUID().toString()
                ownedIds += id
                ServiceLocator.historyRepository.upsert(
                    DownloadRecord(id, "aweme-$index", "备份验证 $index", sourcePageUrl = url,
                        status = DownloadStatus.COMPLETED, fileName = "x$index.mp4"),
                )
            }
        }
        val records = runBlocking { ServiceLocator.historyRepository.snapshot() }
        val uri = createDocument("links-${UUID.randomUUID()}.txt", "text/plain")
        val text = LinkList.format(records)
        assertTrue("Write must succeed", runBlocking { BackupDocuments.write(context, uri, text) })

        val stored = resolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertTrue(stored, stored.startsWith(LinkList.HEADER))
        assertTrue(stored, stored.contains("https://v.douyin.com/aaa/"))
        assertTrue(stored, stored.contains("https://m.weibo.cn/status/RklMe8iOM"))
        // What was written is what an import reads back.
        val imported = LinkList.parse(runBlocking { BackupDocuments.read(context, uri) })
        assertTrue(imported.containsAll(listOf("https://v.douyin.com/aaa/", "https://m.weibo.cn/status/RklMe8iOM")))

        // The rows that trigger this are reachable in 我的.
        compose.onNodeWithText("我的").performClick()
        compose.waitForIdle()
        listOf("导出链接清单", "导入链接", "备份设置", "恢复设置").forEach { title ->
            compose.onNodeWithText(title).performScrollTo().assertExists()
        }
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "BACKUP_LINKS: wrote ${imported.size} links through a real document and all four rows exist\n")
        })
    }

    @Test fun anImportedLinkFileBecomesBatchImportInput() {
        val uri = createDocument("import-${UUID.randomUUID()}.txt", "text/plain")
        val body = "# 手工清单\nhttps://v.douyin.com/aaa/ 复制此链接\n分享文案 https://v.douyin.com/bbb/\nhttps://v.douyin.com/aaa/\n"
        assertTrue(runBlocking { BackupDocuments.write(context, uri, body) })

        val links = LinkList.parse(runBlocking { BackupDocuments.read(context, uri) })
        assertEquals(listOf("https://v.douyin.com/aaa/", "https://v.douyin.com/bbb/"), links)

        // The parse page turns that text into the batch-import dialog's input.
        // 测试替身：ViewModel 在 composable 之外构造（lint: ViewModelConstructorInComposable）。
        val homeModel = com.videodown.douyin.presentation.home.HomeViewModel()
        compose.runOnUiThread {
            compose.activity.setContent {
                com.videodown.douyin.presentation.theme.VideoDownTheme {
                    com.videodown.douyin.presentation.home.HomeScreen(
                        sharedText = null, onSharedTextConsumed = {}, onOpenHistory = {}, onOpenLibrary = {},
                        onPlayDownloaded = {}, recentRecords = emptyList(),
                        viewModel = homeModel,
                        batchImportSeed = links.joinToString("\n"),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("批量链接导入").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("https://v.douyin.com/aaa/", substring = true).assertExists()
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "BACKUP_IMPORT: file links reached the batch-import dialog\n")
        })
    }

    @Test fun aSettingsBackupRestoresRealPreferences() {
        val preferences = ServiceLocator.preferences
        preferences.themeMode = com.videodown.douyin.core.ThemeMode.DARK
        preferences.wifiOnly = true
        preferences.maxConcurrentDownloads = 5
        preferences.globalSpeedLimitKbps = 1024
        preferences.clipboardDetect = false

        val uri = createDocument("settings-${UUID.randomUUID()}.json", "application/json")
        val encoded = SettingsBackupCodec.encode(preferences.snapshot())
        assertTrue(runBlocking { BackupDocuments.write(context, uri, encoded) })

        // A different device starts from other values.
        preferences.themeMode = com.videodown.douyin.core.ThemeMode.LIGHT
        preferences.wifiOnly = false
        preferences.maxConcurrentDownloads = 2
        preferences.globalSpeedLimitKbps = 0
        preferences.clipboardDetect = true
        preferences.preferredQuality = PreferredQuality.HIGHEST

        val restored = SettingsBackupCodec.decode(runBlocking { BackupDocuments.read(context, uri) })
        assertNotNull("The file must parse as a backup", restored)
        val applied = preferences.apply(restored!!)
        assertTrue("A restore should report what it changed, got $applied", applied.isNotEmpty())
        assertEquals(com.videodown.douyin.core.ThemeMode.DARK, preferences.themeMode)
        assertEquals(true, preferences.wifiOnly)
        assertEquals(5, preferences.maxConcurrentDownloads)
        assertEquals(1024, preferences.globalSpeedLimitKbps)
        assertFalse(preferences.clipboardDetect)
        // Applying the same file again changes nothing.
        assertTrue(preferences.apply(restored).isEmpty())
        instrumentation.sendStatus(0, android.os.Bundle().apply {
            putString("stream", "BACKUP_SETTINGS: restored ${applied.size} settings, second apply was a no-op\n")
        })
    }
}
