package org.avmedia.gshockapi.link

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.casio.WatchLink as SingleWatchLink
import org.avmedia.gshockapi.io.H5600Channel
import org.avmedia.gshockapi.io.IO
import org.avmedia.gshockapi.io.WorkoutLink
import org.avmedia.gshockapi.log.TestLog
import org.avmedia.gshockapi.protocols.H5600Protocol
import org.avmedia.gshockapi.protocols.StandardProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Each link's frames go through its own dispatcher to its own objects: a frame of A never reaches B or the single link. */
class LinkDispatchTest {
    init { TestLog.install() }

    @get:Rule val timeout: Timeout = Timeout.seconds(30)

    private val links = testLinks()

    private val kind1 = "0x10 17 62 07 38 85 CD 7F 01 03 0F FF FF FF FF 24 00 00 00"

    @Test fun eachLinkAndTheSingleWatchLinkHaveTheirOwnDispatcher(): Unit = runBlocking {
        val a = readyLink(links, "A", FakeGatt())
        val b = readyLink(links, "B", FakeGatt())
        assertNotSame(a.dispatcher, b.dispatcher)
        assertNotSame(a.dispatcher, SingleWatchLink.dispatcher)
    }

    @Test fun aTimeAnswerOnAsDispatcherCompletesOnlyAsRead(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", gattB)
        val readA = async { a.time.requestDstWatchState(IO.DstState.ZERO) }
        val readB = async { b.time.requestDstWatchState(IO.DstState.ZERO) }
        waitUntil("1D 00 asked on A and B") { gattA.writes() == listOf("w 1D 00") && gattB.writes() == listOf("w 1D 00") }
        a.dispatcher.onReceived("1D 00 01 00 02 34 12 00 00 FF FF FF FF FF FF", StandardProtocol)
        assertEquals("1D 00 01 00 02 34 12 00 00 FF FF FF FF FF FF", withTimeout(3_000) { readA.await() })
        delay(200)
        assertFalse("B took A's answer", readB.isCompleted)
        b.dispatcher.onReceived("1D 00 01 03 02 34 12 00 00 FF FF FF FF FF FF", StandardProtocol)
        assertEquals("1D 00 01 03 02 34 12 00 00 FF FF FF FF FF FF", withTimeout(3_000) { readB.await() })
    }

    @Test fun aButtonAnswerOnAsDispatcherGoesToAsButtonInfoOnly(): Unit = runBlocking {
        val a = readyLink(links, "A", FakeGatt())
        val b = readyLink(links, "B", FakeGatt())
        val before = SingleWatchLink.button.getConnectionKind()
        a.dispatcher.onReceived(kind1, H5600Protocol)
        assertEquals(1, a.button.getConnectionKind())
        assertEquals(null, b.button.getConnectionKind())
        assertEquals("the single link's kind is untouched", before, SingleWatchLink.button.getConnectionKind())
    }

    @Test fun theWatchsUpkeepAndGateOnAsDispatcherActOnAOnly(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", gattB)
        a.dispatcher.onReceived("49 00", H5600Protocol)
        waitUntil("49 01 written on A") { gattA.writes() == listOf("w 49 01") }
        a.dispatcher.onReceived("3D 10", H5600Protocol)
        assertFalse("A's watch paused its texts", a.notifications.gate.allowed())
        assertTrue("B's watch did not", b.notifications.gate.allowed())
        a.dispatcher.onReceived("28 06 1D 00 00 F7 00", H5600Protocol)
        assertEquals(247, a.notifications.gate.mtu)
        assertEquals(0, b.notifications.gate.mtu)
        delay(100)
        assertEquals(emptyList<String>(), gattB.writes())
    }

    /** A tide transfer on A takes A's data request and bulk frames; the same frame on B keeps its own key. */
    @Test fun aTransferOnATakesTheChannelsOfAOnly(): Unit = runBlocking {
        val a = readyLink(links, "A", FakeGatt())
        val b = readyLink(links, "B", FakeGatt())
        val sent = mutableListOf<String>()
        a.transfers.tide.script = object : WorkoutLink {
            override fun drsp(data: ByteArray) { sent += "drsp" }
            override fun bulk(data: ByteArray) { sent += "bulk" }
            override suspend fun boundary() = Unit
        }
        val send = async { a.transfers.tide.send(ByteArray(40)) }
        waitUntil("A's tide transfer holds its link", 5_000) { a.transfers.tide.isActive }
        assertFalse(b.transfers.tide.isActive)
        assertFalse(SingleWatchLink.transfers.tide.isActive)
        val drsp = CasioConstants.CASIO_DATA_REQUEST_CHARACTERISTIC_UUID
        val convoy = CasioConstants.CASIO_CONVOY_CHARACTERISTIC_UUID
        assertEquals(H5600Channel.DRSP.key, H5600Protocol.linkKey(a, "09 1D 00", drsp))
        assertEquals(H5600Channel.CONVOY.key, H5600Protocol.linkKey(a, "03 00 01", convoy))
        assertEquals("B has no transfer: its frame keeps its code", 0x09, H5600Protocol.linkKey(b, "09 1D 00", drsp))
        assertEquals("the single link has no transfer", 0x09, H5600Protocol.linkKey(SingleWatchLink.link, "09 1D 00", drsp))
        a.onGattLost()
        withTimeout(5_000) { send.await() }
        assertFalse(a.transfers.tide.isActive)
    }
}
