package org.avmedia.gshockapi.link

import kotlinx.coroutines.runBlocking
import org.avmedia.gshockapi.casio.WatchLink as SingleWatchLink
import org.avmedia.gshockapi.log.TestLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Each link has its own connection setup and keep-alive: what one watch sends never moves or writes to another. */
class LinkConnectionContextTest {
    init { TestLog.install() }

    @get:Rule val timeout: Timeout = Timeout.seconds(15)

    private val links = testLinks()

    private val kind1 = "0x10 17 62 07 38 85 CD 7F 01 03 0F FF FF FF FF 24 00 00 00"

    @Test fun eachLinkAndTheSingleWatchLinkHaveTheirOwnContext() = runBlocking {
        val a = readyLink(links, "A", FakeGatt())
        val b = readyLink(links, "B", FakeGatt())
        assertNotSame(a.connection, b.connection)
        assertNotSame(a.connection.setup, b.connection.setup)
        assertNotSame(a.connection.keepAlive, b.connection.keepAlive)
        assertNotSame(a.connection, SingleWatchLink.connection)
    }

    @Test fun aConnectionCheckOnAIsAnsweredOnAOnly() = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        readyLink(links, "B", gattB)
        a.connection.keepAlive.onCheck("49 00")
        waitUntil("49 01 on A") { gattA.writes() == listOf("w 49 01") }
        assertEquals(emptyList<String>(), gattB.writes())
    }

    @Test fun theSetupOfAStartsAndWritesOnAOnly() = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", gattB)
        a.connection.setup.reset()
        b.connection.setup.reset()
        a.connection.setup.onKind(kind1)
        assertTrue(a.connection.setup.isRunning())
        assertFalse("B's setup did not move", b.connection.setup.isRunning())
        assertEquals(null, b.connection.setup.lastKind)
        waitUntil("0x11 asked on A") { gattA.writes() == listOf("w 11") }
        assertEquals(emptyList<String>(), gattB.writes())
    }

    @Test fun aWatchSetupDoneAndADropOfAStayOnA() = runBlocking {
        val a = readyLink(links, "A", FakeGatt())
        val b = readyLink(links, "B", FakeGatt())
        a.connection.keepAlive.onSetup("47 01")
        b.connection.keepAlive.onSetup("47 01")
        a.onGattLost()
        assertFalse("A's setup starts over with its link", a.connection.setup.watchSetupDone)
        assertTrue("B keeps its watch's 47 01", b.connection.setup.watchSetupDone)
        a.connection.keepAlive.onSetup("47 01")
        assertTrue(a.connection.setup.watchSetupDone)
    }
}
