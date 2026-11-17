package com.videodown.douyin.data.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * I3：导出回执的判重条件与指纹。
 *
 * 这些判定决定「再点一次保存」是安静返回「已保存」还是真的写第二份，所以每条失效条件都要有
 * 独立断言：错一个就会在相册里留下同名重复，或者反过来把用户明确要求的第二份吞掉。
 */
class ExportReceiptTest {
    @get:Rule val temp = TemporaryFolder()

    private fun file(name: String, bytes: Int = 16, modifiedAt: Long = 1_000L): File =
        File(temp.root, name).apply {
            writeBytes(ByteArray(bytes) { 3 })
            setLastModified(modifiedAt)
        }

    @Test fun `fingerprint uses name size and modification time`() {
        val first = file("a.png", bytes = 16, modifiedAt = 1_000L)
        val base = ExportReceipts.fingerprint(listOf(first))
        assertEquals("a.png:16:1000", base)
        assertEquals(base, ExportReceipts.fingerprint(listOf(file("a.png", 16, 1_000L))))
        assertTrue("size change invalidates", base != ExportReceipts.fingerprint(listOf(file("a.png", 32, 1_000L))))
        assertTrue("mtime change invalidates", base != ExportReceipts.fingerprint(listOf(file("a.png", 16, 2_000L))))
        assertTrue("name change invalidates", base != ExportReceipts.fingerprint(listOf(file("b.png", 16, 1_000L))))
    }

    @Test fun `live photo fingerprint covers both files in order`() {
        val still = file("still.jpg", 16, 1_000L)
        val motion = file("still_motion.mov", 32, 2_000L)
        assertEquals(
            "still.jpg:16:1000|still_motion.mov:32:2000",
            ExportReceipts.fingerprint(listOf(still, motion)),
        )
        assertTrue(
            "replacing only the motion invalidates the pair",
            ExportReceipts.fingerprint(listOf(still, motion)) !=
                ExportReceipts.fingerprint(listOf(still, file("still_motion.mov", 64, 2_000L))),
        )
    }

    private val receipt = ExportReceipt("record", "a.png:16:1000", listOf("content://media/1"), 0L)

    @Test fun `a matching receipt whose target still exists means already saved`() {
        assertTrue(ExportReceipts.isValid(receipt, "record", "a.png:16:1000") { it == "content://media/1" })
    }

    @Test fun `a receipt for another record never matches`() {
        assertFalse(ExportReceipts.isValid(receipt, "other", "a.png:16:1000") { true })
    }

    @Test fun `a changed source file invalidates the receipt`() {
        assertFalse(ExportReceipts.isValid(receipt, "record", "a.png:32:1000") { true })
    }

    @Test fun `a deleted gallery target invalidates the receipt`() {
        assertFalse("user removed it from the gallery", ExportReceipts.isValid(receipt, "record", "a.png:16:1000") { false })
    }

    @Test fun `a live photo receipt needs every target to survive`() {
        val pair = receipt.copy(targets = listOf("content://media/1", "content://media/2"))
        assertTrue(ExportReceipts.isValid(pair, "record", "a.png:16:1000") { true })
        assertFalse(
            "deleting only the motion half must not claim the pair is saved",
            ExportReceipts.isValid(pair, "record", "a.png:16:1000") { it != "content://media/2" },
        )
    }

    @Test fun `an empty receipt is never valid`() {
        assertFalse(ExportReceipts.isValid(null, "record", "a.png:16:1000") { true })
        assertFalse(ExportReceipts.isValid(receipt.copy(targets = emptyList()), "record", "a.png:16:1000") { true })
    }

    @Test fun `receipts survive a JSON round trip`() {
        val json = kotlinx.serialization.json.Json
        val encoded = json.encodeToString(ExportReceipt.serializer(), receipt)
        val decoded = json.decodeFromString(ExportReceipt.serializer(), encoded)
        assertEquals(receipt, decoded)
        assertNotNull(decoded.targets.firstOrNull())
        assertNull(decoded.copy(targets = emptyList()).targets.firstOrNull())
    }
}
