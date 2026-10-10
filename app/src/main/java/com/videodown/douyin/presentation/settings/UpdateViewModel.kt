package com.videodown.douyin.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videodown.douyin.core.AppPreferences
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.data.update.AppUpdateManifest
import com.videodown.douyin.data.update.AppVersion
import com.videodown.douyin.data.update.AppUpdateChecker
import com.videodown.douyin.data.update.UpdateCheckResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What 「我的 → 检查更新」 currently shows. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val current: AppVersion) : UpdateUiState
    data class Available(
        val latest: AppUpdateManifest,
        val downloadUrl: String?,
        val releasePageUrl: String?,
    ) : UpdateUiState
    /**
     * Not configured, offline, or malformed manifest.
     *
     * [reasonKey] names why (see `core/ErrorResources.kt`); the dialog words it.
     */
    data class Message(val reasonKey: String, val httpStatus: Int? = null) : UpdateUiState
}

/**
 * Drives the manual update check. It only reads the remote manifest and hands links back to the
 * UI; installing a new APK is left to the system package installer on an explicit user tap.
 */
class UpdateViewModel(
    private val checker: AppUpdateChecker = ServiceLocator.appUpdateChecker,
    private val preferences: AppPreferences = ServiceLocator.preferences,
) : ViewModel() {

    val currentVersion: AppVersion = ServiceLocator.appVersion

    private val mutableUrl = MutableStateFlow(preferences.updateManifestUrl)
    val manifestUrl = mutableUrl.asStateFlow()

    private val mutableState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state = mutableState.asStateFlow()

    private var checkJob: Job? = null

    fun saveManifestUrl(value: String) {
        preferences.updateManifestUrl = value
        mutableUrl.value = preferences.updateManifestUrl
    }

    fun check() {
        if (mutableState.value is UpdateUiState.Checking) return
        checkJob?.cancel()
        mutableState.value = UpdateUiState.Checking
        checkJob = viewModelScope.launch {
            mutableState.value = try {
                when (val result = checker.check(mutableUrl.value)) {
                    is UpdateCheckResult.UpdateAvailable -> UpdateUiState.Available(
                        latest = result.latest,
                        downloadUrl = result.downloadUrl,
                        releasePageUrl = result.releasePageUrl,
                    )
                    is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(result.current)
                    UpdateCheckResult.NotConfigured -> UpdateUiState.Message("update:not_configured")
                    is UpdateCheckResult.Failed -> UpdateUiState.Message(result.reasonKey, result.httpStatus)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                UpdateUiState.Message("update:failed")
            }
        }
    }
}
