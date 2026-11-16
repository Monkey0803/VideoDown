package com.videodown.douyin

import android.graphics.BitmapFactory
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.data.playback.VideoFrameExporter
import com.videodown.douyin.data.playback.VideoFrameProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VideoFrameToolsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun previewAndScreenshotUseARealDecodedFrame() = runBlocking {
        val source = File(context.cacheDir, "frame-${UUID.randomUUID()}.mp4")
        instrumentation.context.assets.open("landscape-fixture.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        var exported: android.net.Uri? = null
        try {
            VideoFrameProvider(source).use { provider ->
                val preview = provider.preview(5_000).getOrThrow()
                assertTrue(preview.width in 1..320)
                assertTrue(preview.height in 1..180)
            }
            exported = VideoFrameExporter(context).export(source, 5_000).getOrThrow()
            context.contentResolver.query(exported, arrayOf(
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.RELATIVE_PATH,
            ), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("image/jpeg", cursor.getString(0))
                assertEquals("Pictures/拾影/", cursor.getString(1))
            }
            context.contentResolver.openInputStream(exported)!!.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                assertNotNull(bitmap)
                assertTrue(bitmap.width > 0 && bitmap.height > 0)
                bitmap.recycle()
            }
        } finally {
            exported?.let { context.contentResolver.delete(it, null, null) }
            source.delete()
        }
    }
}
