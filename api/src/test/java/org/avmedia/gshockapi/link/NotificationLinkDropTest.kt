package org.avmedia.gshockapi.link

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.casio.NotificationSender
import org.avmedia.gshockapi.log.TestLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** A text in several chunks and a link that drops between two of them: the watch never gets the rest alone. */
class NotificationLinkDropTest {
    init { TestLog.install() }

    @get:Rule val timeout: Timeout = Timeout.seconds(30)

    private val links = testLinks()
    private val side = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() = side.cancel()

    private fun sizes(gatt: FakeGatt) = gatt.writes().map { it.split(' ').size - 1 }

    /** The link of [link] drops and comes back [awayMs] later, after the write number [after] of [gatt]. */
    private fun dropAfter(link: WatchLink, gatt: FakeGatt, after: Set<Int>, awayMs: Long = 60) {
        val count = AtomicInteger()
        gatt.onWrite = {
            if (count.incrementAndGet() in after) {
                side.launch {
                    delay(15) // the chunk's write result came, the next chunk waits for its spacing
                    link.onGattLost()
                    delay(awayMs)
                    link.onGattReady().await()
                }
            }
        }
    }

    @Test fun aDropAfterTheFirstChunkSendsTheWholeTextOnceMoreOnTheNextLink(): Unit = runBlocking {
        val gatt = FakeGatt()
        val link = readyLink(links, "A", gatt)
        link.notifications.sender.spacingMs = 150
        link.notifications.sender.holdPollMs = 5
        dropAfter(link, gatt, after = setOf(1))
        TestLog.clear()
        val text = ByteArray(50) { it.toByte() }
        val outcome = withTimeout(10_000) { link.notifications.sender.send(text).await() }
        assertEquals(NotificationSender.Outcome.SENT, outcome)
        // The first chunk on the old link, then all three on the new one: never chunk two or three alone.
        assertEquals(listOf(20, 20, 20, 10), sizes(gatt))
        val sent = gatt.writes().drop(1).flatMap { w -> w.split(' ').drop(1).map { it.toInt(16).toByte() } }
        assertEquals("the text on the new link is the whole text", text.toList(), sent)
        val lines = TestLog.recentEvents()
        assertTrue(lines.toString(), lines.any { it.endsWith("notification text: the link dropped between two chunks, the whole text is sent once more") })
        assertTrue(lines.toString(), lines.any { it.endsWith("notification text sent (50 bytes)") })
    }

    @Test fun aSecondDropDropsTheTextAndSaysSo(): Unit = runBlocking {
        val gatt = FakeGatt()
        val link = readyLink(links, "A", gatt)
        link.notifications.sender.spacingMs = 150
        link.notifications.sender.holdPollMs = 5
        // The first chunk of the first try and the first chunk of the second try are each followed by a drop.
        dropAfter(link, gatt, after = setOf(1, 2))
        TestLog.clear()
        val outcome = withTimeout(10_000) { link.notifications.sender.send(ByteArray(50)).await() }
        assertEquals(NotificationSender.Outcome.WRITE_FAILED, outcome)
        assertEquals("only the two first chunks went out", listOf(20, 20), sizes(gatt))
        val lines = TestLog.recentEvents()
        assertTrue(lines.toString(), lines.any { it.endsWith("notification text: the link dropped again between two chunks") })
        assertTrue(lines.toString(), lines.any { it.endsWith("notification text dropped: a chunk write failed") })
    }

    @Test fun aTextThatFitsOneChunkIsNotSentTwice(): Unit = runBlocking {
        val gatt = FakeGatt()
        val link = readyLink(links, "A", gatt)
        dropAfter(link, gatt, after = setOf(1))
        val outcome = withTimeout(10_000) { link.notifications.sender.send(ByteArray(12)).await() }
        assertEquals(NotificationSender.Outcome.SENT, outcome)
        delay(200)
        assertEquals(listOf(12), sizes(gatt))
    }
}
