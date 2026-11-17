package com.videodown.douyin

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.data.gallery.GalleryExporter
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Android 8/9 专用：验证老式 MediaStore 在拒绝权限时不会删掉应用内原件。 */
@RunWith(AndroidJUnit4::class)
class LegacyImageGalleryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun deniedPermissionKeepsPrivateImageAndGrantAllowsExport() = runBlocking {
        assumeTrue("legacy storage only", Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
        val source = File(context.cacheDir, "legacy-gallery.png")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val record = DownloadRecord("legacy", "legacy", "legacy", fileName = source.name,
            filePath = source.absolutePath, status = DownloadStatus.COMPLETED, mediaKind = MediaKind.IMAGE)
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        instrumentation.uiAutomation.revokeRuntimePermission(context.packageName, permission)
        assertTrue(GalleryExporter(context).export(record).isFailure)
        assertTrue(source.isFile)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val result = GalleryExporter(context).export(record)
        assertTrue(result.exceptionOrNull()?.toString().orEmpty(), result.isSuccess)
        result.getOrNull()?.let { context.contentResolver.delete(it, null, null) }
        source.delete()
        Unit
    }
}
