package com.videodown.douyin

import androidx.test.platform.app.InstrumentationRegistry
import com.videodown.douyin.data.playback.PlaybackPreferences
import com.videodown.douyin.domain.model.PlaybackCheckpoint
import com.videodown.douyin.domain.model.PlaybackOptions
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Run prepare and verify in separate instrumentation processes, with force-stop between them. */
class P3ProcessPersistenceDeviceTest {
    @Test fun checkpointAndSeparateOptionsPersistAcrossProcessRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = PlaybackPreferences(context, "p3_process_persistence")
        val processFile = File(context.cacheDir, "p3-persistence.pid")
        val phase = InstrumentationRegistry.getArguments().getString("phase") ?: "roundtrip"
        if (phase != "verify") {
            processFile.writeText(android.os.Process.myPid().toString())
            assertTrue(context.getSharedPreferences("p3_process_persistence", 0).edit().clear().commit())
            preferences.saveCheckpoint("fixture", 1234, 5678, PlaybackCheckpoint(12000, 60000))
            preferences.setOptions(false, PlaybackOptions(1.5f, true))
            preferences.setOptions(true, PlaybackOptions(0.75f, false))
            // Wait for the real asynchronous preferences write, without making it synchronous.
            val disk = File(context.applicationInfo.dataDir, "shared_prefs/p3_process_persistence.xml")
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val xml = runCatching { disk.readText() }.getOrDefault("")
                if (xml.contains("12000") && xml.contains("0.75") && xml.contains("1.5")) break
                Thread.sleep(50)
            }
            val xml = disk.readText()
            assertTrue(xml.contains("12000") && xml.contains("0.75") && xml.contains("1.5"))
        }
        if (phase == "verify") assertNotEquals(processFile.readText().toInt(), android.os.Process.myPid())
        assertEquals(12000L, preferences.checkpoint("fixture", 1234, 5678).resumePosition())
        assertEquals(PlaybackOptions(1.5f, true), preferences.options(false))
        assertEquals(PlaybackOptions(0.75f, false), preferences.options(true))
        if (phase != "prepare") {
            context.getSharedPreferences("p3_process_persistence", 0).edit().clear().commit()
            processFile.delete()
        }
    }
}
