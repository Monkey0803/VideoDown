package com.videodown.douyin.data.site

import com.videodown.douyin.domain.model.SiteWatchRecord
import com.videodown.douyin.domain.repository.SiteWatchHistoryRepository
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

class SiteWatchHistoryStore(
    private val file: File,
    private val limit: Int = 100,
) : SiteWatchHistoryRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val serializer = ListSerializer(SiteWatchRecord.serializer())
    private val mutex = Mutex()
    private val _records = MutableStateFlow<List<SiteWatchRecord>>(emptyList())
    override val records: StateFlow<List<SiteWatchRecord>> = _records.asStateFlow()
    private var loaded = false

    override suspend fun load(): List<SiteWatchRecord> = withContext(Dispatchers.IO) {
        mutex.withLock { ensureLoaded(); _records.value }
    }

    override suspend fun upsert(record: SiteWatchRecord): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val next = (listOf(record) + _records.value.filterNot { it.id == record.id })
                .sortedByDescending(SiteWatchRecord::updatedAt)
                .take(limit.coerceAtLeast(1))
            commit(next)
        }
    }

    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { ensureLoaded(); commit(_records.value.filterNot { it.id == id }) }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { ensureLoaded(); commit(emptyList()) }
    }

    override suspend fun find(id: String): SiteWatchRecord? = load().firstOrNull { it.id == id }

    private fun ensureLoaded() {
        if (loaded) return
        _records.value = if (!file.isFile) emptyList() else runCatching {
            file.readText().takeIf(String::isNotBlank)?.let { json.decodeFromString(serializer, it) }.orEmpty()
        }.getOrElse {
            runCatching { file.renameTo(File(file.parentFile, "${file.name}.corrupt")) }
            emptyList()
        }.sortedByDescending(SiteWatchRecord::updatedAt).take(limit.coerceAtLeast(1))
        loaded = true
    }

    private fun commit(next: List<SiteWatchRecord>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().use { output ->
            output.write(json.encodeToString(serializer, next).toByteArray())
            output.fd.sync()
        }
        if (!temporary.renameTo(file)) throw IOException("Unable to replace site watch history")
        _records.value = next
    }
}
