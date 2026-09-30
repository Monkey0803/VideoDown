package com.videodown.douyin.core

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppErrorLanguageTest {
    @Test fun networkExceptionsDoNotExposeEnglishSystemMessages() {
        val cause = IOException("Unable to resolve host example.com")
        val error = cause.toAppError() as AppError.Network
        assertEquals("网络连接失败，请检查网络后重试", error.message)
        assertSame(cause, error.cause)
    }

    @Test fun unknownExceptionsDoNotExposeClassNamesOrEnglishMessages() {
        assertEquals("操作失败，请稍后重试", IllegalStateException("Unexpected response").toAppError().message)
        assertEquals("操作失败，请稍后重试", IllegalStateException().toAppError().message)
    }

    @Test fun galleryErrorsPreserveActionableCopyAndTranslateExternalFailures() {
        assertEquals("本地视频文件不存在", IllegalArgumentException("本地视频文件不存在").galleryErrorMessage())
        assertEquals("没有相册访问权限，请检查系统权限设置", SecurityException("Permission denied").galleryErrorMessage())
        assertEquals("请检查存储空间和相册权限后重试", IOException("No space left on device").galleryErrorMessage())
    }
}
