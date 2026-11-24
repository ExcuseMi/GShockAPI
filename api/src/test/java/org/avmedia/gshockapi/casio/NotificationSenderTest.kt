package org.avmedia.gshockapi.casio

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Notification texts: chunks of MTU - 4 (20 before the MTU is known), spaced, held back while the watch or a transfer says stop. */
class NotificationSenderTest {
    private val written = CopyOnWriteArrayList<Int>()
    private var transferRunning = false

    @Before fun install() {
        NotificationGate.reset()
        NotificationSender.spacingMs = 1
        NotificationSender.holdPollMs = 5
        NotificationSender.holdLimitMs = 2_000
        NotificationSender.busy = { transferRunning }
        NotificationSender.writer = { written += it.size; true }
    }

    @After fun restore() {
        NotificationGate.reset()
        NotificationSender.spacingMs = 2_000
        NotificationSender.holdPollMs = 500
        NotificationSender.holdLimitMs = 10 * 60_000L
        NotificationSender.busy = { org.avmedia.gshockapi.io.H5600HealthIO.isActive || org.avmedia.gshockapi.io.H5600WorkoutIO.isActive }
        NotificationSender.writer = { WatchLink.writeChecked(org.avmedia.gshockapi.ble.GetSetMode.NOTIFY, it) }
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 3_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out; written=$written" }
            Thread.sleep(5)
        }
    }

    @Test fun twentyByteChunksUntilTheMtuIsKnown() = runBlocking {
        NotificationSender.send(ByteArray(50)).join()
        assertEquals(listOf(20, 20, 10), written.toList())
    }

    @Test fun theChunkIsTheMtuFromTheConditionMinusFour() = runBlocking {
        NotificationGate.onCondition("0x28 00 00 00 00 B9 00 00 00 00")
        NotificationSender.send(ByteArray(200)).join()
        assertEquals(listOf(181, 19), written.toList())
    }

    @Test fun aFailedWriteDropsTheRest() = runBlocking {
        NotificationSender.writer = { written += it.size; false }
        NotificationSender.send(ByteArray(50)).join()
        assertEquals(listOf(20), written.toList())
    }

    @Test fun heldBackWhileTheWatchHasPausedItAndSentAfterTheStartCommand() {
        NotificationGate.onManager("0x3D 11")
        val job = NotificationSender.send(ByteArray(30))
        Thread.sleep(100)
        assertTrue(written.isEmpty())
        NotificationGate.onManager("0x3D 02")
        waitFor { written.size == 2 }
        runBlocking { job.join() }
        assertEquals(listOf(20, 10), written.toList())
    }

    @Test fun heldBackWhileATransferRunsAndSentAfterIt() {
        transferRunning = true
        val job = NotificationSender.send(ByteArray(10))
        Thread.sleep(100)
        assertTrue(written.isEmpty())
        transferRunning = false
        runBlocking { job.join() }
        assertEquals(listOf(10), written.toList())
    }

    @Test fun theValueOfOurOwnReadIsNotACommand() {
        NotificationGate.onManager("0x3D 30 00 02 01")
        assertTrue(NotificationGate.allowed())
    }

    // A plugin's text was reported "done" before anything was written; the caller now gets how it ended.
    @Test fun aSentTextSaysSent() = runBlocking {
        assertEquals(NotificationSender.Outcome.SENT, NotificationSender.send(ByteArray(30)).await())
    }

    @Test fun aFailedChunkSaysWriteFailed() = runBlocking {
        NotificationSender.writer = { written += it.size; false }
        assertEquals(NotificationSender.Outcome.WRITE_FAILED, NotificationSender.send(ByteArray(30)).await())
    }

    @Test fun aTextHeldTooLongSaysSo() = runBlocking {
        NotificationSender.holdLimitMs = 50
        NotificationGate.onManager("0x3D 11")
        assertEquals(NotificationSender.Outcome.HELD_TOO_LONG, NotificationSender.send(ByteArray(10)).await())
        assertTrue(written.isEmpty())
    }

    @Test fun anEmptyTextIsNotSent() = runBlocking {
        assertEquals(NotificationSender.Outcome.EMPTY, NotificationSender.send(ByteArray(0)).await())
    }
}
