package org.avmedia.gshockapi.link

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.log.TestLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The setup after a (re)connect is one job: nothing overlaps it (a 0x22 read during the descriptor writes was refused: "prior command is not finished"). */
class LinkSetupTest {
    init { TestLog.install() }

    @get:Rule val timeout: Timeout = Timeout.seconds(15)

    private val links = testLinks()

    @Test fun aReadAskedTheMomentTheLinkIsUpGoesOnTheAirOnlyAfterEverySubscription() = runBlocking {
        // The app's init asks 0x22 right after DeviceReady, while the descriptor writes were still running.
        val gatt = FakeGatt(subscribeMs = 20)
        val link = links.open("A", gatt)
        gatt.onWrite = { f ->
            if (f[0] == 0x22.toByte()) {
                Thread {
                    Thread.sleep(5)
                    link.onNotification(CasioConstants.CASIO_ALL_FEATURES_CHARACTERISTIC_UUID, bytes(0x22, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 2))
                }.start()
            }
        }
        val setup = link.onGattReady()
        val appInfo = link.submit(spec("app info", JobPriority.USER)) { exchange(GetSetMode.GET, bytes(0x22)) }
        val kind = link.submit(spec("button info", JobPriority.USER)) { exchange(GetSetMode.GET, bytes(0x10)) }
        assertTrue(setup.await().ok)
        assertTrue(withTimeout(3_000) { appInfo.await() }.ok)
        kind.cancel()
        val ops = gatt.ops.toList()
        assertEquals("ops: $ops", listOf("sub 0", "sub 1", "sub 2", "w 22"), ops.take(4))
        assertEquals("no two GATT operations at once: $ops", 1, gatt.maxInFlight.get())
    }

    @Test fun aDropAndReconnectDuringTheSetupNeverRunsTwoSetupsAtOnce() = runBlocking {
        val gatt = FakeGatt(subscribeMs = 30)
        val link = links.open("A", gatt)
        val first = link.onGattReady()
        waitUntil("first subscription") { gatt.ops.isNotEmpty() }
        link.onGattLost()
        val second = link.onGattReady()
        val third = link.onGattReady()
        val job = link.submit(spec("read", JobPriority.USER)) { exchange(GetSetMode.CONVOY, bytes(0x11)) }
        assertTrue(job.await().ok)
        assertTrue((first.await() as JobOutcome.Failed).reason == FailReason.DISCONNECTED)
        listOf(second, third).awaitAll()
        assertEquals(LinkState.READY, link.state.value)
        assertEquals("no two setups at once: ${gatt.ops}", 1, gatt.maxInFlight.get())
        assertEquals("the job ran after the setups: ${gatt.ops}", "w 11", gatt.ops.last())
    }

    @Test fun theSetupWaitsForTheJobThatHeldTheLinkToGiveItUp() = runBlocking {
        val gatt = FakeGatt(writeMs = 20)
        val link = readyLink(links, "A", gatt)
        gatt.ops.clear()
        val onAir = CompletableDeferred<Unit>()
        gatt.onWrite = { if (it[0] == 0x31.toByte()) onAir.complete(Unit) }
        val job = link.submit(spec("settings write", JobPriority.COMMAND)) {
            exchange(GetSetMode.CONVOY, bytes(0x31))
            exchange(GetSetMode.CONVOY, bytes(0x32))
        }
        onAir.await()
        // The watch dropped and came back while 0x31 was on the air: the setup starts after it, before 0x32.
        link.onGattLost()
        link.onGattReady()
        job.await()
        assertEquals(listOf("w 31", "sub 0", "sub 1", "sub 2", "w 32"), gatt.ops.toList())
        assertEquals(1, gatt.maxInFlight.get())
    }
}
