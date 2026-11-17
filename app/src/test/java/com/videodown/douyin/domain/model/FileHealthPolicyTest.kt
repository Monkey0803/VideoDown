package com.videodown.douyin.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * I4：已存文件的低成本健康判定。
 *
 * 这里的每个分支都对应界面上的一个结论，所以「旧记录没有快照」与「文件被改过」必须分开：
 * 前者只能报告未知，后者才值得深入校验，任何一侧缺失都不能被当成损坏证据。
 */
class FileHealthPolicyTest {

    @Test fun `a missing file is reported before anything else`() {
        assertEquals(
            StoredFileHealth.MISSING,
            FileHealthPolicy.cheapHealth(false, 10L, 1_000L, null, null),
        )
    }

    @Test fun `a matching snapshot is healthy`() {
        assertEquals(
            StoredFileHealth.HEALTHY,
            FileHealthPolicy.cheapHealth(true, 10L, 1_000L, 10L, 1_000L),
        )
    }

    @Test fun `an old record without a snapshot is only known to exist`() {
        assertEquals(StoredFileHealth.UNKNOWN, FileHealthPolicy.cheapHealth(true, null, null, 10L, 1_000L))
    }

    @Test fun `a different size means the file was replaced or written incompletely`() {
        assertEquals(StoredFileHealth.CHANGED, FileHealthPolicy.cheapHealth(true, 10L, 1_000L, 20L, 1_000L))
    }

    @Test fun `a different modification time is enough to distrust the file`() {
        assertEquals(StoredFileHealth.CHANGED, FileHealthPolicy.cheapHealth(true, 10L, 1_000L, 10L, 2_000L))
    }

    @Test fun `an unreadable actual size is not treated as corruption`() {
        assertEquals(StoredFileHealth.UNKNOWN, FileHealthPolicy.cheapHealth(true, 10L, null, null, null))
    }

    @Test fun `a failed structure check always reports corruption`() {
        assertEquals(StoredFileHealth.CORRUPT, FileHealthPolicy.afterStructureCheck(StoredFileHealth.HEALTHY, false))
        assertEquals(StoredFileHealth.CORRUPT, FileHealthPolicy.afterStructureCheck(StoredFileHealth.UNKNOWN, false))
        assertEquals(StoredFileHealth.CORRUPT, FileHealthPolicy.afterStructureCheck(StoredFileHealth.CHANGED, false))
    }

    @Test fun `a passed structure check keeps the cheap verdict`() {
        assertEquals(StoredFileHealth.HEALTHY, FileHealthPolicy.afterStructureCheck(StoredFileHealth.HEALTHY, true))
        assertEquals(StoredFileHealth.CHANGED, FileHealthPolicy.afterStructureCheck(StoredFileHealth.CHANGED, true))
        assertEquals(StoredFileHealth.UNKNOWN, FileHealthPolicy.afterStructureCheck(StoredFileHealth.UNKNOWN, true))
    }
}
