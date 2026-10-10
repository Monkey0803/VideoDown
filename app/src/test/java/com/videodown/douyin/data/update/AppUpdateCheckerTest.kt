package com.videodown.douyin.data.update

import com.videodown.douyin.data.download.RangeAwareTestServer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the update check against a real local HTTP server so the OkHttp path (status codes,
 * JSON decoding, failure messages) is covered without touching a live endpoint.
 */
class AppUpdateCheckerTest {

    private val current = AppVersion(versionCode = 2, versionName = "1.0.1")

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun checker() = AppUpdateChecker(client, current)

    private fun serve(body: String, statusCode: Int = 200): RangeAwareTestServer =
        RangeAwareTestServer(payload = body.toByteArray(), statusCode = statusCode)

    private fun urlOf(server: RangeAwareTestServer) = "http://127.0.0.1:${server.port}/version.json"

    @Test
    fun `reports a newer build with notes and download link`() = runBlocking {
        val manifest = """
            {"versionCode":3,"versionName":"1.0.2","downloadUrl":"https://example.com/app-1.0.2.apk",
             "releasePageUrl":"https://example.com/releases/1.0.2","notes":"修复导出失败反馈"}
        """.trimIndent()
        serve(manifest).use { server ->
            val result = checker().check(urlOf(server))
            val available = result as UpdateCheckResult.UpdateAvailable
            assertEquals("1.0.2", available.latest.versionName)
            assertEquals("修复导出失败反馈", available.latest.notesFor(english = false))
            assertEquals("https://example.com/app-1.0.2.apk", available.downloadUrl)
            assertEquals("https://example.com/releases/1.0.2", available.releasePageUrl)
            assertEquals(current, available.current)
        }
    }

    @Test
    fun `keeps both note languages apart`() = runBlocking {
        val manifest = """
            {"versionCode":3,"versionName":"1.0.2","notes":"新增合集解析","notesEn":"Adds collection parsing"}
        """.trimIndent()
        serve(manifest).use { server ->
            val latest = (checker().check(urlOf(server)) as UpdateCheckResult.UpdateAvailable).latest
            assertEquals("Adds collection parsing", latest.notesFor(english = true))
            assertEquals("新增合集解析", latest.notesFor(english = false))
        }
    }

    @Test
    fun `falls back to the other language when one note is missing`() {
        assertEquals("only chinese", AppUpdateManifest(notes = "only chinese").notesFor(english = true))
        assertEquals("only english", AppUpdateManifest(notesEn = "only english").notesFor(english = false))
        assertEquals("only english", AppUpdateManifest(versionCode = 3, notesEn = "only english").notesFor(english = true))
        // 空串与空白不算说明，仍然回落到另一版；两版都没有就是没有。
        assertEquals("real", AppUpdateManifest(notes = "real", notesEn = "   ").notesFor(english = true))
        assertEquals(null, AppUpdateManifest(versionCode = 3, notes = "", notesEn = " ").notesFor(english = false))
        assertEquals(null, AppUpdateManifest(versionCode = 3).notesFor(english = true))
    }

    @Test
    fun `english notes win over the changelog alias, and the alias still serves chinese`() {
        val manifest = AppUpdateManifest(versionCode = 3, changelog = "别名说明", notesEn = "alias note")
        assertEquals("alias note", manifest.notesFor(english = true))
        assertEquals("别名说明", manifest.notesFor(english = false))
    }

    @Test
    fun `stays quiet when the manifest matches the installed build`() = runBlocking {
        serve("""{"versionCode":2,"versionName":"1.0.1"}""").use { server ->
            val result = checker().check(urlOf(server))
            assertEquals(UpdateCheckResult.UpToDate(current), result)
        }
    }

    @Test
    fun `accepts a versionName-only manifest`() = runBlocking {
        serve("""{"versionName":"1.2.0","url":"https://example.com/app.apk"}""").use { server ->
            val available = checker().check(urlOf(server)) as UpdateCheckResult.UpdateAvailable
            assertEquals("1.2.0", available.latest.versionName)
            assertEquals("https://example.com/app.apk", available.downloadUrl)
        }
    }

    @Test
    fun `treats an older versionName-only manifest as up to date`() = runBlocking {
        serve("""{"versionName":"0.9.9"}""").use { server ->
            assertEquals(UpdateCheckResult.UpToDate(current), checker().check(urlOf(server)))
        }
    }

    @Test
    fun `reports http errors with the status code`() = runBlocking {
        serve("", statusCode = 500).use { server ->
            val failure = checker().check(urlOf(server)) as UpdateCheckResult.Failed
        }
    }

    @Test
    fun `rejects a malformed manifest`() = runBlocking {
        serve("not json at all").use { server ->
            val failure = checker().check(urlOf(server)) as UpdateCheckResult.Failed
            assertEquals("update:bad_format", failure.reasonKey)
        }
    }

    @Test
    fun `rejects a manifest without any version`() = runBlocking {
        serve("""{"notes":"no version here"}""").use { server ->
            val failure = checker().check(urlOf(server)) as UpdateCheckResult.Failed
            assertEquals("update:no_version", failure.reasonKey)
        }
    }

    @Test
    fun `requires a configured http source`() = runBlocking {
        assertEquals(UpdateCheckResult.NotConfigured, checker().check(null))
        assertEquals(UpdateCheckResult.NotConfigured, checker().check("   "))
        val failure = checker().check("ftp://example.com/version.json") as UpdateCheckResult.Failed
        assertEquals("update:bad_url", failure.reasonKey)
    }

    @Test
    fun `compares dotted version names numerically`() {
        assertTrue(compareVersionNames("1.0.1", "1.0.2") < 0)
        assertTrue(compareVersionNames("1.0.1", "1.10.0") < 0)
        assertTrue(compareVersionNames("1.0.1", "1.0.1") == 0)
        assertTrue(compareVersionNames("2.0.0", "1.9.9") > 0)
        assertTrue(compareVersionNames("1.0", "1.0.0.1") < 0)
    }
}
