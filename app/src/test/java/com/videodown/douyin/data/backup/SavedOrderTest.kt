package com.videodown.douyin.data.backup

import com.videodown.douyin.data.local.DownloadHistoryStore
import com.videodown.douyin.data.repository.DownloadHistoryRepositoryImpl
import com.videodown.douyin.domain.model.DownloadRecord
import com.videodown.douyin.domain.model.DownloadStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 已存「自定义顺序」在设置备份里的往返。
 *
 * 分工：这里测**纯逻辑**（导出哪一段、清洗什么、恢复后落在哪）与一次跨 store 的落盘往返；
 * 「界面把顺序交给备份、再把备份交回界面」由设备用例 `BackupDeviceTest` 覆盖。
 */
class SavedOrderTest {

    private val root = TemporaryFolder()

    @get:Rule
    val tempFolder: TemporaryFolder = root

    private fun record(id: String, sortIndex: Long = 0, updatedAt: Long = 0) = DownloadRecord(
        id = id, awemeId = id, title = id, status = DownloadStatus.COMPLETED,
        downloadedBytes = 1, updatedAt = updatedAt, sortIndex = sortIndex,
    )

    private fun historyFile() = File(root.root, "download_history.json")

    @Test
    fun `only the manually ordered records are exported`() {
        // 从没排过的（0）不进备份；位置有空洞也按位置升序取。
        val records = listOf(record("x", 0), record("y", 5), record("z", 2))
        assertEquals(listOf("z", "y"), SavedOrder.of(records))
    }

    @Test
    fun `a library that was never reordered exports nothing`() {
        assertNull(SavedOrder.of(listOf(record("a"), record("b"))))
        assertNull(SavedOrder.of(emptyList()))
    }

    @Test
    fun `a hand-edited order is cleaned instead of trusted`() {
        assertEquals(listOf("a", "b", "c"), SavedOrder.sanitize(listOf(" a ", "", "a", "b", "  ", "c", "b")))
        assertNull(SavedOrder.sanitize(null))
        assertNull(SavedOrder.sanitize(emptyList()))
        assertNull("全是空串等于没带顺序", SavedOrder.sanitize(listOf(" ", "")))
        assertEquals(SavedOrder.MAX_ENTRIES, SavedOrder.sanitize((1..SavedOrder.MAX_ENTRIES + 10).map { "id$it" })!!.size)
    }

    @Test
    fun `restoring puts the saved order back and leaves later records after it`() {
        // 备份时：a,b,c 手动排过。
        val backup = listOf("a", "b", "c")
        // 之后用户又拖成了 b,c,a，并新增了 f 也排进了手动段。
        val now = listOf(record("b", 1), record("c", 2), record("a", 3), record("f", 4))
        assertEquals(listOf("b", "c", "a", "f"), SavedOrder.of(now))

        assertEquals(listOf("a", "b", "c", "f"), SavedOrder.restorableOrder(now, backup))
    }

    @Test
    fun `ids that no longer exist are dropped and the survivors keep the backup order`() {
        val now = listOf(record("a", 2), record("b", 1))
        assertEquals(listOf("b", "a"), SavedOrder.of(now))

        assertEquals(listOf("a", "b"), SavedOrder.restorableOrder(now, listOf("deleted", "a", "b")))
    }

    @Test
    fun `a restore that changes nothing reports nothing`() {
        val now = listOf(record("a", 1), record("b", 2), record("d", 0))
        assertNull(SavedOrder.restorableOrder(now, listOf("a", "b")))
    }

    @Test
    fun `a restore on another device finds no ids and leaves the order alone`() {
        val now = listOf(record("a", 1), record("b", 2))
        assertNull(SavedOrder.restorableOrder(now, listOf("other-device-1", "other-device-2")))
        assertNull(SavedOrder.restorableOrder(now, null))
    }

    @Test
    fun `a settings backup round trip restores the order through the real store`() = runBlocking {
        val repository = DownloadHistoryRepositoryImpl(DownloadHistoryStore(historyFile()))
        listOf(record("a"), record("b"), record("c")).forEach { repository.upsert(it) }
        repository.reorderLibrary(listOf("c", "a", "b"))

        val encoded = SettingsBackupCodec.encode(
            SettingsSnapshot(savedOrder = SavedOrder.of(repository.snapshot())),
        )

        // 备份之后用户又改了一次顺序。
        repository.reorderLibrary(listOf("b", "c", "a"))

        // 恢复走设置界面那条路：解码 → restorableOrder → reorderLibrary。
        val decoded = SettingsBackupCodec.decode(encoded)!!
        SavedOrder.restorableOrder(repository.snapshot(), decoded.savedOrder)?.let { repository.reorderLibrary(it) }

        val reopened = DownloadHistoryRepositoryImpl(DownloadHistoryStore(historyFile())).snapshot()
        assertEquals(listOf("c", "a", "b"), SavedOrder.of(reopened))
    }
}
