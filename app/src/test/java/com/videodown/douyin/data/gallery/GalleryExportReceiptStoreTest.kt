package com.videodown.douyin.data.gallery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * I3：回执落盘与自动失效。
 *
 * 用假的 `targetExists` 驱动：真实的 MediaStore 查询在设备用例里覆盖，这里只验证
 * 「写下去、重启后读回来、目标没了就自动作废、长期不用会被清理」这套状态机。
 */
class GalleryExportReceiptStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val existing = mutableSetOf("content://media/1")
    private var now = 1_000_000L

    private val file by lazy { File(temp.root, "export-receipts.json") }
    private val store by lazy {
        GalleryExportReceiptStore(file, targetExists = { it in existing }, now = { now })
    }

    @Test fun `a saved receipt is found again in a new store instance`() = runBlocking {
        store.save("record", "a.png:16:1000", listOf("content://media/1"))
        val reopened = GalleryExportReceiptStore(file, targetExists = { it in existing }, now = { now })
        assertNotNull("回执必须落盘，进程重启后还能认出已经保存过", reopened.find("record", "a.png:16:1000"))
    }

    @Test fun `deleting the gallery target invalidates and forgets the receipt`() = runBlocking {
        store.save("record", "a.png:16:1000", listOf("content://media/1"))
        existing.clear()
        assertNull(store.find("record", "a.png:16:1000"))
        assertNull("失效回执会被清掉，不会一直挡着重存", store.rawReceipt("record"))
    }

    @Test fun `changing the source file invalidates the receipt`() = runBlocking {
        store.save("record", "a.png:16:1000", listOf("content://media/1"))
        assertNull(store.find("record", "a.png:32:1000"))
        assertNull(store.rawReceipt("record"))
    }

    @Test fun `a live photo receipt is invalid as soon as one half disappears`() = runBlocking {
        store.save("record", "pair", listOf("content://media/1", "content://media/2"))
        existing.remove("content://media/2")
        assertNull(store.find("record", "pair"))
    }

    @Test fun `clear removes only the requested record`() = runBlocking {
        store.save("a", "fp-a", listOf("content://media/1"))
        existing.add("content://media/3")
        store.save("b", "fp-b", listOf("content://media/3"))
        store.clear("a")
        assertNull(store.find("a", "fp-a"))
        assertNotNull(store.find("b", "fp-b"))
    }

    @Test fun `receipts unused for ninety days are pruned`() = runBlocking {
        store.save("old", "fp-old", listOf("content://media/1"))
        now += 91L * 24L * 60L * 60L * 1000L
        existing.add("content://media/3")
        store.save("new", "fp-new", listOf("content://media/3"))
        assertNull("过期回执不应无限增长", store.rawReceipt("old"))
        assertNotNull(store.find("new", "fp-new"))
    }

    @Test fun `a corrupt receipts file is ignored instead of crashing saves`() = runBlocking {
        file.writeText("{ not json")
        assertNull(store.find("record", "fp"))
        store.save("record", "fp", listOf("content://media/1"))
        assertNotNull(store.find("record", "fp"))
        val reopened = GalleryExportReceiptStore(file, targetExists = { it in existing }, now = { now })
        assertEquals(1, reopened.find("record", "fp")?.targets?.size)
    }
}
