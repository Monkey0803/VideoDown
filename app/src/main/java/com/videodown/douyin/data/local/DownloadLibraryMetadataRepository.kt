package com.videodown.douyin.data.local

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
                val record = history.getById(id) ?: error("记录已不存在")  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
                require(record.status == DownloadStatus.COMPLETED && id !in activeIds()) { "只可整理已保存文件" }  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
                val name = title?.trim()
                require(name == null || validName(name, 80)) { "名称不能为空，且不能包含路径分隔符或控制字符" }  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
                val category = folder?.trim()
                require(category == null || category.isEmpty() || validName(category, 40)) { "分类名称不可用" }  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
                val original = record.filePath?.let(::File) ?: error("本地文件不存在")  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
                require(safe(original) && original.isFile) { "本地文件不可用" }  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
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
                    require(safe(target)) { "文件名称不可用" }  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
                    if (target != original) {
                        require(!target.exists()) { "同名文件已存在" }  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
                        changed = changed.copy(fileName = fileName, filePath = target.absolutePath, partialPath = target.path + ".part")
                        val operation = Rename(record, changed)
                        withContext(NonCancellable) {
                            privateDirectory.mkdirs()
                            val temporary = File(privateDirectory, "rename.json.new")
                            temporary.writeText(json.encodeToString(Rename.serializer(), operation))
                            check(temporary.renameTo(journal)) { "无法保存重命名事务" }  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
                            if (!original.renameTo(target)) {
                                journal.delete()
                                error("无法重命名文件")  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
                            }
                            try { history.upsertDurably(changed) }
                            catch (error: Exception) {
                                // Roll back ordinary persistence failures; a process kill leaves
                                // the durable journal so startup can finish the authorized move.
                                if (history.getById(record.id)?.filePath == original.absolutePath && target.renameTo(original)) journal.delete()
                                throw error
                            }
                            check(journal.delete()) { "无法完成重命名事务" }  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
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
        val before = operation.before.filePath?.let(::File) ?: error("重命名记录无效")  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
        val after = operation.after.filePath?.let(::File) ?: error("重命名记录无效")  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
        require(safe(before) && safe(after) && operation.before.id == operation.after.id) { "重命名路径无效" }  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
        val current = history.getById(operation.before.id)
        if (current == null) {
            // A deleted record must never be recreated. Preserve any leftover video for manual recovery.
            check(journal.delete()); return
        }
        require(current.status == DownloadStatus.COMPLETED && current.id !in activeIds()) { "文件仍被任务使用" }  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
        require(current.filePath != null && setOf(before.path, after.path).contains(current.filePath)) { "记录路径已经改变" }  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
        if (after.isFile && !before.exists()) history.upsertDurably(operation.after)
        else if (before.isFile && !after.exists()) {
            check(before.renameTo(after)) { "无法恢复重命名" }  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
            history.upsertDurably(operation.after)
        } else error("重命名文件状态需要检查")  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
        check(journal.delete()) { "无法清除重命名事务" }  // l10n-ignore: 内部不变量：断言失败只会以统一的「操作失败」呈现给用户，不是界面文案
    }
    private fun safe(file: File): Boolean = !Files.isSymbolicLink(paths.downloadDir.toPath()) &&
        !Files.isSymbolicLink(file.toPath()) && file.canonicalFile.parentFile == paths.downloadDir.canonicalFile
    private fun validName(value: String, max: Int) = value.isNotBlank() && value.length <= max &&
        value.none { it.isISOControl() || it in "\\/:*?\"<>|" } && value !in setOf(".", "..")
}
