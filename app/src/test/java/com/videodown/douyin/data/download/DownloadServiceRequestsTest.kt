package com.videodown.douyin.data.download

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DownloadServiceRequestsTest {
    @Test fun `separate starts require separate acknowledgements and cancelled tokens expire`() = runBlocking {
        val requests = DownloadServiceRequests()
        val tokens = mutableListOf<String>()
        val one = async(start = CoroutineStart.UNDISPATCHED) { requests.awaitStart { tokens.add(it) } }
        val two = async(start = CoroutineStart.UNDISPATCHED) { requests.awaitStart { tokens.add(it) } }
        requests.acknowledge(tokens[0]); one.await()
        assertFalse(two.isCompleted)
        two.cancelAndJoin()
        requests.acknowledge(tokens[1])
        requests.awaitStart { requests.acknowledge(it) }
    }

    @Test fun `startup denial and missing foreground acknowledgement fail explicitly`() = runBlocking {
        val requests = DownloadServiceRequests()
        try { requests.awaitStart { throw SecurityException("denied") }; fail("must report denial") }
        catch (error: DownloadServiceException) { assertTrue(error.cause is SecurityException) }
        try { requests.awaitStart(timeoutMs = 10) { }; fail("must time out") }
        catch (error: DownloadServiceException) { assertTrue(error.message!!.contains("超时")) }
    }

    @Test fun `foreground promotion failure releases all waiting starts`() = runBlocking {
        val requests = DownloadServiceRequests()
        val one = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { requests.awaitStart { } }.exceptionOrNull()
        }
        val two = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { requests.awaitStart { } }.exceptionOrNull()
        }
        requests.failPending(DownloadServiceException("前台服务不可用"))
        assertTrue(one.await() is DownloadServiceException)
        assertTrue(two.await() is DownloadServiceException)
    }
}
