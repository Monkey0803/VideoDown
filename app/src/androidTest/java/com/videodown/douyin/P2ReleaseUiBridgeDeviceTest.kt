package com.videodown.douyin

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Drives an actual non-debuggable release UI through platform accessibility, without private storage access. */
class P2ReleaseUiBridgeDeviceTest {
    @Test fun preciselyEnterPublicSourceInVisibleReleaseUi() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = InstrumentationRegistry.getArguments().getString("public_source")!!
        require(source.startsWith("https://") || source.startsWith("http://"))
        val automation = instrumentation.uiAutomation
        var observed = automation.rootInActiveWindow
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while(observed == null && android.os.SystemClock.elapsedRealtime() < deadline) { Thread.sleep(50); observed = automation.rootInActiveWindow }
        val root = observed ?: throw AssertionError("Release UI root unavailable after accessibility initialization")
        assertEquals("com.videodown.douyin", root.packageName?.toString())
        fun input(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(node.isEditable) return node
            for(i in 0 until node.childCount) node.getChild(i)?.let { input(it)?.let { field -> return field } }
            return null
        }
        val field = input(root) ?: throw AssertionError("No actual editable input in release UI")
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, source)
        }))
        instrumentation.waitForIdleSync()
        val updateDeadline = android.os.SystemClock.elapsedRealtime() + 5000
        while(input(automation.rootInActiveWindow ?: root)?.text?.toString() != source && android.os.SystemClock.elapsedRealtime() < updateDeadline) Thread.sleep(50)
        assertEquals(source, input(automation.rootInActiveWindow!!)!!.text?.toString())
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "RELEASE_ACTUAL_UI_INPUT: exact public source entered and verified\n") })
    }
    @Test fun actualReleaseUiClickAndCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val automation = instrumentation.uiAutomation
        var root = automation.rootInActiveWindow
        val deadline = android.os.SystemClock.elapsedRealtime() + 5000
        while(root == null && android.os.SystemClock.elapsedRealtime() < deadline) { Thread.sleep(50); root = automation.rootInActiveWindow }
        assertEquals("com.videodown.douyin", root?.packageName?.toString())
        fun find(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
            if(node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for(i in 0 until node.childCount) node.getChild(i)?.let { find(it, label)?.let { match -> return match } }
            return null
        }
        args.getString("ui_label")?.let { label ->
            val target = find(root!!, label) ?: throw AssertionError("Actual release control absent: $label")
            assertTrue("Control must actually be visible", target.isVisibleToUser)
            var clickable = target
            while(!clickable.isClickable && clickable.parent != null) clickable = clickable.parent
            assertTrue("Actual release UI click must respond", clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            Thread.sleep(300)
        }
        root = automation.rootInActiveWindow
        assertEquals("com.videodown.douyin", root?.packageName?.toString())
        val name = args.getString("capture_name") ?: "snapshot"
        require(name.matches(Regex("[A-Za-z0-9_-]{1,60}")))
        val directory = java.io.File(instrumentation.targetContext.cacheDir, "p2-release-ui-evidence").apply { mkdirs() }
        automation.takeScreenshot().let { bitmap -> java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        fun render(node: AccessibilityNodeInfo): String = buildString {
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            append("<node text=\"${android.text.TextUtils.htmlEncode(node.text?.toString().orEmpty())}\" desc=\"${android.text.TextUtils.htmlEncode(node.contentDescription?.toString().orEmpty())}\" id=\"${node.viewIdResourceName}\" bounds=\"$bounds\">\n")
            for(i in 0 until node.childCount) node.getChild(i)?.let { append(render(it)) }; append("</node>\n")
        }
        java.io.File(directory, "$name.xml").writeText(render(root!!))
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "RELEASE_ACTUAL_UI: $name captured after real accessibility action\n") })
    }
    @Test fun independentlyReadActuallyExportedReleaseMedia() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = java.io.File(instrumentation.targetContext.cacheDir, "p2-release-export.mp4")
        assertTrue(file.isFile && file.length() > 0)
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val video = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw AssertionError("Actual exported media has no video track")
            extractor.selectTrack(video)
            assertEquals(video, extractor.sampleTrackIndex)
            assertTrue(extractor.readSampleData(java.nio.ByteBuffer.allocate(4 * 1024 * 1024), 0) > 0)
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue(duration > 60_000)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "RELEASE_EXPORTED_VIDEO: bytes=${file.length()} tracks=${extractor.trackCount} durationMs=$duration readableVideoSample=true\n") })
            } finally { retriever.release() }
        } finally { extractor.release(); file.delete() }
    }

}
