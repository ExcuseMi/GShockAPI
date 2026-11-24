package org.avmedia.gshockapi.io

import CachedIO
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.casio.WatchChannel
import org.avmedia.gshockapi.casio.WatchLink
import org.avmedia.gshockapi.casio.WatchQueue
import org.avmedia.gshockapi.casio.WatchTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Before: a settings read (`0x11`) went out every 4 to 5 s while a workout read held the link. Now the read is a command job:
 * it does not go out while the transfer holds the link, goes once at the transfer's next boundary and gets its answer.
 */
class SettingsReadTransferGateTest {
    private val savedQueue = WatchLink.queue
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun restore() {
        WatchLink.queue = savedQueue
        CachedIO.remove("GATE_TEST")
    }

    @Test fun aSettingsReadDoesNotGoOutWhileATransferHoldsTheLinkAndGoesAtItsBoundary() = runBlocking {
        val sent = CopyOnWriteArrayList<String>()
        WatchLink.queue = WatchQueue(
            transport = WatchTransport { _: WatchChannel, f: ByteArray ->
                sent += "%02X".format(f[0])
                if (f[0] == 0x11.toByte()) scope.launch {
                    delay(20)
                    WatchLink.onNotification(CasioConstants.CASIO_ALL_FEATURES_CHARACTERISTIC_UUID, "0x11 00 00 00 06 00 00 00 00 00")
                }
                true
            },
            readTimeoutMs = 2_000,
        )
        val boundary = CompletableDeferred<Unit>()
        val transfer = WatchLink.transfer("test transfer", 10_000) {
            exchange(GetSetMode.CONVOY, byteArrayOf(0x05, 0x01))
            boundary.await()
            yieldPoint()
            exchange(GetSetMode.CONVOY, byteArrayOf(0x05, 0x02))
        }
        while (sent.isEmpty()) delay(10)
        val read = async(Dispatchers.Default) { CachedIO.request("GATE_TEST") { WatchLink.writeChecked(GetSetMode.GET, byteArrayOf(0x11)) } }
        delay(400)
        assertEquals("sent while the transfer holds the link: $sent", listOf("05"), sent.toList())
        boundary.complete(Unit)
        assertTrue("the read got its answer", withTimeout(3_000) { read.await() })
        assertTrue(transfer.await().ok)
        assertEquals("the read once, at the boundary", listOf("05", "11", "05"), sent.toList())
    }
}
