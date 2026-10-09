package com.videodown.douyin.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videodown.douyin.core.AppPreferences
import com.videodown.douyin.core.ServiceLocator
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
    data class UpToDate(val currentLabel: String) : UpdateUiState
    data class Available(
        val latestLabel: String,
        val notes: String?,
        val downloadUrl: String?,
        val releasePageUrl: String?,
    ) : UpdateUiState
    /** Not configured, offline, or malformed manifest — [text] is already user-facing. */
    data class Message(val text: String) : UpdateUiState
}

/**
 * Drives the manual update check. It only reads the remote manifest and hands links back to the
 * UI; installing a new APK is left to the system package installer on an explicit user tap.
 */
class UpdateViewModel(
    private val checker: AppUpdateChecker = ServiceLocator.appUpdateChecker,
    private val preferences: AppPreferences = ServiceLocator.preferences,
) : ViewModel() {

    val currentLabel: String = ServiceLocator.appVersion.label

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
                        latestLabel = result.latestLabel,
                        notes = result.notes,
                        downloadUrl = result.downloadUrl,
                        releasePageUrl = result.releasePageUrl,
                    )
                    is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(result.currentLabel)
                    UpdateCheckResult.NotConfigured -> UpdateUiState.Message("尚未配置更新源地址，请先设置版本清单地址")
                    is UpdateCheckResult.Failed -> UpdateUiState.Message(result.message)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                UpdateUiState.Message("更新检查失败，请稍后重试")
            }
        }
    }
}
