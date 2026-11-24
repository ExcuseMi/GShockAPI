package org.avmedia.gshockapi.io

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.casio.ConnectionSetup
import org.avmedia.gshockapi.casio.Outcome
import org.avmedia.gshockapi.casio.WatchChannel
import org.avmedia.gshockapi.casio.WatchLink
import org.avmedia.gshockapi.casio.WatchQueue
import org.avmedia.gshockapi.casio.WatchTransport
import org.avmedia.gshockapi.link.FakeGatt
import org.avmedia.gshockapi.link.JobOutcome
import org.avmedia.gshockapi.link.JobPriority
import org.avmedia.gshockapi.link.readyLink
import org.avmedia.gshockapi.link.spec
import org.avmedia.gshockapi.link.testLinks
import org.avmedia.gshockapi.log.TestLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The workout and tide transfers as jobs on the link: they give way to more urgent jobs at their boundaries. */
class TransferJobsTest {
    init { TestLog.install() }

    @get:Rule val timeout: Timeout = Timeout.seconds(30)

    @Test fun aTapDuringATideTransferGoesRightAfterThePieceOnTheAirAndEveryPieceArrives() = runBlocking {
        val record = ByteArray(163) { (it * 7).toByte() }
        val gatt = FakeGatt(writeMs = 3)
        val link = readyLink(testLinks(), "A", gatt)
        val inbox = Channel<WorkoutMsg>(Channel.UNLIMITED)
        val order = CopyOnWriteArrayList<String>()
        val tap = AtomicReference<Deferred<JobOutcome<Outcome>>?>(null)
        var got = 0
        gatt.onWrite = { f ->
            when {
                f.size == 5 && f[0].toInt() == 0 && f[1].toInt() == 0x23 -> {
                    inbox.trySend(WorkoutMsg(H5600Channel.CONVOY, byteArrayOf(0, 0, 0)))
                    inbox.trySend(WorkoutMsg(H5600Channel.CONVOY, byteArrayOf(2, 24, 0)))
                }
                f[0].toInt() == 5 -> {
                    val n = order.count { it.startsWith("piece") }
                    order += "piece $n"
                    got += f.size - 1
                    if (n == 3 && tap.get() == null) {
                        tap.set(link.submit(spec("tap", JobPriority.USER)) { exchange(GetSetMode.SET, byteArrayOf(0xC1.toByte())) })
                    }
                    if (got >= record.size) inbox.trySend(WorkoutMsg(H5600Channel.DRSP, byteArrayOf(0x09, 0x23)))
                }
                f.size == 1 && f[0] == 0xC1.toByte() -> order += "tap"
            }
        }
        val out = link.submit(spec("tide", JobPriority.BACKGROUND, 20_000)) {
            TideTransfer(JobWorkoutLink(this), inbox, chunkGapMs = 5).run(record).error
        }.await()
        assertEquals(JobOutcome.Done(null), out)
        assertTrue(tap.get()!!.await().ok)
        assertEquals("order: $order", order.indexOf("piece 3") + 1, order.indexOf("tap"))
        assertEquals((0 until 9).map { "piece $it" }, order.filter { it.startsWith("piece") })
    }

    /** A boundary only where the watch is between steps: after a closed block chain or a stored workout, never inside a chain. */
    @Test fun aWorkoutReadGivesWayOnlyAfterAClosedChainOrAStoredWorkout() = runTest {
        val inbox = Channel<WorkoutMsg>(Channel.UNLIMITED)
        val watch = FakeWatch(inbox, twoWorkouts = true)
        val counted = object : WorkoutLink by watch {
            override suspend fun boundary() {
                watch.events += "boundary"
            }
        }
        val result = WorkoutTransfer(counted, inbox, sink = { true }).run()
        assertEquals(null, result.error)
        val ev = watch.events.toList()
        val boundaries = ev.withIndex().filter { it.value == "boundary" }.map { it.index }
        assertEquals("two chains and one stored workout per workout: $ev", 6, boundaries.size)
        for (i in boundaries) {
            val lastBlockRead = ev.subList(0, i).indexOfLast { it.startsWith("read 31") || it.startsWith("read 32") }
            val closed = ev.subList(lastBlockRead, i).any { it.startsWith("close") }
            assertTrue("boundary at $i inside an open chain: $ev", closed)
        }
    }

    @Test fun aFrameOfAJobThatGaveTheLinkUpIsNotSent() = runBlocking {
        val gatt = FakeGatt()
        val link = readyLink(testLinks(), "A", gatt)
        val scope = CompletableDeferred<org.avmedia.gshockapi.link.JobScope>()
        link.submit(spec("done", JobPriority.COMMAND)) { scope.complete(this) }.await()
        val late = scope.await().post(GetSetMode.CONVOY, byteArrayOf(0x31)).await()
        assertTrue(!late.ok)
        assertTrue(gatt.writes().isEmpty())
    }

    /** Compatible with the LifeLog lock: a workout read that waits (settings exchange running) does not hold the link meanwhile. */
    @Test fun aWorkoutReadThatWaitsLeavesTheLinkFree() = runBlocking {
        val savedQueue = WatchLink.queue
        val savedSink = H5600WorkoutIO.sink
        val savedLink = H5600WorkoutIO.link
        val sent = CopyOnWriteArrayList<Int>()
        WatchLink.queue = WatchQueue(transport = WatchTransport { _: WatchChannel, f: ByteArray -> sent += f[0].toInt() and 0xFF; true })
        H5600WorkoutIO.sink = { true }
        H5600WorkoutIO.resetBackoff()
        H5600WorkoutIO.link = object : WorkoutLink {
            override fun drsp(data: ByteArray) {}

            override fun bulk(data: ByteArray) {
                if (data.size == 3 && data.all { it.toInt() == 0 }) H5600WorkoutIO.onDrsp("03 01")
            }
        }
        ConnectionSetup.reset()
        ConnectionSetup.onWatchSetupDone()
        val exchangeRuns = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val settings = async(Dispatchers.IO) { WatchExchange.settingsExchange { exchangeRuns.complete(Unit); release.await() } }
            exchangeRuns.await()
            val read = async(Dispatchers.IO) { H5600WorkoutIO.download(userAsked = true) }
            Thread.sleep(200)
            assertEquals("the settings write went out while the workout read waits", true, withTimeoutOrNull(2_000) { WatchLink.writeChecked(GetSetMode.SET, byteArrayOf(0x13)) })
            release.complete(Unit)
            settings.await()
            withTimeout(20_000) { read.await() }
        } finally {
            release.complete(Unit)
            WatchLink.queue = savedQueue
            H5600WorkoutIO.sink = savedSink
            H5600WorkoutIO.link = savedLink
            H5600WorkoutIO.resetBackoff()
            ConnectionSetup.reset()
            if (WatchExchange.mutex.isLocked) WatchExchange.mutex.unlock()
        }
        assertTrue(0x13 in sent)
    }
}
