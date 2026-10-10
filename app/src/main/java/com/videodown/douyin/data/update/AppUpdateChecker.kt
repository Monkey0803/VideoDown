package com.videodown.douyin.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Outcome of one update check against the configured manifest endpoint. */
sealed interface UpdateCheckResult {
    /**
     * A strictly newer build is published.
     *
     * 更新说明不在这一层选语言（这里没有 Context）：界面按当前语言调用
     * [AppUpdateManifest.notesFor]。
     */
    data class UpdateAvailable(
        val current: AppVersion,
        val latest: AppUpdateManifest,
        val downloadUrl: String?,
        val releasePageUrl: String?,
    ) : UpdateCheckResult

    data class UpToDate(val current: AppVersion) : UpdateCheckResult

    /** No update source has been configured yet. */
    data object NotConfigured : UpdateCheckResult

    /**
     * The check could not complete.
     *
     * [reasonKey] names the reason (see `core/ErrorResources.kt`) and [httpStatus] is set when the
     * service answered with a failing status; the dialog owns the wording.
     */
    data class Failed(val reasonKey: String, val httpStatus: Int? = null) : UpdateCheckResult
}

/**
 * Compares the installed build against a remote JSON version manifest.
 *
 * The checker only *reads* the manifest; it never downloads or installs an APK. Opening the
 * download / release link stays an explicit user action in the UI, which keeps the app free of
 * `REQUEST_INSTALL_PACKAGES` and silent self-updates.
 */
class AppUpdateChecker(
    private val httpClient: OkHttpClient,
    private val currentVersion: AppVersion,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    suspend fun check(manifestUrl: String?): UpdateCheckResult {
        val url = manifestUrl?.trim().orEmpty()
        if (url.isEmpty()) return UpdateCheckResult.NotConfigured
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return UpdateCheckResult.Failed("update:bad_url")
        }
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext UpdateCheckResult.Failed("update:http_error", httpStatus = response.code)
                    }
                    val body = response.body?.string().orEmpty()
                    val manifest = runCatching { json.decodeFromString<AppUpdateManifest>(body) }
                        .getOrNull()
                        ?: return@withContext UpdateCheckResult.Failed("update:bad_format")
                    evaluate(manifest)
                }
            } catch (error: IOException) {
                return@withContext UpdateCheckResult.Failed("update:network")
            } catch (error: Exception) {
                return@withContext UpdateCheckResult.Failed("update:failed")
            }
        }
    }

    /** Pure comparison step, kept separate so tests need no network. */
    fun evaluate(manifest: AppUpdateManifest): UpdateCheckResult {
        val latestName = manifest.versionName?.trim()?.takeIf(String::isNotEmpty)
        if (latestName == null && manifest.versionCode == null) {
            return UpdateCheckResult.Failed("update:no_version")
        }
        val newer = manifest.versionCode?.let { it > currentVersion.versionCode }
            ?: (compareVersionNames(currentVersion.versionName, latestName!!) < 0)
        return if (newer) {
            UpdateCheckResult.UpdateAvailable(
                current = currentVersion,
                latest = manifest,
                downloadUrl = manifest.apkUrl,
                releasePageUrl = manifest.pageUrl,
            )
        } else {
            UpdateCheckResult.UpToDate(currentVersion)
        }
    }

    private companion object {
        const val USER_AGENT = "VideoDown-Android-UpdateCheck"
    }
}
