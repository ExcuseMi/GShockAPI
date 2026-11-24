package org.avmedia.gshockapi.link

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.log.TestLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Two watches connected at once: each link has its own queue, setup and state. */
class TwoLinksTest {
    init { TestLog.install() }

    @get:Rule val timeout: Timeout = Timeout.seconds(15)

    private val links = testLinks()

    private fun transfer(link: WatchLink, tag: Int, n: Int = 30) = link.submit(spec("transfer", JobPriority.BACKGROUND, 20_000)) {
        var i = 0
        while (i < n) {
            if (exchange(GetSetMode.DATA_REQUEST, bytes(tag, i)).ok) i++
            yieldPoint()
        }
        n
    }

    @Test fun aTransferOnANeverDelaysAUserActionOnB() = runBlocking {
        val gattA = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", FakeGatt())
        // A's block stays on the air until the end of the test: anything B shares with A would hang behind it.
        val held = CompletableDeferred<Unit>()
        gattA.hold = held
        val onA = transfer(a, 0xA0)
        waitUntil("A's first block on the air") { gattA.writes().isNotEmpty() }
        val onB = transfer(b, 0xB0)
        val tap = b.submit(spec("tap", JobPriority.USER)) { exchange(GetSetMode.CONVOY, bytes(0xC1)) }
        assertTrue(withTimeout(2_000) { tap.await() }.ok)
        assertEquals(JobOutcome.Done(30), withTimeout(2_000) { onB.await() })
        assertEquals("A is still on its first block", listOf("w A0 00"), gattA.writes())
        held.complete(Unit)
        assertEquals(JobOutcome.Done(30), onA.await())
    }

    @Test fun aDropOfALeavesBAndItsQueueAlone() = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", gattB)
        val gate = CompletableDeferred<Unit>()
        val holding = CompletableDeferred<Unit>()
        val busyB = b.submit(spec("B busy", JobPriority.COMMAND, rule = OnDisconnect.CANCEL)) { holding.complete(Unit); gate.await() }
        holding.await()
        val waitingB = b.submit(spec("B find phone", JobPriority.USER, rule = OnDisconnect.CANCEL)) { exchange(GetSetMode.CONVOY, bytes(0x12)) }
        val waitingA = a.submit(spec("A alarm", JobPriority.USER, deadlineMs = 300)) { CompletableDeferred<Unit>().await() }
        a.onGattLost()
        assertEquals(LinkState.DISCONNECTED, a.state.value)
        assertEquals(LinkState.READY, b.state.value)
        assertEquals(2, b.jobs.pending())
        gate.complete(Unit)
        assertTrue(busyB.await().ok)
        assertTrue(waitingB.await().ok)
        assertTrue(waitingA.await() is JobOutcome.Failed)
        assertEquals(listOf("w 12"), gattB.writes())
    }

    @Test fun twoLinksRunInParallelWithNoSharedState() = runBlocking {
        val global = AtomicInteger()
        val gattA = FakeGatt(writeMs = 5, global = global)
        val gattB = FakeGatt(writeMs = 5, global = global)
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", gattB)
        assertNotSame(a.frames, b.frames)
        assertNotSame(a.jobs, b.jobs)
        val ta = transfer(a, 0xA0)
        val tb = transfer(b, 0xB0)
        assertEquals(JobOutcome.Done(30), ta.await())
        assertEquals(JobOutcome.Done(30), tb.await())
        assertEquals((0 until 30).map { "w A0 %02X".format(it) }, gattA.writes())
        assertEquals((0 until 30).map { "w B0 %02X".format(it) }, gattB.writes())
        assertEquals("one operation at a time per link", 1, gattA.maxInFlight.get())
        assertEquals(1, gattB.maxInFlight.get())
        assertEquals("the two links ran at the same time", 2, gattA.maxGlobal.get().coerceAtLeast(gattB.maxGlobal.get()))
    }

    @Test fun aLinkIsOpenedOncePerKeyAndClosingEndsOnlyItsJobs() = runBlocking {
        val a = readyLink(links, "A", FakeGatt())
        val b = readyLink(links, "B", FakeGatt())
        assertTrue(links.open("A", FakeGatt()) === a)
        val waitingA = a.submit(spec("A", JobPriority.USER)) { CompletableDeferred<Unit>().await() }
        links.close("A")
        val out = waitingA.await()
        assertTrue(out is JobOutcome.Failed && out.reason == FailReason.LINK_CLOSED)
        assertEquals(null, links["A"])
        assertTrue(b.submit(spec("B", JobPriority.USER)) { exchange(GetSetMode.CONVOY, bytes(0x11)) }.await().ok)
    }
}
