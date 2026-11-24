package org.avmedia.gshockapi.link

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.casio.MessageDispatcher
import org.avmedia.gshockapi.casio.WatchLink as SingleWatchLink
import org.avmedia.gshockapi.log.TestLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Each link has its own settings, alarms, timer, reminders, name, battery and app information: a read on A writes only
 * on A and takes only A's answer, an outbound action of A runs on A, and A's frames are routed with A's own model's
 * protocol.
 */
class LinkIOTest {
    init { TestLog.install() }

    @get:Rule val timeout: Timeout = Timeout.seconds(30)

    private val links = testLinks()

    /** [hex] from [link]'s watch: to its frame sequencer (a read that waits ends) and to its dispatcher. */
    private fun answer(link: WatchLink, hex: String) {
        link.onNotification(CasioConstants.CASIO_ALL_FEATURES_CHARACTERISTIC_UUID, SingleWatchLink.parseHex(hex))
        link.dispatcher.onReceived(hex)
    }

    /** [reply] reaches B and the single link's routes first; [read] on A must still wait. Then A. */
    private suspend fun <T> onlyAsAnswer(a: WatchLink, b: WatchLink, read: Deferred<T>, reply: String): T {
        b.dispatcher.onReceived(reply)
        SingleWatchLink.io.routes[SingleWatchLink.parseHex(reply)[0].toInt() and 0xFF]?.invoke(reply)
        delay(150)
        assertFalse("A took B's or the single link's answer", read.isCompleted)
        answer(a, reply)
        return withTimeout(3_000) { read.await() }
    }

    @Test fun eachLinkAndTheSingleWatchLinkHaveTheirOwnIO(): Unit = runBlocking {
        val a = readyLink(links, "A", FakeGatt())
        val b = readyLink(links, "B", FakeGatt())
        assertNotSame(a.io, b.io)
        assertNotSame(a.io, SingleWatchLink.io)
        listOf(
            a.io.status to b.io.status, a.io.appInfo to b.io.appInfo, a.io.settings to b.io.settings,
            a.io.timeAdjustment to b.io.timeAdjustment, a.io.h5600Settings to b.io.h5600Settings, a.io.alarms to b.io.alarms,
            a.io.timer to b.io.timer, a.io.events to b.io.events, a.io.stepCounter to b.io.stepCounter,
        ).forEach { (x, y) -> assertNotSame(x, y) }
    }

    @Test fun aNameAndBatteryReadOnAWritesOnATakesOnlyAsAnswerAndKeepsItInAsCache(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", gattB)
        val name = async { a.io.status.requestName() }
        waitUntil("23 asked on A") { gattA.writes() == listOf("w 23") }
        assertEquals("A", onlyAsAnswer(a, b, name, "23 41"))
        assertEquals("A", a.cache.get<String>("23"))
        val condition = async { a.io.status.requestCondition() }
        waitUntil("28 asked on A") { gattA.writes() == listOf("w 23", "w 28") }
        assertEquals(100, onlyAsAnswer(a, b, condition, "28 13 1D").batteryLevel)
        assertEquals(emptyList<String>(), gattB.writes())
    }

    @Test fun anAlarmAndTimerReadOnAWritesOnAAndTakesOnlyAsAnswers(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", gattB)
        val alarms = async { a.io.alarms.request() }
        waitUntil("15 asked on A") { gattA.writes() == listOf("w 15") }
        b.dispatcher.onReceived("15 40 40 01 00")
        answer(a, "15 40 40 07 1E")
        waitUntil("16 asked on A") { gattA.writes() == listOf("w 15", "w 16") }
        val got = onlyAsAnswer(a, b, alarms, "16 00 40 08 00 00 40 09 00 00 40 0A 00 00 40 0B 00")
        assertEquals(listOf(7, 8, 9, 10, 11), got.map { it.hour })
        val timer = async { a.io.timer.request() }
        waitUntil("18 asked on A") { gattA.writes().last() == "w 18" }
        assertEquals(3_723, onlyAsAnswer(a, b, timer, "18 01 02 03 00 00 00"))
        assertEquals(emptyList<String>(), gattB.writes())
    }

    @Test fun aReminderReadOnAAsksTitleThenTimeOnAOnly(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", gattB)
        val event = async { a.io.events.request(1) }
        waitUntil("30 01 asked on A") { gattA.writes() == listOf("w 30 01") }
        b.dispatcher.onReceived("30 01 42")
        delay(150)
        assertEquals("B's title asks nothing more of A", listOf("w 30 01"), gattA.writes())
        answer(a, "30 01 41")
        waitUntil("31 01 asked on A") { gattA.writes() == listOf("w 30 01", "w 31 01") }
        answer(a, "31 01 01 26 01 01 26 01 01 00 00")
        assertEquals("A", withTimeout(3_000) { event.await() }.title)
        assertEquals(emptyList<String>(), gattB.writes())
    }

    @Test fun aRefusedSettingsReadEndsOnlyOnItsOwnLink(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", gattB)
        val settings = async { a.io.settings.request() }
        waitUntil("13 asked on A") { gattA.writes() == listOf("w 13") }
        onlyAsAnswer(a, b, settings, "FF 81 13")
        assertTrue(a.io.status.lastError().contains("81 13"))
        assertEquals(emptyList<String>(), gattB.writes())
    }

    @Test fun anOutboundActionOfARunsOnA(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        readyLink(links, "B", gattB)
        a.dispatcher.sendToWatch("{action: 'GET_TIMER'}")
        waitUntil("18 asked on A") { gattA.writes() == listOf("w 18") }
        answer(a, "18 00 00 00 00 00 00")
        a.dispatcher.sendToWatch("{action: \"SET_TIMER\", value: 61}")
        a.dispatcher.sendToWatch("{action: \"SET_TIME\", value: 1700000000000}")
        waitUntil("three writes on A") { gattA.writes().size == 3 }
        assertEquals(listOf("w 18", "w 18 00 01 01 00 00 00"), gattA.writes().take(2))
        assertTrue(gattA.writes()[2].startsWith("w 09 "))
        // The single-watch message reaches the one link, never a registry link.
        MessageDispatcher.sendToWatch("{action: 'GET_TIMER'}")
        delay(150)
        assertEquals(3, gattA.writes().size)
        assertEquals(emptyList<String>(), gattB.writes())
    }

    @Test fun eachLinksFramesAreRoutedWithItsOwnModelsProtocol(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", gattB)
        a.watchInfo.setNameAndModel("CASIO DW-H5600")
        val single = WatchInfo.protocol
        b.dispatcher.onReceived("49 00")
        a.dispatcher.onReceived("49 00")
        waitUntil("49 01 written on A, an H5600") { gattA.writes() == listOf("w 49 01") }
        delay(100)
        assertEquals("B is no H5600: nothing answers its 49", emptyList<String>(), gattB.writes())
        assertTrue(a.io.h5600Settings.isSupported())
        assertFalse(b.io.h5600Settings.isSupported())
        assertEquals("the single link's protocol is untouched", single, WatchInfo.protocol)
    }

    /** A short `28` answer is refused on an H5600 link (its layout needs more) and taken on a link of another model. */
    @Test fun eachLinksAnswerLengthsAreItsOwnModels(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", gattB)
        a.watchInfo.setNameAndModel("CASIO DW-H5600")
        val readA = a.command(GetSetMode.GET, bytes(0x28))
        val readB = b.command(GetSetMode.GET, bytes(0x28))
        waitUntil("28 asked on A and B") { gattA.writes() == listOf("w 28") && gattB.writes() == listOf("w 28") }
        a.onNotification(CasioConstants.CASIO_ALL_FEATURES_CHARACTERISTIC_UUID, bytes(0x28, 0x13, 0x1D, 0x00))
        b.onNotification(CasioConstants.CASIO_ALL_FEATURES_CHARACTERISTIC_UUID, bytes(0x28, 0x13, 0x1D, 0x00))
        assertFalse("A is an H5600: its 28 is longer", (withTimeout(3_000) { readA.await() } as JobOutcome.Done).value.ok)
        assertTrue("B takes the short answer", (withTimeout(3_000) { readB.await() } as JobOutcome.Done).value.ok)
    }
}
