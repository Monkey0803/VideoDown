package com.videodown.douyin.data.local

import com.videodown.douyin.domain.model.*
import com.videodown.douyin.domain.repository.DownloadHistoryRepository
import com.videodown.douyin.domain.repository.StorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Only allowlisted app-owned temporary entries are removable; video files are never cleanup candidates. */
class DownloadStorageRepository(
    private val directory: () -> File,
    private val history: DownloadHistoryRepository,
    private val maintenanceMutex: Mutex,
    private val activeIds: () -> Set<String>,
    private val available: (File) -> Long = { it.usableSpace },
    /** Running-download reservation ledger; kept as a provider so the screen always sees it live. */
    private val budget: () -> StorageBudget = { StorageBudget() },
) : StorageRepository {
    override suspend fun inspect(): StorageUsage = withContext(Dispatchers.IO) {
        maintenanceMutex.withLock { inspectLocked() }
    }
    private suspend fun inspectLocked(): StorageUsage {
        val root = directory()
        if (Files.isSymbolicLink(root.toPath())) error("下载目录不可用")  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
        val records = history.snapshot()
        val active = activeIds()
        val protected = protectedPaths(records, root)
        var videoBytes = 0L
        var temporaryBytes = 0L
        val candidates = mutableListOf<StorageCleanupEntry>()
        root.listFiles().orEmpty().forEach { file ->
            if (!safe(root, file)) return@forEach
            val temporary = isTemporary(file)
            val info = fingerprint(root, file) ?: return@forEach
            if (temporary) temporaryBytes += info.bytes else videoBytes += info.bytes
            if (temporary && file.canonicalPath !in protected &&
                !(file.name.endsWith(".publishing") && active.isNotEmpty())) candidates += info
        }
        val missing = records.filter { it.status == DownloadStatus.COMPLETED && it.id !in active &&
            it.filePath?.let { path -> File(path).isFile } != true }.map { it.id }.toSet()
        return StorageUsage(videoBytes, temporaryBytes, available(root).coerceAtLeast(0), candidates, missing, budget = budget())
    }

    override suspend fun clean(plan: StorageUsage): StorageCleanupResult = withContext(Dispatchers.IO) {
        maintenanceMutex.withLock {
            // Refresh eligibility while enqueue/resume/cancel are excluded by the shared gate.
            val current = inspectLocked()
            val eligible = current.orphanEntries.associateBy { it.path }
            val root = directory()
            var freed = 0L; var entries = 0; var removed = 0; var skipped = 0; var failed = 0
            plan.orphanEntries.forEach { expected ->
                val now = eligible[expected.path]
                if (now == null || now.fingerprint != expected.fingerprint) { skipped++; return@forEach }
                val file = File(expected.path)
                // Recheck immediately before deletion, including every descendant and symbolic links.
                if (!safe(root, file) || fingerprint(root, file)?.fingerprint != expected.fingerprint) { skipped++; return@forEach }
                if (deleteTree(root, file)) { freed += expected.bytes; entries++ } else failed++
            }
            plan.missingRecordIds.forEach { id ->
                val record = history.getById(id)
                if (id !in current.missingRecordIds || record?.status != DownloadStatus.COMPLETED ||
                    id in activeIds() || record.filePath?.let { File(it).isFile } == true) skipped++
                else { history.delete(id); removed++ }
            }
            StorageCleanupResult(freed, entries, removed, skipped, failed)
        }
    }

    private fun protectedPaths(records: List<DownloadRecord>, root: File): Set<String> = buildSet {
        records.forEach { record ->
            record.filePath?.let { add(File(it).canonicalPath) }
            if (record.partialPath.isNotBlank()) add(File(record.partialPath).canonicalPath)
            if (record.fileName.isNotBlank()) {
                add(File(root, record.fileName).canonicalPath)
                add(File(root, record.fileName + ".part").canonicalPath)
                add(File(root, record.fileName + ".publishing").canonicalPath)
            }
            add(File(root, "engine-${record.id}").canonicalPath)
        }
        activeIds().forEach { add(File(root, "engine-$it").canonicalPath) }
    }
    private fun isTemporary(file: File): Boolean = if (file.isDirectory)
        file.name.matches(Regex("engine-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
        else file.name.endsWith(".part") || file.name.endsWith(".publishing")

    private fun safe(root: File, file: File): Boolean = !Files.isSymbolicLink(root.toPath()) &&
        !Files.isSymbolicLink(file.toPath()) && file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) && file.canonicalFile != root.canonicalFile

    private fun fingerprint(root: File, file: File): StorageCleanupEntry? {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        fun visit(entry: File, depth: Int): Boolean {
            if (depth > 24 || !safe(root, entry) || !entry.exists()) return false
            digest.update("${entry.absolutePath}:${entry.lastModified()}:${entry.length()}:${entry.isDirectory}\n".toByteArray())
            if (entry.isDirectory) {
                val children = entry.listFiles() ?: return false
                return children.sortedBy { it.name }.all { visit(it, depth + 1) }
            }
            if (!entry.isFile) return false
            bytes += entry.length(); return true
        }
        if (!visit(file, 0)) return null
        return StorageCleanupEntry(file.absolutePath, bytes, digest.digest().joinToString("") { "%02x".format(it) })
    }
    private fun deleteTree(root: File, file: File): Boolean {
        if (!safe(root, file)) return false
        if (file.isDirectory) {
            val children = file.listFiles() ?: return false
            if (children.any { !safe(root, it) }) return false
            if (!children.all { deleteTree(root, it) }) return false
        }
        return file.delete()
    }
}
