package com.videodown.douyin.data.export

import com.videodown.douyin.R
import com.videodown.douyin.core.AppText
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.videodown.douyin.data.download.DownloadPaths
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.model.videoMimeType
import com.videodown.douyin.domain.repository.DownloadHistoryRepository
import com.videodown.douyin.domain.repository.FileExportRepository
import com.videodown.douyin.domain.repository.FileExportTargetUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files

class DocumentTreeExporter(
    private val resolver: ContentResolver,
    private val history: DownloadHistoryRepository,
    private val paths: DownloadPaths,
    private val maintenanceMutex: Mutex,
    private val journalDirectory: File,
) : FileExportRepository {
    // Guarded by maintenanceMutex; a live export is never a startup recovery candidate.
    private val activeJournals = mutableSetOf<File>()
    private fun pendingJournals() = journalDirectory.listFiles().orEmpty().filter { it.name.endsWith(".pending") }
    override suspend fun pendingCleanupCount(): Int = withContext(Dispatchers.IO) {
        maintenanceMutex.withLock { pendingJournals().count { it !in activeJournals } }
    }
    override suspend fun recoverInterruptedExports() = withContext(Dispatchers.IO) {
        maintenanceMutex.withLock { recoverLocked() }
    }
    private fun recoverLocked() {
        pendingJournals().filterNot { it in activeJournals }.forEach { pending ->
            // An unavailable old provider must not block exports to a newly selected tree.
            // Keep its independent journal and retry after the user restores access.
            runCatching {
                val target = Uri.parse(pending.readText())
                require(target.scheme == "content")
                val exists = try {
                    resolver.query(target, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() }
                        ?: throw IOException(AppText.get(R.string.export_read_last_target))
                } catch (_: java.io.FileNotFoundException) { false }
                if (exists) check(DocumentsContract.deleteDocument(resolver, target))
                check(pending.delete())
            }
        }
    }
    override suspend fun export(id: String, treeUri: String): Result<String> {
        var created: Uri? = null
        val journal = File(journalDirectory, java.util.UUID.randomUUID().toString() + ".pending")
        return try {
            val result = withContext(Dispatchers.IO) { maintenanceMutex.withLock {
                activeJournals.add(journal)
                recoverLocked()
                val tree = Uri.parse(treeUri)
                if (tree.scheme != "content" || !DocumentsContract.isTreeUri(tree)) {
                    throw FileExportTargetUnavailable(AppText.get(R.string.export_pick_folder_again))
                }
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val record = history.getById(id) ?: error(AppText.get(R.string.export_record_missing))
                require(record.status == DownloadStatus.COMPLETED) { AppText.get(R.string.export_not_completed) }
                val source = record.filePath?.let(::File) ?: error(AppText.get(R.string.export_local_file_missing))
                require(source.isFile && !Files.isSymbolicLink(source.toPath()) &&
                    !Files.isSymbolicLink(paths.downloadDir.toPath()) && source.canonicalFile.parentFile == paths.downloadDir.canonicalFile) { AppText.get(R.string.export_local_file_unusable) }
                val names = mutableSetOf<String>()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                try {
                    resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) names.add(cursor.getString(0).orEmpty())
                    } ?: throw FileExportTargetUnavailable(AppText.get(R.string.export_read_target_failed))
                } catch (error: java.io.FileNotFoundException) {
                    throw FileExportTargetUnavailable(AppText.get(R.string.export_target_unavailable), error)
                }
                val name = availableExportName(source.name, names)
                created = DocumentsContract.createDocument(resolver, parent, videoMimeType(source.extension), name)
                    ?: throw IOException(AppText.get(R.string.export_create_failed))
                val target = checkNotNull(created)
                journalDirectory.mkdirs()
                val temporary = File(journalDirectory, journal.name + ".new")
                temporary.outputStream().use { it.write(target.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
                check(temporary.renameTo(journal)) { AppText.get(R.string.export_journal_save_failed) }
                source.inputStream().use { input ->
                    val output = resolver.openOutputStream(target, "w") ?: throw IOException(AppText.get(R.string.export_write_failed))
                    output.use {
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            it.write(buffer, 0, count)
                        }
                        it.flush()
                    }
                }
                currentCoroutineContext().ensureActive()
                Result.success(target.toString())
            } }
            withContext(NonCancellable + Dispatchers.IO) {
                maintenanceMutex.withLock {
                    check(journal.delete()) { AppText.get(R.string.export_journal_finish_failed) }
                    activeJournals.remove(journal)
                }
            }
            result
        } catch (error: Exception) {
            // The destination is newly created, so cleanup never touches a preexisting file.
            withContext(NonCancellable + Dispatchers.IO) {
                maintenanceMutex.withLock { if (created == null) activeJournals.remove(journal) }
                created?.let { target ->
                    maintenanceMutex.withLock {
                        if (runCatching { DocumentsContract.deleteDocument(resolver, target) }.getOrDefault(false)) journal.delete()
                        activeJournals.remove(journal)
                    }
                }
            }
            if (error is CancellationException) throw error
            Result.failure(error)
        }
    }
}

internal fun availableExportName(name: String, existing: Set<String>): String {
    if (name !in existing) return name
    val extension = name.substringAfterLast('.', "")
    val stem = if (extension.isEmpty()) name else name.substringBeforeLast('.')
    var number = 2
    while (true) {
        val candidate = "$stem ($number)" + if (extension.isEmpty()) "" else ".$extension"
        if (candidate !in existing) return candidate
        number++
    }
}
