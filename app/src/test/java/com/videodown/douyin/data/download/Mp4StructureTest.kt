package com.videodown.douyin.data.download

import com.videodown.douyin.domain.model.ExpectedTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [Mp4Structure] 的纯 JVM 用例：用字节构造各种容器，不依赖 `MediaExtractor`。
 *
 * 覆盖下载成品最常遇到的三种残局：`moov` 缺失（非 faststart 截断）、`mdat` 声明长度越过
 * 文件末尾（faststart 截断）、以及正常文件；另外覆盖 64 位盒子长度与 `size == 0` 的末盒。
 */
class Mp4StructureTest {

    @get:Rule val temp = TemporaryFolder()

    private fun write(name: String, bytes: ByteArray): File =
        File(temp.root, name).apply { writeBytes(bytes) }

    private fun inspectFile(name: String, bytes: ByteArray) = Mp4Structure.inspect(write(name, bytes))

    @Test fun `a faststart mp4 with a video track is complete`() {
        val mp4 = mp4(box("moov", trak("vide")), box("mdat", ByteArray(16)))
        assertEquals(Mp4Structure.Complete(setOf(ExpectedTrack.VIDEO)), inspectFile("a.mp4", mp4))
    }

    @Test fun `both track types and extra box types are reported`() {
        val mp4 = mp4(
            box("free", ByteArray(4)),
            box("moov", trak("vide") + trak("soun")),
            box("mdat", ByteArray(128)),
            box("udta", ByteArray(2)),
        )
        assertEquals(Mp4Structure.Complete(setOf(ExpectedTrack.VIDEO, ExpectedTrack.AUDIO)), inspectFile("b.mp4", mp4))
    }

    @Test fun `non media handlers do not count as tracks`() {
        val mp4 = mp4(box("moov", trak("vide") + trak("text") + trak("hint")), box("mdat", ByteArray(8)))
        assertEquals(Mp4Structure.Complete(setOf(ExpectedTrack.VIDEO)), inspectFile("c.mp4", mp4))
    }

    @Test fun `an audio only container reports only audio`() {
        val mp4 = mp4(box("moov", trak("soun")), box("mdat", ByteArray(8)))
        assertEquals(Mp4Structure.Complete(setOf(ExpectedTrack.AUDIO)), inspectFile("d.mp4", mp4))
    }

    @Test fun `a truncated mdat is incomplete`() {
        val head = mp4(box("moov", trak("vide")), box("mdat", ByteArray(64)))
        // 声明 72 字节，实际只剩 40 字节：服务器提前断流的形态。
        assertEquals(Mp4Structure.Incomplete, inspectFile("e.mp4", head.copyOf(head.size - 24)))
    }

    @Test fun `a missing moov is incomplete`() {
        val tailFirst = ftyp() + box("mdat", ByteArray(32))
        assertEquals(Mp4Structure.Incomplete, inspectFile("f.mp4", tailFirst))
    }

    @Test fun `a container without media data is incomplete`() {
        assertEquals(Mp4Structure.Incomplete, inspectFile("g.mp4", mp4(box("moov", trak("vide")))))
    }

    @Test fun `a trak without a handler is incomplete`() {
        val broken = mp4(box("moov", box("trak", box("mdia", box("minf", ByteArray(4))))), box("mdat", ByteArray(8)))
        assertEquals(Mp4Structure.Incomplete, inspectFile("h.mp4", broken))
    }

    @Test fun `html or empty payloads are not mp4`() {
        assertEquals(Mp4Structure.NotMp4, inspectFile("i.mp4", "<html>error</html>".toByteArray()))
        assertEquals(Mp4Structure.NotMp4, inspectFile("j.mp4", ByteArray(0)))
        assertEquals(Mp4Structure.NotMp4, Mp4Structure.inspect(File(temp.root, "missing.mp4")))
    }

    @Test fun `ftyp must come first`() {
        val moovFirst = box("moov", trak("vide")) + box("mdat", ByteArray(8)) + ftyp()
        assertEquals(Mp4Structure.NotMp4, inspectFile("k.mp4", moovFirst))
    }

    @Test fun `a trailing box declared as size zero runs to the end of file`() {
        val openEnded = ftyp() + box("moov", trak("vide")) + box("mdat", ByteArray(0)).copyOfRange(0, 8)
        assertEquals(Mp4Structure.Complete(setOf(ExpectedTrack.VIDEO)), inspectFile("l.mp4", openEnded))
    }

    @Test fun `a 64 bit box size is understood`() {
        val mp4 = mp4(box("moov", trak("vide")), box("mdat", ByteArray(32), largeSize = true))
        assertEquals(Mp4Structure.Complete(setOf(ExpectedTrack.VIDEO)), inspectFile("m.mp4", mp4))
    }

    @Test fun `a 64 bit size beyond the file is incomplete`() {
        val mp4 = mp4(box("moov", trak("vide")), box("mdat", ByteArray(32), largeSize = true))
        val truncated = mp4.copyOf(mp4.size - 8)
        assertTrue(inspectFile("n.mp4", truncated) is Mp4Structure.Incomplete)
    }

    private fun ftyp(): ByteArray = box(
        "ftyp",
        "iso5".toByteArray() + ByteBuffer.allocate(4).putInt(0x200).array() + "iso6mp41".toByteArray(),
    )

    private fun mp4(vararg boxes: ByteArray): ByteArray = boxes.fold(ftyp()) { acc, box -> acc + box }

    private fun trak(handler: String): ByteArray =
        box("trak", box("mdia", box("hdlr", ByteArray(8) + handler.toByteArray())))

    private fun box(type: String, payload: ByteArray, largeSize: Boolean = false): ByteArray {
        require(type.length == 4)
        val buffer = ByteBuffer.allocate(if (largeSize) 16 else 8).order(ByteOrder.BIG_ENDIAN)
        if (largeSize) {
            buffer.putInt(1).put(type.toByteArray()).putLong(payload.size + 16L)
        } else {
            buffer.putInt(payload.size + 8).put(type.toByteArray())
        }
        return buffer.array() + payload
    }
}
