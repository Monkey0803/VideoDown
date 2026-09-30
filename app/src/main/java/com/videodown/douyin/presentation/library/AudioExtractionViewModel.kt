package com.videodown.douyin.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videodown.douyin.core.ServiceLocator
import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.repository.Downloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AudioExtractionState(val busy: Boolean = false, val duplicate: DownloadRecord? = null, val message: String? = null)
class AudioExtractionViewModel(private val downloader: Downloader = ServiceLocator.downloader) : ViewModel() {
    private val mutable = MutableStateFlow(AudioExtractionState())
    val state = mutable.asStateFlow()
    fun reset() { if (!mutable.value.busy) mutable.value = AudioExtractionState() }
    fun start(record: DownloadRecord, again: Boolean = false, onQueued: () -> Unit = {}) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try {
                val (video, variant) = record.audioExtractionSelection()
                val existing = if (again) null else downloader.findDuplicate(video, variant)
                if (existing != null) mutable.update { it.copy(duplicate = existing, message = "此视频已有音频任务或副本，可在任务或影像库中查看") }
                else { downloader.start(video, variant); mutable.update { it.copy(duplicate = null) }; onQueued() }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutable.update { it.copy(message = "无法加入提取队列，请检查原视频后重试") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
