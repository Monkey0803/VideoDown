package com.videodown.douyin.data.local

import com.videodown.douyin.R
import com.videodown.douyin.core.AppText
import com.videodown.douyin.data.download.DownloadPaths
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import com.videodown.douyin.domain.repository.DownloadHistoryRepository
import com.videodown.douyin.domain.repository.LibraryMetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files

/** A small durable move journal lets startup finish a rename interrupted between file and history writes. */
class DownloadLibraryMetadataRepository(
    private val paths: DownloadPaths,
    private val history: DownloadHistoryRepository,
    private val privateDirectory: File,
    private val maintenanceMutex: Mutex,
    private val activeIds: () -> Set<String>,
) : LibraryMetadataRepository {
    @Serializable private data class Rename(val before: DownloadRecord, val after: DownloadRecord)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val journal get() = File(privateDirectory, "rename.json")
    override suspend fun update(id: String, title: String?, favorite: Boolean?, folder: String?, isPrivate: Boolean?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            maintenanceMutex.withLock {
                recoverLocked()
                val record = history.getById(id) ?: error(AppText.get(R.string.meta_record_missing))
                require(record.status == DownloadStatus.COMPLETED && id !in activeIds()) { AppText.get(R.string.meta_only_saved_files) }
                val name = title?.trim()
                require(name == null || validName(name, 80)) { AppText.get(R.string.meta_name_invalid) }
                val category = folder?.trim()
                require(category == null || category.isEmpty() || validName(category, 40)) { AppText.get(R.string.meta_category_invalid) }
                val original = record.filePath?.let(::File) ?: error(AppText.get(R.string.meta_local_file_missing))
                require(safe(original) && original.isFile) { AppText.get(R.string.meta_local_file_unusable) }
                var changed = record.copy(
                    title = name ?: record.title,
                    favorite = favorite ?: record.favorite,
                    folder = category ?: record.folder,
                    // The vault flag is the one整理 change that does not touch the file on disk.
                    isPrivate = isPrivate ?: record.isPrivate,
                )
                if (name != null && name != record.title) {
                    val fileName = paths.buildFileName(name, record.resolutionLabel,
                        "${record.platform.name.lowercase()}_${record.awemeId}_${record.id.take(8)}").substringBeforeLast('.') + "." + original.extension
                    val target = File(paths.downloadDir, fileName)
                    require(safe(target)) { AppText.get(R.string.meta_target_name_unusable) }
                    if (target != original) {
                        require(!target.exists()) { AppText.get(R.string.meta_target_exists) }
                        changed = changed.copy(fileName = fileName, filePath = target.absolutePath, partialPath = target.path + ".part")
                        val operation = Rename(record, changed)
                        withContext(NonCancellable) {
                            privateDirectory.mkdirs()
                            val temporary = File(privateDirectory, "rename.json.new")
                            temporary.writeText(json.encodeToString(Rename.serializer(), operation))
                            check(temporary.renameTo(journal)) { AppText.get(R.string.meta_journal_save_failed) }
                            if (!original.renameTo(target)) {
                                journal.delete()
                                error(AppText.get(R.string.meta_rename_failed))
                            }
                            try { history.upsertDurably(changed) }
                            catch (error: Exception) {
                                // Roll back ordinary persistence failures; a process kill leaves
                                // the durable journal so startup can finish the authorized move.
                                if (history.getById(record.id)?.filePath == original.absolutePath && target.renameTo(original)) journal.delete()
                                throw error
                            }
                            check(journal.delete()) { AppText.get(R.string.meta_journal_finish_failed) }
                        }
                        return@withLock
                    }
                }
                history.upsertDurably(changed)
            }
            Result.success(Unit)
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { Result.failure(error) }
    }
    override suspend fun recoverRenames() = withContext(Dispatchers.IO) { maintenanceMutex.withLock { recoverLocked() } }
    private suspend fun recoverLocked() {
        if (!journal.exists()) return
        val operation = json.decodeFromString(Rename.serializer(), journal.readText())
        val before = operation.before.filePath?.let(::File) ?: error(AppText.get(R.string.meta_rename_record_invalid))
        val after = operation.after.filePath?.let(::File) ?: error(AppText.get(R.string.meta_rename_record_invalid))
        require(safe(before) && safe(after) && operation.before.id == operation.after.id) { AppText.get(R.string.meta_rename_path_invalid) }
        val current = history.getById(operation.before.id)
        if (current == null) {
            // A deleted record must never be recreated. Preserve any leftover video for manual recovery.
            check(journal.delete()); return
        }
        require(current.status == DownloadStatus.COMPLETED && current.id !in activeIds()) { AppText.get(R.string.meta_file_in_use) }
        require(current.filePath != null && setOf(before.path, after.path).contains(current.filePath)) { AppText.get(R.string.meta_record_path_changed) }
        if (after.isFile && !before.exists()) history.upsertDurably(operation.after)
        else if (before.isFile && !after.exists()) {
            check(before.renameTo(after)) { AppText.get(R.string.meta_rename_restore_failed) }
            history.upsertDurably(operation.after)
        } else error(AppText.get(R.string.meta_rename_state_needs_check))
        check(journal.delete()) { AppText.get(R.string.meta_journal_clear_failed) }
    }
    private fun safe(file: File): Boolean = !Files.isSymbolicLink(paths.downloadDir.toPath()) &&
        !Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == paths.downloadDir.canonicalFile
    private fun validName(value: String, max: Int) = value.isNotBlank() && value.length <= max &&
        value.none { it.isISOControl() || it in "\\/:*?\"<>|" } && value !in setOf(".", "..")
}
