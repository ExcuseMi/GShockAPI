package org.avmedia.gshockapi.io

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.casio.WatchChannel
import org.avmedia.gshockapi.casio.WatchLink
import org.avmedia.gshockapi.casio.WatchQueue
import org.avmedia.gshockapi.casio.WatchTransport
import org.avmedia.gshockapi.casio.singleSetup
import org.avmedia.gshockapi.log.TestLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * A watch that is ready at once and then stops in the middle of the stream. [confirmHeader]: it sends the header block
 * (1234 steps), confirms it (`07 2E 00`), announces the next block, sends one fragment of it and goes silent. Otherwise
 * it goes silent after the first fragment of the header, before any confirm.
 */
private class StallingWatch(private val confirmHeader: Boolean) : WatchTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val starts = AtomicInteger()
    @Volatile private var sixSeen = 0

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

    private fun convoy(b: List<Int>) = H5600HealthIO.onConvoy(hex(b))

    private fun drsp(vararg b: Int) = H5600HealthIO.onDrsp(hex(b.toList()))

    /** The header as it goes over the air: every byte inverted, two checksum bytes behind it. */
    private fun headerOnAir(): List<Int> {
        val header = IntArray(HEADER) { 0 }
        header[1] = 0x0F
        header[5] = 26; header[6] = 11; header[7] = 27; header[8] = 1
        header[14] = 0xD2; header[15] = 0x04 // 1234 steps
        return header.map { it xor 0xFF } + listOf(0, 0)
    }

    override suspend fun write(channel: WatchChannel, frame: ByteArray): Boolean {
        delay(EVENT_MS)
        val f = frame.map { it.toInt() and 0xFF }
        when (channel) {
            WatchChannel.READ_REQUEST -> later {
                status(if (f[0] == 0x28) listOf(0x28, 0x06, 0x18, 0, 0, 0xFA, 0, 0, 0, 0) else listOf(f[0], 0x40, 0x1F) + List(17) { 0 })
            }
            WatchChannel.DATA_REQUEST -> when {
                f[0] == 0x00 && f[1] == 0x2E && f[2] == 0xFF -> if (sixSeen >= 2) {
                    starts.incrementAndGet()
                    later { drsp(0x00, 0x2E, 0x00, HEADER, 0, 0) }
                }
                f[0] == 0x00 && f[1] == 0x2E && f[2] == 0x00 -> later {
                    val air = headerOnAir()
                    convoy(listOf(0x05) + air.take(19))
                    if (confirmHeader) {
                        convoy(listOf(0x05) + air.drop(19))
                        drsp(0x07, 0x2E, 0x00, HEADER, 0, 0)
                    }
                }
                f[0] == 0x07 && f[1] == 0x2E -> later { drsp(0x00, 0x2E, 0x50, 0x40, 0, 0) }
                // The open block: one fragment, then nothing more.
                f[0] == 0x00 && f[1] == 0x2E && f[2] == 0x50 -> later { convoy(listOf(0x05) + List(19) { 0xAA }) }
            }
            WatchChannel.CONVOY -> when {
                f.size == 3 && f.all { it == 0 } -> later { convoy(listOf(0, 0, 0)) }
                f[0] == 0x04 -> later { convoy(listOf(0x04, 0x00)) }
                f[0] == 0x06 -> { sixSeen++; later { convoy(listOf(0x06, 0xFA, 0, 2, 0, 0, 0, 0, 0)) } }
            }
            else -> Unit
        }
        return true
    }

    companion object {
        const val EVENT_MS = 5L
        const val HEADER = 20
    }
}

/** A LifeLog stream that stops: the attempt ends after the stall limit, not after the attempt's 180 s. */
class LifeLogStallTest {
    init { TestLog.install() }

    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    private val savedQueue = WatchLink.queue
    private val reader get() = WatchLink.transfers.lifeLog
    private val savedStall = reader.stallMs

    @Before fun install() {
        H5600HealthIO.stepCounterSupported = { true }
        singleSetup.busy = { false }
        singleSetup.reset()
        singleSetup.onWatchSetupDone()
        reader.stallMs = 500
        TestLog.clear()
    }

    @After fun restore() {
        H5600HealthIO.stepCounterSupported = { org.avmedia.gshockapi.WatchInfo.hasStepCounter }
        reader.stallMs = savedStall
        WatchLink.queue = savedQueue
        singleSetup.reset()
    }

    @Test fun theDefaultLimitIsWellAboveTheWatchsOwnPauses() {
        assertEquals(20_000L, savedStall)
    }

    @Test fun aStreamThatStopsAfterAConfirmedBlockKeepsThatBlockAndEndsTheRead() = runBlocking {
        val watch = StallingWatch(confirmHeader = true)
        WatchLink.queue = WatchQueue(transport = watch, readTimeoutMs = 2_000)
        val started = System.currentTimeMillis()
        val data = withTimeout(30_000) { H5600HealthIO.request(peek = true) }
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        val lines = TestLog.recentEvents()
        assertNotNull("the confirmed header is decoded: $lines", data.h5600)
        assertTrue(lines.toString(), lines.any { it.endsWith("LifeLog attempt ended: no frame from the watch for 0 s (1 blocks confirmed before)") })
        assertTrue(lines.toString(), lines.any { it.endsWith("LifeLog read stopped early: steps 1234 from 1 confirmed blocks, the rest stays on the watch") })
        assertTrue(H5600HealthIO.lastError.orEmpty(), H5600HealthIO.lastError.orEmpty().contains("stopped answering"))
        assertEquals("what was confirmed is not asked for again", 1, watch.starts.get())
        assertTrue("the read took $seconds s", seconds < 10.0)
    }

    @Test fun aStreamThatStopsBeforeAnyConfirmIsTriedAgainAndEndsWithoutData() = runBlocking {
        val watch = StallingWatch(confirmHeader = false)
        WatchLink.queue = WatchQueue(transport = watch, readTimeoutMs = 2_000)
        val started = System.currentTimeMillis()
        val data = withTimeout(40_000) { H5600HealthIO.request(peek = true) }
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        val lines = TestLog.recentEvents()
        assertNull(data.h5600)
        assertEquals("our three attempts stay: $lines", 3, lines.count { it.endsWith("LifeLog attempt ended: no frame from the watch for 0 s (0 blocks confirmed before)") })
        assertTrue(H5600HealthIO.lastError.orEmpty(), H5600HealthIO.lastError.orEmpty().contains("could not be read"))
        assertTrue("three stalled attempts took $seconds s", seconds < 30.0)
    }

    /** Another job that took the link for longer than the stall limit: the watch's quiet during it ended the attempt. */
    @Test fun aJobThatHoldsTheLinkLongerThanTheLimitIsNotAStall() = runBlocking {
        val watch = StallingWatch(confirmHeader = true)
        WatchLink.queue = WatchQueue(transport = watch, readTimeoutMs = 2_000)
        reader.stallMs = 800
        val read = async(Dispatchers.Default) { H5600HealthIO.request(peek = true) }
        withTimeout(10_000) { while (watch.starts.get() == 0) delay(10) }
        val jobEnd = java.util.concurrent.atomic.AtomicLong()
        val urgent = WatchLink.link.submit(
            org.avmedia.gshockapi.link.JobSpec("long job", org.avmedia.gshockapi.link.JobSource.System("test"), org.avmedia.gshockapi.link.JobPriority.USER),
        ) {
            delay(2_500)
            jobEnd.set(System.currentTimeMillis())
        }
        urgent.await()
        assertTrue("the long job ran", jobEnd.get() > 0)
        withTimeout(30_000) { read.await() }
        val after = System.currentTimeMillis() - jobEnd.get()
        assertTrue("the attempt ended $after ms after the other job gave the link back, the limit is 800 ms", after >= 600)
    }
}
