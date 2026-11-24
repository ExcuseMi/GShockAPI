package org.avmedia.gshockapi.io

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.casio.ConnectionSetup
import org.avmedia.gshockapi.casio.Outcome
import org.avmedia.gshockapi.casio.WatchChannel
import org.avmedia.gshockapi.casio.WatchLink
import org.avmedia.gshockapi.casio.WatchQueue
import org.avmedia.gshockapi.casio.WatchTransport
import org.avmedia.gshockapi.link.JobOutcome
import org.avmedia.gshockapi.link.JobPriority
import org.avmedia.gshockapi.link.JobSource
import org.avmedia.gshockapi.link.JobSpec
import org.avmedia.gshockapi.log.TestLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * A watch that streams a LifeLog of [blocks] bulk blocks: after START it is ready at once, takes the parameter exchange,
 * announces the index, sends one block per acknowledgement (`07`), then END. [onAck] runs on each acknowledgement.
 */
private class StreamingWatch(private val blocks: Int, private val onAck: (Int) -> Unit) : WatchTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val events = CopyOnWriteArrayList<String>()
    @Volatile private var sixSeen = 0
    @Volatile private var acks = 0

    private fun later(block: () -> Unit) {
        scope.launch {
            delay(EVENT_MS)
            block()
        }
    }

    private fun hex(b: List<Int>) = b.joinToString(" ") { "%02X".format(it) }

    private fun status(bytes: List<Int>) {
        WatchLink.onNotification(CasioConstants.CASIO_ALL_FEATURES_CHARACTERISTIC_UUID, hex(bytes))
        if (H5600HealthIO.isAwaitingSetupReply()) H5600HealthIO.onStatus(hex(bytes))
    }

    private fun convoy(vararg b: Int) = H5600HealthIO.onConvoy(hex(b.toList()))

    private fun drsp(vararg b: Int) = H5600HealthIO.onDrsp(hex(b.toList()))

    private fun block(n: Int) = later { H5600HealthIO.onConvoy(hex(listOf(0x05, n) + List(18) { (n * 3 + it) and 0xFF })) }

    override suspend fun write(channel: WatchChannel, frame: ByteArray): Boolean {
        delay(EVENT_MS)
        val f = frame.map { it.toInt() and 0xFF }
        when (channel) {
            WatchChannel.READ_REQUEST -> later {
                status(if (f[0] == 0x28) listOf(0x28, 0x06, 0x18, 0, 0, 0xFA, 0, 0, 0x04, 0) else listOf(f[0], 0x40, 0x1F) + List(17) { 0 })
            }
            WatchChannel.DATA_REQUEST -> when {
                f[0] == 0x00 && f[1] == 0x2E && f[2] == 0xFF -> if (sixSeen >= 2) later { drsp(0x00, 0x2E, 0x01, (blocks * 19) and 0xFF, (blocks * 19) shr 8, 0) }
                f[0] == 0x00 && f[1] == 0x2E -> block(0)
                f[0] == 0x04 && f[1] == 0x2E -> events += "end echo"
            }
            WatchChannel.CONVOY -> when {
                f.size == 3 && f.all { it == 0 } -> later { convoy(0, 0, 0) }
                f[0] == 0x04 -> later { convoy(0x04, 0x00) }
                f[0] == 0x06 -> { sixSeen++; later { convoy(0x06, 0xFA, 0, 2, 0, 0, 0, 0, 0) } }
                f[0] == 0x07 -> {
                    val n = acks++
                    events += "ack $n"
                    onAck(n)
                    if (n + 1 < blocks) block(n + 1) else later { drsp(0x04, 0x2E, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF) }
                }
            }
            WatchChannel.ALL_FEATURES -> if (f[0] == 0x13) events += "tap"
            else -> Unit
        }
        return true
    }

    companion object {
        const val EVENT_MS = 5L
    }
}

/** The LifeLog read as one job on the link: its writes go from the job's outbox, and it gives way between blocks. */
class LifeLogJobTest {
    init { TestLog.install() }

    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    private val savedQueue = WatchLink.queue

    @Before fun install() {
        H5600HealthIO.stepCounterSupported = { true }
        ConnectionSetup.busy = { false }
        ConnectionSetup.reset()
        ConnectionSetup.onWatchSetupDone()
        TestLog.clear()
    }

    @After fun restore() {
        H5600HealthIO.stepCounterSupported = { org.avmedia.gshockapi.WatchInfo.hasStepCounter }
        WatchLink.queue = savedQueue
        ConnectionSetup.reset()
    }

    @Test fun aTapDuringA30BlockLifeLogStreamRunsWithinOneBlockAndTheStreamGoesOnWithoutLoss() = runBlocking {
        val tap = AtomicReference<Deferred<JobOutcome<Outcome>>?>(null)
        val watch = StreamingWatch(blocks = 30) { n ->
            if (n == 10) tap.set(WatchLink.link.submit(JobSpec("tap", JobSource.User, JobPriority.USER)) { exchange(GetSetMode.SET, byteArrayOf(0x13)) })
        }
        WatchLink.queue = WatchQueue(transport = watch, readTimeoutMs = 2_000)
        withTimeout(30_000) { H5600HealthIO.request(peek = true) }
        assertTrue(withTimeout(5_000) { tap.get()!!.await() }.ok)
        val ev = watch.events.toList()
        assertEquals("every block acknowledged once, in order: $ev", (0 until 30).map { "ack $it" }, ev.filter { it.startsWith("ack") })
        assertEquals("END echoed last: $ev", "end echo", ev.last())
        val tapAt = ev.indexOf("tap")
        assertTrue("the tap within one block after ack 10: $ev", tapAt > ev.indexOf("ack 10") && tapAt <= ev.indexOf("ack 11") + 1)
        val lines = TestLog.recentEvents()
        assertTrue(lines.toString(), lines.none { it.contains("LifeLog attempt 1 of") })
        assertEquals("the job ended", 0, WatchLink.link.jobs.pending())
    }
}
