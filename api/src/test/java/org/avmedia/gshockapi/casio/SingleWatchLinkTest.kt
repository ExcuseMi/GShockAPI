package org.avmedia.gshockapi.casio

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.log.TestLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The single-watch adapter: the old calls keep their order and results on the one link. */
class SingleWatchLinkTest {
    init { TestLog.install() }

    private val saved = WatchLink.queue

    @After fun restore() {
        WatchLink.queue = saved
    }

    private class Recorder(var ok: Boolean = true, val hold: CompletableDeferred<Unit>? = null) : WatchTransport {
        val sent = CopyOnWriteArrayList<Int>()

        override suspend fun write(channel: WatchChannel, frame: ByteArray): Boolean {
            hold?.await()
            sent += frame[0].toInt() and 0xFF
            return ok
        }
    }

    @Test fun writesGoOutInTheOrderAskedAndACheckedWriteReportsItsResult() = runBlocking {
        val r = Recorder()
        WatchLink.queue = WatchQueue(transport = r)
        for (code in 0x30..0x3F) WatchLink.write(GetSetMode.CONVOY, byteArrayOf(code.toByte()))
        assertTrue(WatchLink.writeChecked(GetSetMode.CONVOY, byteArrayOf(0x40)))
        assertEquals((0x30..0x40).toList(), r.sent.toList())
        r.ok = false
        assertFalse(WatchLink.writeChecked(GetSetMode.CONVOY, byteArrayOf(0x41)))
    }

    @Test fun aJobStuckOnAReplacedSequencerDoesNotHoldTheLink() = runBlocking {
        WatchLink.queue = WatchQueue(transport = Recorder(hold = CompletableDeferred()))
        WatchLink.write(GetSetMode.CONVOY, byteArrayOf(0x30))
        Thread.sleep(50)
        val r = Recorder()
        WatchLink.queue = WatchQueue(transport = r)
        assertTrue(withTimeout(2_000) { WatchLink.writeChecked(GetSetMode.CONVOY, byteArrayOf(0x31)) })
        assertEquals(listOf(0x31), r.sent.toList())
    }
}
