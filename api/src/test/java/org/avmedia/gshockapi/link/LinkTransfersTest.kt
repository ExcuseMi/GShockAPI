package org.avmedia.gshockapi.link

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.casio.NotificationSender
import org.avmedia.gshockapi.casio.WatchLink as SingleWatchLink
import org.avmedia.gshockapi.log.TestLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Each link has its own LifeLog read, workout read and notification texts: what runs, waits or stops on A never touches B. */
class LinkTransfersTest {
    init { TestLog.install() }

    @get:Rule val timeout: Timeout = Timeout.seconds(30)

    private val links = testLinks()

    /** A ready link whose watch finished its own setup (`47 01`) and keeps a LifeLog. */
    private suspend fun watch(key: String, gatt: FakeGatt): WatchLink =
        readyLink(links, key, gatt).also {
            it.connection.keepAlive.onSetup("47 01")
            it.transfers.lifeLog.stepCounterSupported = { true }
        }

    /** The watch of [link] answers a read: the link's read takes it, and so does its LifeLog read while it waits for one. */
    private fun answer(link: WatchLink, hex: String) {
        link.onNotification(CasioConstants.CASIO_ALL_FEATURES_CHARACTERISTIC_UUID, SingleWatchLink.parseHex(hex))
        if (link.transfers.lifeLog.isAwaitingSetupReply()) link.transfers.lifeLog.onStatus(hex)
    }

    @Test fun eachLinkAndTheSingleWatchLinkHaveTheirOwnTransfersAndTexts(): Unit = runBlocking {
        val a = watch("A", FakeGatt())
        val b = watch("B", FakeGatt())
        assertNotSame(a.transfers.lifeLog, b.transfers.lifeLog)
        assertNotSame(a.transfers.workouts, b.transfers.workouts)
        assertNotSame(a.notifications.sender, b.notifications.sender)
        assertNotSame(a.notifications.gate, b.notifications.gate)
        assertNotSame(a.transfers, SingleWatchLink.transfers)
        assertNotSame(a.notifications, SingleWatchLink.notifications)
    }

    @Test fun aLifeLogReadOnAWritesOnATakesOnlyAsAnswersAndEndsOnlyWithA(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = watch("A", gattA)
        val b = watch("B", gattB)
        val readA = async { a.transfers.lifeLog.request() }
        waitUntil("A asks 0x43", 5_000) { a.transfers.lifeLog.isAwaitingSetupReply() && "w 43" in gattA.writes() }
        assertFalse(b.transfers.lifeLog.isActive)
        assertFalse(b.transfers.lifeLog.isAwaitingSetupReply())
        assertFalse("B's setup is not held by A's read", b.connection.setup.busy())
        assertTrue(a.connection.setup.busy())
        assertEquals(emptyList<String>(), gattB.writes())

        // B's answer is not A's: A still waits for its own 0x43.
        answer(b, "0x43 40 1F 00")
        delay(300)
        assertFalse("A went on with B's answer: ${gattA.writes()}", "w 28" in gattA.writes())
        answer(a, "0x43 40 1F 00")
        waitUntil("A asks 0x28 after its own answer") { "w 28" in gattA.writes() }

        // B reads at the same time (one read per link, not one for all), and A's drop leaves it running.
        val readB = async { b.transfers.lifeLog.request() }
        waitUntil("B asks 0x43", 5_000) { b.transfers.lifeLog.isAwaitingSetupReply() && "w 43" in gattB.writes() }
        a.onGattLost()
        withTimeout(5_000) { readA.await() }
        assertTrue(a.transfers.lifeLog.lastError.orEmpty().contains("disconnected"))
        assertFalse(a.transfers.lifeLog.isActive)
        assertTrue("B's read goes on after A dropped", b.transfers.lifeLog.isActive)
        assertNull(b.transfers.lifeLog.lastError)
        b.onGattLost()
        withTimeout(5_000) { readB.await() }
    }

    @Test fun aWorkoutReadOnARunsBesideOneOnBAndWritesOnlyOnA(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = watch("A", gattA)
        val b = watch("B", gattB)
        a.transfers.workouts.sink = { true }
        b.transfers.workouts.sink = { true }
        val readA = async { a.transfers.workouts.download(userAsked = true) }
        waitUntil("A's workout read holds its link", 5_000) { a.transfers.workouts.isActive && gattA.writes().isNotEmpty() }
        assertFalse(b.transfers.workouts.isActive)
        assertFalse(b.transfers.busy)
        assertEquals(emptyList<String>(), gattB.writes())

        val readB = async { b.transfers.workouts.download(userAsked = true) }
        waitUntil("B's workout read runs beside A's", 5_000) { b.transfers.workouts.isActive && gattB.writes().isNotEmpty() }
        assertTrue(a.transfers.workouts.isActive)

        links.close("A")
        val outA = withTimeout(5_000) { readA.await() }
        assertTrue("A's read ended with its link: $outA", outA?.error != null)
        assertTrue("B's read goes on after A closed", b.transfers.workouts.isActive)
        assertNull(b.transfers.workouts.lastError)
        links.close("B")
        withTimeout(5_000) { readB.await() }
    }

    @Test fun aPauseOrAnMtuFromAHoldsOrSizesOnlyAsTexts(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = watch("A", gattA)
        val b = watch("B", gattB)
        listOf(a, b).forEach {
            it.notifications.sender.spacingMs = 1
            it.notifications.sender.holdPollMs = 5
        }
        a.notifications.gate.onManager("0x3D 10")
        a.notifications.gate.onCondition("0x28 00 00 00 00 B9 00 00 00 00")
        assertFalse(a.notifications.gate.allowed())
        assertTrue(b.notifications.gate.allowed())
        assertEquals(181, a.notifications.gate.chunkSize())
        assertEquals(20, b.notifications.gate.chunkSize())

        val textA = a.notifications.sender.send(ByteArray(30) { 1 })
        val textB = b.notifications.sender.send(ByteArray(30) { 2 })
        assertEquals("B's text goes out while A's watch paused", NotificationSender.Outcome.SENT, withTimeout(5_000) { textB.await() })
        assertEquals("B in chunks of 20", listOf(20, 10), gattB.writes().map { it.split(' ').size - 1 })
        assertFalse(textA.isCompleted)
        assertEquals(emptyList<String>(), gattA.writes())

        a.notifications.gate.onManager("0x3D 02")
        assertEquals(NotificationSender.Outcome.SENT, withTimeout(5_000) { textA.await() })
        assertEquals("A in one chunk of its MTU", listOf(30), gattA.writes().map { it.split(' ').size - 1 })
        assertEquals("B wrote nothing for A", 2, gattB.writes().size)
    }
}
