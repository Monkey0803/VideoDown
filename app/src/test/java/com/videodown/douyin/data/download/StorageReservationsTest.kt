package com.videodown.douyin.data.download

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class StorageReservationsTest {
    @Test fun `concurrent admission cannot promise the same bytes twice`() {
        val reservations = StorageReservations { 100L }
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val admitted = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { pool.submit {
                ready.countDown(); go.await()
                try { reservations.acquire(70L) { 0L }; admitted.incrementAndGet() }
                catch (_: InsufficientStorageException) { }
            } }
            ready.await(); go.countDown(); results.forEach { it.get() }
            assertEquals(1, admitted.get())
        } finally { pool.shutdownNow() }
    }
    @Test fun `written bytes reduce future reservation without double counting disk usage`() {
        var free = 100L
        var written = 0L
        val reservations = StorageReservations { free }
        val first = reservations.acquire(70) { written }
        written = 40; free = 60
        val second = reservations.acquire(30) { 0 }
        first.check()
        second.close()
        first.close()
        reservations.acquire(60) { 0 }.close()
    }
    @Test fun `actual size growth respects other reservations and failure leaves previous budget`() {
        val reservations = StorageReservations { 100 }
        val first = reservations.acquire(40) { 0 }
        val second = reservations.acquire(50) { 0 }
        try { first.updatePeak(60); fail("must reject enlarged budget") }
        catch (_: InsufficientStorageException) { }
        second.check()
        second.close()
        first.updatePeak(90)
        first.close(); first.close()
        reservations.acquire(100) { 0 }.close()
    }
    @Test fun `publishing bytes consume reserved copy space without double counting`() {
        var free = 100L
        var publishing = 0L
        val reservations = StorageReservations { free }
        val first = reservations.acquire(100) { 40 }
        first.includeUsage { publishing }
        publishing = 30; free = 70
        val second = reservations.acquire(40) { 0 }
        first.check()
        first.close(); second.close()
    }
    @Test fun `external space loss is detected and sums never overflow`() {
        var free = Long.MAX_VALUE
        val reservations = StorageReservations { free }
        val first = reservations.acquire(Long.MAX_VALUE - 1) { 0 }
        try { reservations.acquire(10) { 0 }; fail("must reject overflow") }
        catch (_: InsufficientStorageException) { }
        free = 1
        try { first.check(); fail("must detect external usage") }
        catch (_: InsufficientStorageException) { }
        first.close()
    }
}
