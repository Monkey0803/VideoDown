package com.videodown.douyin.data.gallery

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 导出回执的小型持久化：单个 JSON 文件，写入同目录 `.tmp` 后再原子改名。
 *
 * 与下载历史一样是「整份重写」：回执最多几十条，读一次写一次比引入数据库简单得多。
 * 目标是否还存在由构造时注入的 [targetExists] 判断（生产实现查 MediaStore / 文件系统，
 * JVM 用例传一个假实现），因此这里不依赖 Android 框架。
 */
class GalleryExportReceiptStore(
    private val file: File,
    private val targetExists: (String) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val listSerializer = ListSerializer(ExportReceipt.serializer())
    private val mutex = Mutex()
    private var loaded = false
    private val receipts = mutableMapOf<String, ExportReceipt>()

    /**
     * 这条记录「已经保存过」的回执；目标被删除或源文件已变时返回 null，并顺手丢掉过期回执。
     *
     * 失效即清理是「回执自动失效」的落点：下一次导出不会被一条指向空文件名的旧回执挡住。
     */
    suspend fun find(recordId: String, fingerprint: String): ExportReceipt? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val receipt = receipts[recordId] ?: return@withLock null
            if (ExportReceipts.isValid(receipt, recordId, fingerprint, targetExists)) {
                receipt
            } else {
                receipts.remove(recordId)
                persist()
                null
            }
        }
    }

    /** 写入（或替换）一条记录的回执，并清掉长期不用的旧条目。 */
    suspend fun save(recordId: String, fingerprint: String, targets: List<String>): Unit = withContext(Dispatchers.IO) {
        require(targets.isNotEmpty())
        mutex.withLock {
            ensureLoaded()
            val timestamp = now()
            receipts[recordId] = ExportReceipt(recordId, fingerprint, targets, timestamp)
            val cutoff = timestamp - RETENTION_MS
            receipts.values.removeAll { it.savedAt < cutoff }
            persist()
        }
    }

    /** 删除任务时清掉它的回执；目标文件本身属于用户，不在这里删除。 */
    suspend fun clear(recordId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            if (receipts.remove(recordId) != null) persist()
        }
    }

    /** 只读诊断入口（设置页与验证记录用），不改变失效判定。 */
    suspend fun rawReceipt(recordId: String): ExportReceipt? = withContext(Dispatchers.IO) {
        mutex.withLock { ensureLoaded(); receipts[recordId] }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val text = runCatching { if (file.isFile) file.readText() else null }.getOrNull() ?: return
        if (text.isBlank()) return
        val parsed = runCatching { json.decodeFromString(listSerializer, text) }.getOrNull() ?: return
        parsed.forEach { receipts[it.recordId] = it }
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            temp.outputStream().use { output ->
                output.write(json.encodeToString(listSerializer, receipts.values.sortedBy { it.recordId }).toByteArray())
                output.fd.sync()
            }
            if (!temp.renameTo(file)) throw IOException("receipt replace failed")
        } catch (_: IOException) {
            // 回执丢失只会让下一次导出多写一份，绝不能让「保存到相册」因此失败。
            temp.delete()
        }
    }

    private companion object {
        /** 90 天没用过的回执不再保留：它们对应的任务多半早已删除。 */
        const val RETENTION_MS = 90L * 24L * 60L * 60L * 1000L
    }
}
