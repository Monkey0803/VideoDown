package com.videodown.douyin.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Outcome of one update check against the configured manifest endpoint. */
sealed interface UpdateCheckResult {
    /** A strictly newer build is published. */
    data class UpdateAvailable(
        val currentLabel: String,
        val latestLabel: String,
        val notes: String?,
        val downloadUrl: String?,
        val releasePageUrl: String?,
    ) : UpdateCheckResult

    data class UpToDate(val currentLabel: String) : UpdateCheckResult

    /** No update source has been configured yet. */
    data object NotConfigured : UpdateCheckResult

    /** The check could not complete; [message] is user-facing Simplified Chinese. */
    data class Failed(val message: String) : UpdateCheckResult
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
            return UpdateCheckResult.Failed("更新源地址需以 http:// 或 https:// 开头")
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
                        return@withContext UpdateCheckResult.Failed(
                            "更新服务返回错误（HTTP ${response.code}），请稍后重试",
                        )
                    }
                    val body = response.body?.string().orEmpty()
                    val manifest = runCatching { json.decodeFromString<AppUpdateManifest>(body) }
                        .getOrNull()
                        ?: return@withContext UpdateCheckResult.Failed("更新信息格式不正确，请检查更新源内容")
                    evaluate(manifest)
                }
            } catch (error: IOException) {
                return@withContext UpdateCheckResult.Failed("无法连接更新服务，请检查网络后重试")
            } catch (error: Exception) {
                return@withContext UpdateCheckResult.Failed("更新检查失败，请稍后重试")
            }
        }
    }

    /** Pure comparison step, kept separate so tests need no network. */
    fun evaluate(manifest: AppUpdateManifest): UpdateCheckResult {
        val latestLabel = manifest.versionLabel
            ?: return UpdateCheckResult.Failed("更新信息缺少版本号，请检查更新源内容")
        val newer = manifest.versionCode?.let { it > currentVersion.versionCode }
            ?: (compareVersionNames(currentVersion.versionName, latestLabel) < 0)
        return if (newer) {
            UpdateCheckResult.UpdateAvailable(
                currentLabel = currentVersion.label,
                latestLabel = latestLabel,
                notes = manifest.releaseNotes,
                downloadUrl = manifest.apkUrl,
                releasePageUrl = manifest.pageUrl,
            )
        } else {
            UpdateCheckResult.UpToDate(currentVersion.label)
        }
    }

    private companion object {
        const val USER_AGENT = "VideoDown-Android-UpdateCheck"
    }
}
