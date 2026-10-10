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

data class AudioExtractionState(val busy: Boolean = false, val duplicate: DownloadRecord? = null, val notice: AudioExtractNotice? = null)
class AudioExtractionViewModel(private val downloader: Downloader = ServiceLocator.downloader) : ViewModel() {
    private val mutable = MutableStateFlow(AudioExtractionState())
    val state = mutable.asStateFlow()
    fun reset() { if (!mutable.value.busy) mutable.value = AudioExtractionState() }
    fun start(record: DownloadRecord, again: Boolean = false, onQueued: () -> Unit = {}) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                val (video, variant) = record.audioExtractionSelection()
                val existing = if (again) null else downloader.findDuplicate(video, variant)
                if (existing != null) mutable.update { it.copy(duplicate = existing, notice = AudioExtractNotice.Duplicate) }
                else { downloader.start(video, variant); mutable.update { it.copy(duplicate = null) }; onQueued() }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutable.update { it.copy(notice = AudioExtractNotice.QueueFailed) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}

/** A neutral outcome of the extract dialog; the dialog words it. */
enum class AudioExtractNotice { Duplicate, QueueFailed }
