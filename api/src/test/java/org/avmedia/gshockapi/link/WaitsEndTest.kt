package org.avmedia.gshockapi.link

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.casio.Outcome
import org.avmedia.gshockapi.casio.WatchLink as SingleWatchLink
import org.avmedia.gshockapi.io.LinkAnswer
import org.avmedia.gshockapi.io.WatchAnswerFailed
import org.avmedia.gshockapi.io.WatchClock
import org.avmedia.gshockapi.log.TestLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Every read that waits for the watch ends: with its answer, with the reason its request failed, or with an error
 * after its time limit. None waits for ever when the watch never answers or the link is gone.
 */
class WaitsEndTest {
    init { TestLog.install() }

    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    private val links = testLinks()
    private val watches = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() = watches.cancel()

    private suspend fun failureOf(block: suspend () -> Any?): Throwable? = runCatching { withTimeout(20_000) { block() } }.exceptionOrNull()

    @Test fun anAnswerThatNeverComesEndsTheReadWithAnError(): Unit = runBlocking {
        val answer = LinkAnswer<String>()
        val started = System.currentTimeMillis()
        val failed = failureOf { answer.ask("read 23", limitMs = 200) {} }
        assertTrue("ended with $failed", failed is WatchAnswerFailed)
        assertTrue(failed!!.message.orEmpty(), failed.message.orEmpty().startsWith("read 23: no answer within"))
        assertTrue(System.currentTimeMillis() - started < 5_000)
        assertTrue(!answer.waiting)
    }

    @Test fun aRequestThatFailedEndsTheReadAtOnceWithItsReason(): Unit = runBlocking {
        val answer = LinkAnswer<String>()
        val job = CompletableDeferred<JobOutcome<Outcome>>()
        val read = async { failureOf { answer.ask("read 28") { job } } }
        delay(50)
        job.complete(JobOutcome.Done(Outcome.failed("link closed")))
        val failed = read.await()
        assertTrue("ended with $failed", failed is WatchAnswerFailed)
        assertEquals("read 28: link closed", failed!!.message)
    }

    @Test fun twoReadsAtOnceEachGetAnAnswerAndNeitherWaitsForEver(): Unit = runBlocking {
        val answer = LinkAnswer<String>()
        val first = async { answer.ask("a") { watches.launch { delay(30); answer.complete("one") } } }
        val second = async { answer.ask("b") { watches.launch { delay(30); answer.complete("two") } } }
        val got = withTimeout(5_000) { listOf(first.await(), second.await()) }
        assertEquals(setOf("one", "two"), got.toSet())
    }

    @Test fun aReadOfAWatchValueOnALinkThatIsGoneEnds(): Unit = runBlocking {
        val gatt = FakeGatt()
        val a = readyLink(links, "A", gatt)
        gatt.down = true // the request cannot be written
        for (read in listOf<suspend () -> Any?>(
            { a.io.status.requestName() },
            { a.io.status.requestCondition() },
            { a.io.appInfo.request() },
            { a.io.timer.request() },
        )) {
            val failed = failureOf(read)
            assertTrue("ended with $failed", failed is WatchAnswerFailed)
        }
    }

    @Test fun aButtonInfoReadWithoutAnAnswerEnds(): Unit = runBlocking {
        val gatt = FakeGatt()
        val a = readyLink(links, "A", gatt)
        a.button.limitMs = 300
        val failed = failureOf { a.button.request() }
        assertTrue("ended with $failed", failed is WatchAnswerFailed)
        assertTrue(failed!!.message.orEmpty(), failed.message.orEmpty().startsWith("read 10: "))
    }

    @Test fun aButtonInfoReadIsStillAnsweredWhenTheWatchAnswers(): Unit = runBlocking {
        val gatt = FakeGatt()
        val a = readyLink(links, "A", gatt)
        gatt.onWrite = { frame ->
            if (frame.size == 1 && frame[0].toInt() == 0x10) {
                val hex = "10 00 00 00 00 00 00 00 01 00 00 00 00 00 00 00 00 00 00"
                watches.launch {
                    delay(5)
                    a.onNotification(CasioConstants.CASIO_ALL_FEATURES_CHARACTERISTIC_UUID, SingleWatchLink.parseHex(hex))
                    a.button.onReceived(hex)
                }
            }
        }
        withTimeout(15_000) { a.button.request() }
    }

    @Test fun aClockThatNeverGetsReadyDoesNotHoldTheTimeSync(): Unit = runBlocking {
        WatchClock.prepare = { awaitCancellation() }
        try {
            val gatt = FakeGatt()
            val a = readyLink(links, "A", gatt)
            a.time.clockReadyLimitMs = 300
            val replies = mapOf(
                0x1D to "1D 00 01 00 02 34 12 00 00 FF FF FF FF FF FF",
                0x1E to "1E %02X 02 76 10 00 00",
                0x1F to "1F %02X 54 4F 4B 59 4F 00 00 00 00 00 00 00 00 00 00 00 00 00",
                0x24 to "24 %02X 54 4F 4B 59 4F 00 00 00 00 00 00 00 00 00 00 00 00 00",
            )
            gatt.onWrite = { frame ->
                val reply = replies[frame[0].toInt() and 0xFF]
                if (frame.size == 2 && reply != null) {
                    val hex = reply.format(frame[1].toInt() and 0xFF)
                    watches.launch {
                        delay(5)
                        a.onNotification(CasioConstants.CASIO_ALL_FEATURES_CHARACTERISTIC_UUID, SingleWatchLink.parseHex(hex))
                        a.time.routes[SingleWatchLink.parseHex(hex)[0].toInt() and 0xFF]?.invoke(hex)
                    }
                }
            }
            withTimeout(20_000) { a.time.set() }
            assertTrue(gatt.writes().any { it.startsWith("w 09 ") })
        } finally {
            WatchClock.prepare = {}
        }
    }
}
