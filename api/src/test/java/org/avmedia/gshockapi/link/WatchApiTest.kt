package org.avmedia.gshockapi.link

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.AppNotification
import org.avmedia.gshockapi.NotificationType
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.casio.NotificationSender
import org.avmedia.gshockapi.casio.WatchLink as SingleWatchLink
import org.avmedia.gshockapi.log.TestLog
import org.avmedia.gshockapi.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Two watches through the per-watch API ([org.avmedia.gshockapi.WatchApi], reached by key): every call on A writes only
 * on A's link and takes only A's answer; B, and the single-watch link, are never touched.
 */
class WatchApiTest {
    init { TestLog.install() }

    @get:Rule val timeout: Timeout = Timeout.seconds(30)

    private val links = testLinks()

    /** [hex] from [link]'s watch: to its frame sequencer (a read that waits ends) and to its dispatcher. */
    private fun answer(link: WatchLink, hex: String) {
        link.onNotification(CasioConstants.CASIO_ALL_FEATURES_CHARACTERISTIC_UUID, SingleWatchLink.parseHex(hex))
        link.dispatcher.onReceived(hex)
    }

    /** [reply] reaches B and the single link first; [read] on A must still wait. Then A's watch sends it. */
    private suspend fun <T> onlyAsAnswer(a: WatchLink, b: WatchLink, read: Deferred<T>, reply: String): T {
        answer(b, reply)
        SingleWatchLink.dispatcher.onReceived(reply)
        delay(150)
        assertFalse("A's call took B's or the single link's answer to $reply", read.isCompleted)
        answer(a, reply)
        return withTimeout(3_000) { read.await() }
    }

    @Test fun theApiIsReachedByKeyAndIsEachWatchsOwn(): Unit = runBlocking {
        val a = readyLink(links, "A", FakeGatt())
        val b = readyLink(links, "B", FakeGatt())
        assertSame(a.api, links.api("A"))
        assertSame(b.api, links.api("B"))
        assertNull("no link, no API", links.api("C"))
        assertNotSame(a.api, b.api)
        assertNotSame(a.api, SingleWatchLink.api)
        assertEquals("A", a.api.key)
        a.watchInfo.setNameAndModel("CASIO DW-H5600")
        assertEquals("DW-H5600", a.api.info.shortName)
        assertFalse("B keeps its own model", b.api.info.shortName == "DW-H5600")
    }

    @Test fun nameBatteryTimerAndAlarmsOfAComeOnlyFromA(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", gattB)

        val name = async { a.api.getWatchName() }
        waitUntil("23 asked on A") { gattA.writes() == listOf("w 23") }
        assertEquals("A", onlyAsAnswer(a, b, name, "23 41"))

        val battery = async { a.api.getBatteryLevel() }
        waitUntil("28 asked on A") { gattA.writes().last() == "w 28" }
        assertEquals(100, onlyAsAnswer(a, b, battery, "28 13 1D"))

        val timer = async { a.api.getTimer() }
        waitUntil("18 asked on A") { gattA.writes().last() == "w 18" }
        assertEquals(3_723, onlyAsAnswer(a, b, timer, "18 01 02 03 00 00 00"))

        val alarms = async { a.api.getAlarms() }
        waitUntil("15 asked on A") { gattA.writes().last() == "w 15" }
        answer(b, "15 40 40 01 00")
        answer(a, "15 40 40 07 1E")
        waitUntil("16 asked on A") { gattA.writes().last() == "w 16" }
        val got = onlyAsAnswer(a, b, alarms, "16 00 40 08 00 00 40 09 00 00 40 0A 00 00 40 0B 00")
        assertEquals(listOf(7, 8, 9, 10, 11), got.map { it.hour })

        assertEquals("B's link wrote nothing for A", emptyList<String>(), gattB.writes())
    }

    @Test fun theHomeTimeAndTheTimeSyncOfARunOnA(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", gattB)
        val home = async { a.api.getHomeTime() }
        waitUntil("1F 00 asked on A") { gattA.writes() == listOf("w 1F 00") }
        assertEquals("AB", onlyAsAnswer(a, b, home, "1F 00 41 42 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"))

        val sync = async { a.api.setTime("Asia/Tokyo") }
        waitUntil("the time sync starts on A") { gattA.writes().any { it.startsWith("w 1D") } }
        delay(100)
        assertEquals("the time sync of A asks nothing of B", emptyList<String>(), gattB.writes())
        assertEquals("A's time zone", "Asia/Tokyo", a.time.timeZone)
        assertFalse("B keeps its own time zone", b.time.timeZone == "Asia/Tokyo")
        assertFalse("the single-watch link keeps its own time zone", SingleWatchLink.time.timeZone == "Asia/Tokyo")
        sync.cancel()
    }

    @Test fun writesAndMessagesOfAGoOnlyToA(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        readyLink(links, "B", gattB)
        a.api.setTimer(61)
        waitUntil("the timer written on A") { gattA.writes() == listOf("w 18 00 01 01 00 00 00") }
        // A registry link's message goes to its own dispatcher, never to the single-watch one.
        a.api.sendMessage("{action: 'GET_TIMER'}")
        waitUntil("18 asked on A") { gattA.writes().size == 2 }
        assertEquals("w 18", gattA.writes()[1])
        a.api.setSettings(Settings())
        delay(300)
        assertEquals(emptyList<String>(), gattB.writes())
    }

    @Test fun aTextOnAGoesOutOnAWithAsOwnModelsSpacing(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", gattB)
        a.watchInfo.setNameAndModel("CASIO DW-H5600")
        b.watchInfo.setNameAndModel("CASIO GW-B5600")
        listOf(a, b).forEach { it.notifications.gate.onCondition("0x28 00 00 00 00 FF 00 00 00 00") }
        val spacingOfTheOne = SingleWatchLink.notifications.sender.spacingMs
        val text = AppNotification(NotificationType.MESSAGE, "20261125T100000", "app", "title", "text")
        assertEquals(NotificationSender.Outcome.SENT, withTimeout(10_000) { a.api.sendAppNotificationAndWait(text) })
        assertTrue("the text went out on A", gattA.writes().isNotEmpty())
        assertEquals(emptyList<String>(), gattB.writes())
        assertEquals("A is an H5600: 2 s between texts", 2_000L, a.notifications.sender.spacingMs)
        assertEquals(NotificationSender.Outcome.SENT, withTimeout(10_000) { b.api.sendAppNotificationAndWait(text) })
        assertEquals("B is no H5600", 500L, b.notifications.sender.spacingMs)
        assertEquals("A's spacing stays its own", 2_000L, a.notifications.sender.spacingMs)
        assertEquals("the single-watch link's spacing is untouched", spacingOfTheOne, SingleWatchLink.notifications.sender.spacingMs)
    }

    @Test fun aWorkoutReadThroughTheApiOfARunsOnlyOnA(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA).also { it.connection.keepAlive.onSetup("47 01") }
        val b = readyLink(links, "B", gattB).also { it.connection.keepAlive.onSetup("47 01") }
        a.api.workoutSink = { true }
        assertNull("B keeps no workouts of A", b.api.workoutSink)
        val read = async { a.api.downloadWorkouts(userAsked = true) }
        waitUntil("A's workout read holds its link", 5_000) { a.api.isWorkoutReadActive && gattA.writes().isNotEmpty() }
        assertFalse(b.api.isWorkoutReadActive)
        assertEquals(emptyList<String>(), gattB.writes())
        links.close("A")
        withTimeout(5_000) { read.await() }
    }

    /** A step that only a single-watch adapter has never runs on a registry link: it would reach the single watch. */
    @Test fun aSingleWatchOnlyStepRefusesARegistryLink(): Unit = runBlocking {
        val gattA = FakeGatt()
        val a = readyLink(links, "A", gattA)
        try {
            a.api.getExerciseProfile()
            fail("the exercise profile ran on a registry link")
        } catch (expected: UnsupportedOperationException) {
            assertTrue(expected.message.orEmpty().contains("single-watch"))
        }
        assertEquals(emptyList<String>(), gattA.writes())
    }
}
