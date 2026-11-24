package org.avmedia.gshockapi.link

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.casio.Outcome
import org.avmedia.gshockapi.log.TestLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The job queue of one link: priority, jump-ahead during a transfer, deadlines and drops. */
class WatchJobQueueTest {
    init { TestLog.install() }

    @get:Rule val timeout: Timeout = Timeout.seconds(15)

    private val links = testLinks()

    private fun blocks(w: List<String>, tag: String) = w.filter { it.startsWith("w $tag") }

    private fun expectedBlocks(tag: String, n: Int) = (0 until n).map { "w $tag %02X".format(it) }

    /** A transfer of [n] blocks that yields at every block boundary; a block that failed (the link dropped) is read again. */
    private fun transfer(link: WatchLink, n: Int = 30, tag: Int = 0xB0, priority: JobPriority = JobPriority.BACKGROUND) =
        link.submit(spec("transfer", priority, 20_000)) {
            var i = 0
            while (i < n) {
                if (exchange(GetSetMode.DATA_REQUEST, bytes(tag, i)).ok) i++
                yieldPoint()
            }
            n
        }

    @Test fun waitingJobsRunByPriorityThenInTheOrderAsked() = runBlocking {
        val gatt = FakeGatt()
        val link = readyLink(links, "A", gatt)
        val gate = CompletableDeferred<Unit>()
        val holding = CompletableDeferred<Unit>()
        val blocker = link.submit(spec("blocker", JobPriority.COMMAND)) { holding.complete(Unit); gate.await() }
        holding.await()
        val asked = listOf(
            JobPriority.BACKGROUND to 0x41, JobPriority.COMMAND to 0x31, JobPriority.NOTIFICATION to 0x21,
            JobPriority.USER to 0x11, JobPriority.COMMAND to 0x32, JobPriority.USER to 0x12, JobPriority.NOTIFICATION to 0x22,
        ).map { (p, tag) -> link.submit(spec("job $tag", p)) { exchange(GetSetMode.CONVOY, bytes(tag)) } }
        gate.complete(Unit)
        asked.awaitAll()
        blocker.await()
        assertEquals(listOf("w 11", "w 12", "w 21", "w 22", "w 31", "w 32", "w 41"), gatt.writes())
    }

    @Test fun sourcesOfOnePriorityTakeTurns() = runBlocking {
        val gatt = FakeGatt()
        val link = readyLink(links, "A", gatt)
        val gate = CompletableDeferred<Unit>()
        val holding = CompletableDeferred<Unit>()
        link.submit(spec("blocker", JobPriority.COMMAND)) { holding.complete(Unit); gate.await() }
        holding.await()
        fun ask(plugin: String, tag: Int) =
            link.submit(JobSpec("$plugin $tag", JobSource.Plugin(plugin), JobPriority.COMMAND)) { exchange(GetSetMode.CONVOY, bytes(tag)) }
        val asked = listOf(ask("a", 0xA1), ask("a", 0xA2), ask("a", 0xA3), ask("a", 0xA4), ask("b", 0xB1), ask("b", 0xB2), ask("c", 0xC1))
        gate.complete(Unit)
        asked.awaitAll()
        assertEquals(listOf("w A1", "w B1", "w C1", "w A2", "w B2", "w A3", "w A4"), gatt.writes())
    }

    @Test fun aSourceThatComesLateIsNotPutBehindEveryJobOfABusyOne() = runBlocking {
        val gatt = FakeGatt(writeMs = 3)
        val link = readyLink(links, "A", gatt)
        val late = AtomicReference<Deferred<JobOutcome<Outcome>>?>(null)
        gatt.onWrite = { f ->
            if (f[0] == 0x03.toByte() && late.get() == null) {
                late.set(link.submit(JobSpec("b", JobSource.Plugin("b"), JobPriority.COMMAND)) { exchange(GetSetMode.CONVOY, bytes(0xB1)) })
            }
        }
        val busy = (1..20).map { n ->
            link.submit(JobSpec("a $n", JobSource.Plugin("a"), JobPriority.COMMAND)) { exchange(GetSetMode.CONVOY, bytes(n)) }
        }
        busy.awaitAll()
        late.get()!!.await()
        val w = gatt.writes()
        assertEquals("b runs right after the job of a that was on the air: $w", w.indexOf("w 03") + 1, w.indexOf("w B1"))
    }

    @Test fun aUserActionDuringA30BlockTransferRunsWithinOneBlockAndTheTransferResumesWithoutLoss() = runBlocking {
        val gatt = FakeGatt(writeMs = 5)
        val link = readyLink(links, "A", gatt)
        val tap = AtomicReference<Deferred<JobOutcome<Outcome>>?>(null)
        gatt.onWrite = { f ->
            if (f[0] == 0xB0.toByte() && f[1] == 5.toByte() && tap.get() == null) {
                tap.set(link.submit(spec("tap", JobPriority.USER)) { exchange(GetSetMode.CONVOY, bytes(0xC1)) })
            }
        }
        assertEquals(JobOutcome.Done(30), transfer(link).await())
        assertTrue(tap.get()!!.await().ok)
        val w = gatt.writes()
        assertEquals("the tap goes right after the block that was on the air: $w", w.indexOf("w B0 05") + 1, w.indexOf("w C1"))
        assertEquals(expectedBlocks("B0", 30), blocks(w, "B0"))
    }

    @Test fun aNotificationAlsoGoesBetweenBlocksButAJobOfTheSamePriorityWaitsForTheTransfer() = runBlocking {
        val gatt = FakeGatt(writeMs = 3)
        val link = readyLink(links, "A", gatt)
        val asked = AtomicReference<List<Deferred<JobOutcome<Outcome>>>?>(null)
        gatt.onWrite = { f ->
            if (f[0] == 0xB0.toByte() && f[1] == 10.toByte() && asked.get() == null) {
                asked.set(listOf(
                    link.submit(spec("later read", JobPriority.BACKGROUND)) { exchange(GetSetMode.CONVOY, bytes(0xC4)) },
                    link.submit(spec("notification", JobPriority.NOTIFICATION)) { exchange(GetSetMode.CONVOY, bytes(0xC2)) },
                ))
            }
        }
        transfer(link).await()
        asked.get()!!.awaitAll()
        val w = gatt.writes()
        assertEquals(w.indexOf("w B0 0A") + 1, w.indexOf("w C2"))
        assertEquals("the background job asked later runs after the transfer: $w", w.size - 1, w.indexOf("w C4"))
    }

    @Test fun aJobWaitsForAnAbsentWatchUpToItsDeadlineThenFailsWithTheReason() = runBlocking {
        val gatt = FakeGatt()
        val link = readyLink(links, "A", gatt)
        link.onGattLost()
        val out = link.submit(spec("alarm change", JobPriority.USER, deadlineMs = 300)) { exchange(GetSetMode.CONVOY, bytes(0x11)) }.await()
        assertTrue(out is JobOutcome.Failed && out.reason == FailReason.DEADLINE && "not connected" in out.detail)
        assertTrue(gatt.writes().isEmpty())
    }

    @Test fun aJobAskedWhileTheWatchIsAwayRunsWhenItIsBackAfterTheSetup() = runBlocking {
        val gatt = FakeGatt()
        val link = readyLink(links, "A", gatt)
        link.onGattLost()
        gatt.ops.clear()
        val job = link.submit(spec("alarm change", JobPriority.USER)) { exchange(GetSetMode.CONVOY, bytes(0x11)) }
        Thread.sleep(50)
        assertTrue(gatt.ops.isEmpty())
        link.onGattReady()
        assertTrue(withTimeout(3_000) { job.await() }.ok)
        assertEquals(listOf("sub 0", "sub 1", "sub 2", "w 11"), gatt.ops.toList())
    }

    @Test fun aDropEndsTheJobsThatCancelOnDisconnectWaitingOrRunning() = runBlocking {
        val gatt = FakeGatt()
        val link = readyLink(links, "A", gatt)
        val holding = CompletableDeferred<Unit>()
        val running = link.submit(spec("live view", JobPriority.COMMAND, rule = OnDisconnect.CANCEL)) {
            holding.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
        holding.await()
        val waitingCancel = link.submit(spec("find phone", JobPriority.USER, rule = OnDisconnect.CANCEL)) { exchange(GetSetMode.CONVOY, bytes(0x12)) }
        val waitingStay = link.submit(spec("alarm", JobPriority.USER, deadlineMs = 400)) { exchange(GetSetMode.CONVOY, bytes(0x13)) }
        link.onGattLost()
        val r = withTimeout(1_000) { running.await() }
        val c = withTimeout(1_000) { waitingCancel.await() }
        assertTrue("running: $r", r is JobOutcome.Failed && r.reason == FailReason.DISCONNECTED)
        assertTrue("waiting: $c", c is JobOutcome.Failed && c.reason == FailReason.DISCONNECTED)
        val s = waitingStay.await()
        assertTrue("the WAIT job waited until its deadline: $s", s is JobOutcome.Failed && s.reason == FailReason.DEADLINE)
        assertTrue(gatt.writes().isEmpty())
    }

    @Test fun aJobThatNeverEndsEndsAtItsDeadlineAndTheNextOneRuns() = runBlocking {
        val gatt = FakeGatt()
        val link = readyLink(links, "A", gatt)
        val stuck = link.submit(spec("stuck", JobPriority.USER, deadlineMs = 200)) { CompletableDeferred<Unit>().await() }
        val next = link.submit(spec("next", JobPriority.COMMAND)) { exchange(GetSetMode.CONVOY, bytes(0x31)) }
        val s = stuck.await()
        assertTrue(s is JobOutcome.Failed && s.reason == FailReason.DEADLINE && "did not finish" in s.detail)
        assertTrue(next.await().ok)
        assertEquals(0, link.jobs.pending())
    }

    @Test fun aTransferThatLosesTheLinkGoesOnAfterTheReconnectSetupWithoutLosingABlock() = runBlocking {
        val gatt = FakeGatt(writeMs = 3)
        val link = readyLink(links, "A", gatt)
        gatt.ops.clear()
        gatt.onWrite = { f ->
            if (f[0] == 0xB0.toByte() && f[1] == 12.toByte() && !gatt.down && gatt.ops.none { it.startsWith("sub") }) {
                gatt.down = true
                link.onGattLost()
                Thread {
                    Thread.sleep(50)
                    gatt.down = false
                    link.onGattReady()
                }.start()
            }
        }
        assertEquals(JobOutcome.Done(30), transfer(link).await())
        val ops = gatt.ops.toList()
        val okBlocks = ops.filter { it.startsWith("w B0") }.distinct()
        assertEquals(expectedBlocks("B0", 30), okBlocks)
        assertTrue("the setup ran before block 12 was read again: $ops", ops.lastIndexOf("sub 2") < ops.lastIndexOf("w B0 0C"))
        assertEquals(1, gatt.maxInFlight.get())
    }

    /** The quiet before a transfer start: the watch refused a start right after a command or a transfer (`03 01`). */
    @Test fun aQuietJobWaitsForTheSettleOfTheJobBeforeItAndOtherJobsRunMeanwhile() = runBlocking {
        val gatt = FakeGatt()
        val link = readyLink(links, "A", gatt)
        link.submit(spec("command", JobPriority.COMMAND).copy(settleMs = 400)) { exchange(GetSetMode.CONVOY, bytes(0x31)) }.await()
        val ended = System.currentTimeMillis()
        val quiet = link.submit(spec("transfer", JobPriority.BACKGROUND).copy(startsQuiet = true)) { System.currentTimeMillis() }
        val other = link.submit(spec("other", JobPriority.BACKGROUND)) { exchange(GetSetMode.CONVOY, bytes(0x41)); System.currentTimeMillis() }
        val otherAt = (other.await() as JobOutcome.Done).value
        val quietAt = (quiet.await() as JobOutcome.Done).value
        assertTrue("the other job ran in the quiet", otherAt < quietAt)
        assertTrue("the transfer started ${quietAt - ended} ms after the command", quietAt - ended >= 390)
    }

    /** Only the first turn waits: a transfer that gave way to a tap goes on at once after it. */
    @Test fun aQuietJobThatGaveWayGoesOnWithoutWaitingAgain() = runBlocking {
        val gatt = FakeGatt()
        val link = readyLink(links, "A", gatt)
        val tap = AtomicReference<Deferred<JobOutcome<Outcome>>?>(null)
        val started = System.currentTimeMillis()
        val out = link.submit(spec("transfer", JobPriority.BACKGROUND).copy(startsQuiet = true, settleMs = 2_000)) {
            for (i in 0 until 10) {
                exchange(GetSetMode.DATA_REQUEST, bytes(0xB0, i))
                if (i == 3) tap.set(link.submit(spec("tap", JobPriority.USER).copy(settleMs = 2_000)) { exchange(GetSetMode.SET, bytes(0x11)) })
                yieldPoint()
            }
        }.await()
        assertTrue(out.ok)
        assertTrue(tap.get()!!.await().ok)
        assertTrue("the transfer waited after the tap", System.currentTimeMillis() - started < 1_500)
        assertEquals("the tap right after block 3", gatt.writes().indexOf("w B0 03") + 1, gatt.writes().indexOf("w 11"))
    }
}
