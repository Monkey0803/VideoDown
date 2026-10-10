package com.videodown.douyin.data.local

import com.videodown.douyin.R
import com.videodown.douyin.core.AppText
import android.util.Log
import com.videodown.douyin.domain.model.DownloadPauseCause
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Small, dependency-free persistence layer for download history.
 *
 * History is a single JSON document rewritten atomically (write to `.tmp`, then rename) on
 * every mutation. Download history is small (hundreds of rows at most) and always read as a
 * whole list, so a full-document rewrite keeps the code simple while remaining crash-safe.
 */
class DownloadHistoryStore(
    private val historyFile: File,
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val listSerializer = ListSerializer(DownloadRecord.serializer())
    private val writeMutex = Mutex()

    private val _records = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val records: StateFlow<List<DownloadRecord>> = _records.asStateFlow()

    private var loaded = false

    suspend fun load(): List<DownloadRecord> = withContext(Dispatchers.IO) {
        if (loaded) return@withContext _records.value
        writeMutex.withLock {
            if (loaded) return@withContext _records.value
            val parsed = readFromDisk()
            _records.value = parsed.sortedByDescending { it.createdAt }
            loaded = true
            _records.value
        }
    }

    suspend fun upsert(record: DownloadRecord, durable: Boolean = false): Unit = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            ensureLoadedLocked()
            val next = _records.value.toMutableList()
            val index = next.indexOfFirst { it.id == record.id }
            if (index >= 0) next[index] = record else next.add(0, record)
            commitLocked(next.sortedByDescending { it.createdAt }, durable)
        }
    }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            ensureLoadedLocked()
            commitLocked(_records.value.filterNot { it.id == id })
        }
    }

    suspend fun replaceAll(records: List<DownloadRecord>): Unit = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            ensureLoadedLocked()
            commitLocked(records.sortedByDescending { it.createdAt })
        }
    }

    suspend fun clear(deleteFiles: Boolean): Unit = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            ensureLoadedLocked()
            if (deleteFiles) {
                _records.value.forEach { record ->
                    record.filePath?.let { runCatching { File(it).delete() } }
                    record.partialPath.takeIf { it.isNotBlank() }?.let { runCatching { File(it).delete() } }
                }
            }
            commitLocked(emptyList())
        }
    }

    /**
     * Anything left in RUNNING or PENDING belongs to a process that is no longer alive.
     * Demote it to PAUSED so the UI offers "continue" instead of showing a stuck progress bar.
     */
    suspend fun markInterruptedAsPaused(): Unit = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            ensureLoadedLocked()
            val now = System.currentTimeMillis()
            val next = _records.value.map { record ->
                if (record.status == DownloadStatus.RUNNING || record.status == DownloadStatus.PENDING) {
                    record.copy(
                        status = DownloadStatus.PAUSED,
                        updatedAt = now,
                        errorMessage = null,
                        // Distinguishes a crash restart from a deliberate pause so the
                        // auto-resume policy can pick this task back up.
                        pauseCause = DownloadPauseCause.PROCESS,
                    )
                } else {
                    record
                }
            }
            if (next != _records.value) commitLocked(next)
        }
    }

    private fun ensureLoadedLocked() {
        if (!loaded) {
            _records.value = readFromDisk()
            loaded = true
        }
    }

    private fun readFromDisk(): List<DownloadRecord> {
        if (!historyFile.exists()) return emptyList()
        return runCatching {
            val text = historyFile.readText()
            if (text.isBlank()) emptyList() else json.decodeFromString(listSerializer, text)
        }.onFailure { error ->
            Log.e(TAG, AppText.get(R.string.log_history_read_failed), error)
            // Preserve the unreadable file instead of silently destroying the user's data.
            runCatching { historyFile.renameTo(File(historyFile.parentFile, "${historyFile.name}.corrupt")) }
        }.getOrDefault(emptyList())
    }

    private fun commitLocked(next: List<DownloadRecord>, durable: Boolean = false) {
        historyFile.parentFile?.mkdirs()
        val temp = File(historyFile.parentFile, "${historyFile.name}.tmp")
        try {
            val bytes = json.encodeToString(listSerializer, next).toByteArray(Charsets.UTF_8)
            temp.outputStream().use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            // Both files have the same parent: rename replaces the old snapshot atomically.
            // Never delete the previous snapshot or copy over it after a failed rename.
            if (!temp.renameTo(historyFile)) throw IOException(AppText.get(R.string.diag_history_replace_failed))
            _records.value = next
        } catch (error: IOException) {
            if (durable) throw error
            // Existing task progress can continue in memory; metadata transactions require
            // the strict path above so they can roll back their associated file move.
            _records.value = next
            Log.e(TAG, AppText.get(R.string.log_history_write_failed), error)
        }
    }

    private companion object {
        const val TAG = "DownloadHistoryStore"
    }
}
