package com.videodown.douyin.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I5：共享带宽预算的账本。
 *
 * 时间由测试推进，所以能看到「一段突发欠下多少时间」「暂停花完之后还能不能再超」这类
 * 只在长时间运行里才暴露的行为。进程暂停本身由设备用例覆盖。
 */
class EngineBandwidthGovernorTest {
    private var clock = 1_000L
    private val governor = EngineBandwidthGovernor(now = { clock })

    private fun advance(ms: Long) { clock += ms }

    @Test fun `without a limit nothing is throttled`() {
        val session = governor.session("a")
        assertEquals(0L, session.onBytes(10_000_000L, globalRate = 0L, taskRate = 0L))
    }

    @Test fun `a burst beyond the bucket produces a pause proportional to the debt`() {
        val session = governor.session("a")
        // 512 KB/s 的桶容量是 64 KB：一次 256 KB 会欠下 192 KB ≈ 375 ms。
        val pause = session.onBytes(256L * 1024L, globalRate = 512L * 1024L, taskRate = 0L)
        assertEquals(375L, pause)
    }

    @Test fun `a single huge event pauses for as long as its debt needs`() {
        val session = governor.session("a")
        // 2 MB 一次上报、上限 256 KB/s（桶容量 32 KB）：债务约 7.875 s，
        // 必须真的等这么久，否则下一个事件还没来，实际速率就会持续超标。
        val pause = session.onBytes(2L * 1024L * 1024L, globalRate = 256L * 1024L, taskRate = 0L)
        assertEquals(7_875L, pause)
    }

    @Test fun `a pause never exceeds the configured cap`() {
        val session = governor.session("a")
        // 一个极小的上限 + 一大段突发：债务约 128 s，远大于安全阀，返回封顶值。
        assertEquals(
            EngineBandwidthGovernor.MAX_PAUSE_MS,
            session.onBytes(8L * 1024L * 1024L, globalRate = 64L * 1024L, taskRate = 0L),
        )
    }

    @Test fun `waiting the pause keeps the long run average at the cap`() {
        val session = governor.session("a")
        val rate = 512L * 1024L
        // 每 100ms 写入 51.2 KB，换算后略低于 512 KB/s：连续 10 次都不该暂停。
        repeat(10) {
            val pause = session.onBytes(51_200L, globalRate = rate, taskRate = 0L)
            assertTrue("steady state at the cap must not pause, got $pause", pause == 0L)
            advance(100L)
        }
    }

    @Test fun `the global bucket is shared by every session`() {
        val first = governor.session("a")
        val second = governor.session("b")
        // 第一个会话先吃掉整个桶，第二个会话的突发就只能等待。
        val firstPause = first.onBytes(64L * 1024L, globalRate = 512L * 1024L, taskRate = 0L)
        assertEquals(0L, firstPause)
        val secondPause = second.onBytes(64L * 1024L, globalRate = 512L * 1024L, taskRate = 0L)
        assertTrue("the second process must wait for the shared bucket, got $secondPause", secondPause > 0L)
    }

    @Test fun `a per task cap only affects its own session`() {
        val limited = governor.session("limited")
        val free = governor.session("free")
        val limitedPause = limited.onBytes(256L * 1024L, globalRate = 0L, taskRate = 256L * 1024L)
        assertTrue(limitedPause > 0L)
        assertEquals(0L, free.onBytes(256L * 1024L, globalRate = 0L, taskRate = 0L))
    }

    @Test fun `raising the limit mid-run takes effect on the next report`() {
        val session = governor.session("a")
        val slow = session.onBytes(256L * 1024L, globalRate = 256L * 1024L, taskRate = 0L)
        assertTrue("a low limit must pause", slow > 0L)
        // 暂停花完之后上限被调高：下一次上报按新上限补充令牌，债务立即还清。
        advance(slow)
        val fast = session.onBytes(256L * 1024L, globalRate = 8L * 1024L * 1024L, taskRate = 0L)
        assertEquals("the next report already uses the new limit", 0L, fast)
    }

    @Test fun `releasing a session drops its task bucket`() {
        val session = governor.session("a")
        assertTrue(session.onBytes(256L * 1024L, globalRate = 0L, taskRate = 128L * 1024L) > 0L)
        governor.release("a")
        // 重新注册后是一个满桶：同样的突发仍然只能借到容量内的部分。
        val fresh = governor.session("a")
        val pause = fresh.onBytes(8L * 1024L, globalRate = 0L, taskRate = 128L * 1024L)
        assertEquals("a new session starts with a full bucket", 0L, pause)
    }
}
