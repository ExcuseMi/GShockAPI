package org.avmedia.gshockapi.link

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.casio.WatchLink as SingleWatchLink
import org.avmedia.gshockapi.log.TestLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** A `39` value the watch sends while a LifeLog read holds the link is answered right after the read's attempt ends. */
class TimeRequestDuringTransferTest {
    init { TestLog.install() }

    @get:Rule val timeout: Timeout = Timeout.seconds(40)

    private val links = testLinks()

    private val kind14 = "0x10 17 62 07 38 85 CD 7F 0E 03 0F FF FF FF FF 24 00 00 00"

    private fun answer(link: WatchLink, hex: String) {
        link.onNotification(CasioConstants.CASIO_ALL_FEATURES_CHARACTERISTIC_UUID, SingleWatchLink.parseHex(hex))
        if (link.transfers.lifeLog.isAwaitingSetupReply()) link.transfers.lifeLog.onStatus(hex)
    }

    @Test fun theWatchAsksForTheTimeDuringALifeLogReadAndGetsItsAnswerAfterTheAttempt(): Unit = runBlocking {
        val gatt = FakeGatt()
        val link = readyLink(links, "A", gatt)
        val setup = link.connection.setup
        // The setup of a kind 14 connection, up to its end (39 04 answered with 39 04) and the watch's 47 01.
        setup.reset()
        setup.onKind(kind14)
        waitUntil("0x23 asked") { "w 23" in gatt.writes() }
        answer(link, "0x23 41 42")
        setup.on23("0x23 41 42")
        // The setup's reads are exchanges: each is answered before the next goes out.
        for ((n, code) in listOf(0x26, 0x28, 0x20, 0x28, 0x39).withIndex()) {
            waitUntil("read $n of the setup (0x%02X)".format(code)) { gatt.writes().count { it.length == 4 } == n + 2 }
            val hex = if (code == 0x39) "0x39 04" else "0x%02X 00 00 00 00 00 00 00 00 00".format(code)
            answer(link, hex)
            if (code == 0x28) setup.on28(hex)
            if (code == 0x39) setup.on39(hex)
        }
        waitUntil("39 04 written") { "w 39 04" in gatt.writes() }
        link.connection.keepAlive.onSetup("47 01")
        link.transfers.lifeLog.stepCounterSupported = { true }
        link.transfers.lifeLog.readyWaitMs = 300

        val read = async { link.transfers.lifeLog.request() }
        waitUntil("the read asks 0x43", 10_000) { link.transfers.lifeLog.isAwaitingSetupReply() && "w 43" in gatt.writes() }
        link.connection.routes.own.getValue(0x39)("0x39 00")
        delay(300)
        assertFalse("not answered while the read runs: ${gatt.writes()}", "w 39 03" in gatt.writes())

        // The read goes on; the watch never gets ready, so the attempt ends and the held value is answered.
        answer(link, "0x43 40 1F 00")
        waitUntil("the read asks 0x28", 10_000) { link.transfers.lifeLog.isAwaitingSetupReply() && gatt.writes().count { it == "w 28" } >= 3 }
        answer(link, "0x28 00 00 00 00 00 00 00 00 00")
        waitUntil("39 03 after the attempt", 20_000) { "w 39 03" in gatt.writes() }
        assertTrue(TestLog.recentEvents().any { it.endsWith("transfer ended: the held 39 value is answered") })

        link.onGattLost()
        withTimeout(10_000) { read.await() }
    }
}
