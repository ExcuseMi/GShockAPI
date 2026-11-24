package org.avmedia.gshockapi.link

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.casio.WatchLink as SingleWatchLink
import org.avmedia.gshockapi.io.IO
import org.avmedia.gshockapi.log.TestLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Each link has its own watch info, cache, button info, settings reads and connection routes: nothing of A reaches B. */
class LinkWatchStateTest {
    init { TestLog.install() }

    @get:Rule val timeout: Timeout = Timeout.seconds(30)

    private val links = testLinks()

    private val kind1 = "0x10 17 62 07 38 85 CD 7F 01 03 0F FF FF FF FF 24 00 00 00"
    private val kind4 = "0x10 17 62 07 38 85 CD 7F 04 03 0F FF FF FF FF 24 00 00 00"

    @Test fun eachLinkAndTheSingleWatchLinkHaveTheirOwnWatchState(): Unit = runBlocking {
        val a = readyLink(links, "A", FakeGatt())
        val b = readyLink(links, "B", FakeGatt())
        listOf(
            a.watchInfo to b.watchInfo, a.cache to b.cache, a.button to b.button, a.rawSettings to b.rawSettings,
            a.connection.routes to b.connection.routes,
        ).forEach { (x, y) -> assertNotSame(x, y) }
        assertNotSame(a.watchInfo, SingleWatchLink.watchInfo)
        assertNotSame(a.cache, SingleWatchLink.cache)
        assertNotSame(a.button, SingleWatchLink.button)
        assertNotSame(a.rawSettings, SingleWatchLink.rawSettings)
        assertNotSame(a.connection.routes, SingleWatchLink.connectionRoutes)
    }

    @Test fun theModelOfAIsAsAloneAndTheSingleWatchInfoReadsTheOneLink(): Unit = runBlocking {
        val a = readyLink(links, "A", FakeGatt())
        val b = readyLink(links, "B", FakeGatt())
        a.watchInfo.setNameAndModel("CASIO DW-H5600")
        a.watchInfo.setAddress("AA:AA")
        assertEquals(WatchInfo.WatchModel.DW_H5600, a.watchInfo.model)
        assertTrue(a.watchInfo.info.hasStepCounter)
        assertEquals(WatchInfo.WatchModel.GENERIC, b.watchInfo.model)
        assertFalse(b.watchInfo.info.hasStepCounter)
        assertEquals("", b.watchInfo.address)
        assertEquals("AA:AA", a.watchInfo.address)
        try {
            WatchInfo.setModelForTest("CASIO GW-B5600")
            assertEquals("WatchInfo is the one link's", WatchInfo.model, SingleWatchLink.watchInfo.model)
            assertEquals(WatchInfo.WatchModel.DW_H5600, a.watchInfo.model)
        } finally {
            WatchInfo.reset()
        }
    }

    /** The LifeLog read of a link asks its own watch's model: a step-counter watch on A never makes B (a plain watch) read. */
    @Test fun theStepCounterCheckOfALinksReadUsesItsOwnWatch(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA).also { it.connection.keepAlive.onSetup("47 01") }
        val b = readyLink(links, "B", gattB).also { it.connection.keepAlive.onSetup("47 01") }
        a.watchInfo.setNameAndModel("CASIO DW-H5600")
        try {
            // The single-watch info says H5600 too: B must not follow it.
            WatchInfo.setModelForTest("CASIO DW-H5600")
            val outB = withTimeout(5_000) { b.transfers.lifeLog.request() }
            assertNull("B (no step counter) does not read: $outB", outB.timestamp)
            assertEquals(emptyList<String>(), gattB.writes())
            assertFalse(b.transfers.lifeLog.isActive)

            WatchInfo.reset()
            val readA = async { a.transfers.lifeLog.request() }
            waitUntil("A (step counter) asks 0x43 though the single watch is unknown", 5_000) { "w 43" in gattA.writes() }
            a.onGattLost()
            withTimeout(5_000) { readA.await() }
        } finally {
            WatchInfo.reset()
        }
    }

    @Test fun aCacheEntryOfAIsNotB(): Unit = runBlocking {
        val a = readyLink(links, "A", FakeGatt())
        val b = readyLink(links, "B", FakeGatt())
        a.cache.put("1d00", "A's DST")
        assertEquals("A's DST", a.cache.get<String>("1D00"))
        assertEquals("B asks its own watch", "B's DST", b.cache.request("1D00") { "B's DST" })
        assertEquals("A's DST", a.cache.request("1D00") { "asked again" })
        b.cache.clear()
        assertEquals("A's DST", a.cache.get<String>("1D00"))
    }

    @Test fun aButtonRequestOnAWritesOnATakesOnlyAsAnswerAndADropForgetsOnlyAs(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", gattB)
        val askA = async { a.button.request() }
        waitUntil("0x10 asked on A") { gattA.writes() == listOf("w 10") }
        assertEquals(emptyList<String>(), gattB.writes())

        b.button.onReceived(kind4)
        delay(200)
        assertFalse("A took B's answer", askA.isCompleted)
        a.button.onReceived(kind1)
        assertEquals(IO.WatchButton.LOWER_LEFT, withTimeout(3_000) { askA.await() })
        assertEquals(IO.WatchButton.LOWER_LEFT, a.button.get())
        assertEquals(IO.WatchButton.INVALID, b.button.get())
        assertEquals(1, a.button.getConnectionKind())
        assertEquals(4, b.button.getConnectionKind())

        // Both answered within the share window; A drops: only A asks its watch again.
        a.onGattLost()
        b.button.request().also { assertEquals(IO.WatchButton.LOWER_RIGHT, it) }
        assertEquals("B shared its fresh answer", emptyList<String>(), gattB.writes())
        a.onGattReady().await()
        val again = async { a.button.request() }
        waitUntil("A asks again after its drop") { gattA.writes() == listOf("w 10", "w 10") }
        a.button.onReceived(kind1)
        withTimeout(3_000) { again.await() }
    }

    @Test fun aSettingsReadOnAWritesOnATakesOnlyAsAnswerAndWaitsForNoOtherSetup(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", gattB)
        // B's setup and the single link's run: A's read waits for its own setup only.
        b.connection.setup.reset()
        b.connection.setup.onKind(kind1)
        SingleWatchLink.connection.setup.reset()
        SingleWatchLink.connection.setup.onKind(kind1)
        try {
            assertTrue(b.connection.setup.isRunning())
            assertTrue(SingleWatchLink.connection.setup.isRunning())
            val readA = async { a.rawSettings.read(byteArrayOf(0x45), 5_000) }
            waitUntil("0x45 asked on A at once", 1_000) { "w 45" in gattA.writes() }
            assertFalse("B's watch answers 0x45: nothing waits on B", b.rawSettings.offer("45 0B 0B"))
            delay(200)
            assertFalse("A took B's answer", readA.isCompleted)
            assertFalse("no 0x45 on B", gattB.writes().any { it.startsWith("w 45") })
            a.rawSettings.offer("45 0A 0A")
            val reply = withTimeout(3_000) { readA.await() }
            assertArrayEquals(byteArrayOf(0x45, 0x0A, 0x0A), reply.frame)
        } finally {
            SingleWatchLink.connection.setup.reset()
        }
    }

    @Test fun aSettingsWriteOnAGoesOnAOnly(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        readyLink(links, "B", gattB)
        a.rawSettings.write(intArrayOf(0x24, 0x01))
        assertTrue(withTimeout(3_000) { a.rawSettings.link.sendChecked(byteArrayOf(0x24, 0x02)) })
        waitUntil("both writes on A") { gattA.writes().containsAll(listOf("w 24 01", "w 24 02")) }
        assertEquals(emptyList<String>(), gattB.writes())
    }

    /** The protocol routes the watch's own frames to a link's routes: `47 01` and `49 00` of A move and answer A only. */
    @Test fun theRoutesOfATakeOnlyAsFrames(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", gattB)
        a.connection.routes.own.getValue(0x47)("47 01")
        assertTrue(a.connection.setup.watchSetupDone)
        assertFalse(b.connection.setup.watchSetupDone)
        a.connection.routes.own.getValue(0x49)("49 00")
        waitUntil("49 01 on A") { gattA.writes() == listOf("w 49 01") }
        a.connection.setup.reset()
        b.connection.setup.reset()
        a.connection.routes.watched.getValue(0x10)(kind1)
        assertTrue(a.connection.setup.isRunning())
        assertFalse(b.connection.setup.isRunning())
        assertEquals(emptyList<String>(), gattB.writes())
        // And B's routes take B's frames: its `49 00` is answered on B.
        b.connection.routes.own.getValue(0x49)("49 00")
        waitUntil("49 01 on B") { gattB.writes() == listOf("w 49 01") }
        assertFalse("A wrote nothing for B", gattA.writes().count { it == "w 49 01" } > 1)
    }
}
