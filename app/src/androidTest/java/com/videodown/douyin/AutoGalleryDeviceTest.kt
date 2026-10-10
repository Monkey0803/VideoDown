package com.videodown.douyin

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.download.DownloadCompletionNotifier
import com.videodown.douyin.domain.model.DouyinVideo
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.VideoPlatform
import com.videodown.douyin.domain.model.VideoVariant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * A real completed transfer with 「下载后自动存入相册」 on: the copy must really land in MediaStore,
 * and the single completion card must tell the user where it went.
 */
@RunWith(AndroidJUnit4::class)
class AutoGalleryDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val evidence get() = File(context.cacheDir, "auto-gallery-evidence").apply { mkdirs() }
    private val ownedIds = mutableListOf<String>()

    private fun digest(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun galleryItem(name: String): android.net.Uri? = context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media._ID),
        "${MediaStore.Video.Media.DISPLAY_NAME} = ?", arrayOf(name), null,
    )?.use { cursor -> if (cursor.moveToFirst()) {
        android.content.ContentUris.withAppendedId(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0),
        )
    } else null }

    private fun removeGalleryCopies(names: Collection<String>) {
        names.forEach { name ->
            runCatching {
                context.contentResolver.delete(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    "${MediaStore.Video.Media.DISPLAY_NAME} = ?", arrayOf(name),
                )
            }
        }
    }

    @After fun cleanup() = runBlocking {
        ownedIds.forEach { runCatching { ServiceLocator.downloader.cancel(it, true) } }
        manager.activeNotifications.filter { it.tag in ownedIds }.forEach { manager.cancel(it.tag, it.id) }
        ServiceLocator.preferences.autoSaveToGallery = false
        Unit
    }

    @Test fun aFinishedDownloadLandsInTheGalleryAndSaysSoOnTheCompletionCard() = runBlocking {
        assertTrue("System notifications must be genuinely enabled", DownloadCompletionNotifier.areNotificationsEnabled(context))
        assertTrue("Preserve any existing transfer", ServiceLocator.downloader.progress.value.values.none { it.isActive })
        // A foreground service may only be started while the app itself is in the foreground.
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        compose.waitUntil(15_000) { compose.onAllNodesWithText("解析").fetchSemanticsNodes().isNotEmpty() }

        val originalGallery = ServiceLocator.preferences.autoSaveToGallery
        val originalNotify = ServiceLocator.preferences.notifyOnCompletion
        val directory = File(context.cacheDir, "auto-gallery-${UUID.randomUUID()}").apply { mkdirs() }
        val createdNames = mutableListOf<String>()
        try {
            instrumentation.context.assets.open("landscape-fixture.mp4").use { input ->
                File(directory, "sample.mp4").outputStream().use { input.copyTo(it) }
            }
            val bytes = File(directory, "sample.mp4").length()
            ServiceLocator.preferences.autoSaveToGallery = true
            ServiceLocator.preferences.notifyOnCompletion = true
            FixtureServer(directory).use { server ->
                val title = "自动入库-${UUID.randomUUID().toString().take(8)}"
                val variant = VideoVariant("${server.baseUrl}/sample.mp4", "720P", sizeBytes = bytes)
                val video = DouyinVideo(UUID.randomUUID().toString(), title, platform = VideoPlatform.GENERIC, variants = listOf(variant))
                val id = ServiceLocator.downloader.start(video, variant).also(ownedIds::add)
                val completed = withTimeout(30_000) {
                    ServiceLocator.historyRepository.observeRecord(id).first { it?.status == DownloadStatus.COMPLETED }!!
                }
                val local = completed.filePath?.let(::File)
                assertNotNull("A completed record must have a local file", local)
                assertTrue(local!!.isFile)
                createdNames += local!!.name

                val item = withTimeout(20_000) {
                    var found: android.net.Uri? = null
                    while (found == null) { found = galleryItem(local!!.name); if (found == null) delay(100) }
                    found
                }
                // The copy must be the same bytes, not an empty placeholder row.
                val copied = context.contentResolver.openInputStream(item)!!.use { it.readBytes() }
                assertEquals(local!!.length(), copied.size.toLong())
                assertEquals(digest(local!!), MessageDigest.getInstance("SHA-256").digest(copied).joinToString("") { "%02x".format(it) })

                withTimeout(15_000) {
                    while (manager.activeNotifications.none { it.tag == id && it.id == 1002 }) delay(50)
                }
                val card = manager.activeNotifications.first { it.tag == id && it.id == 1002 }
                val text = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
                    .mapNotNull { card.notification.extras.getCharSequence(it)?.toString() }
                    .joinToString("\n")
                assertTrue("The completion card must report the gallery copy, got: $text", text.contains("已存入相册"))

                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    File(evidence, "gallery-card.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                File(evidence, "summary.txt").writeText(
                    "record=$id\ntitle=$title\nlocal=${local!!.name} bytes=${local!!.length()}\n" +
                        "gallery row=$item\nsha256 matched=true\ncard text=$text\n",
                )
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "AUTO_GALLERY: ${local!!.name} copied with matching sha256; card said 已存入相册\n")
                })
            }
        } finally {
            ServiceLocator.preferences.autoSaveToGallery = originalGallery
            ServiceLocator.preferences.notifyOnCompletion = originalNotify
            ownedIds.forEach { runCatching { ServiceLocator.downloader.cancel(it, true) } }
            manager.activeNotifications.filter { it.tag in ownedIds }.forEach { manager.cancel(it.tag, it.id) }
            removeGalleryCopies(createdNames)
            directory.deleteRecursively()
        }
    }
}
