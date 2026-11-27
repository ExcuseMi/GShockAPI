package org.avmedia.gshockapi.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.casio.WatchLink as SingleWatchLink
import org.avmedia.gshockapi.io.IO
import org.avmedia.gshockapi.log.TestLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Each link has its own time: its time zone, its DST, world city and home time reads and its time write. */
class LinkTimeTest {
    init { TestLog.install() }

    @get:Rule val timeout: Timeout = Timeout.seconds(30)

    private val links = testLinks()
    private val watches = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() = watches.cancel()

    private val replies = mapOf(
        0x1D to "1D 00 01 00 02 34 12 00 00 FF FF FF FF FF FF",
        0x1E to "1E %02X 02 76 10 00 00",
        0x1F to "1F %02X 54 4F 4B 59 4F 00 00 00 00 00 00 00 00 00 00 00 00 00",
        0x24 to "24 %02X 54 4F 4B 59 4F 00 00 00 00 00 00 00 00 00 00 00 00 00",
    )

    /** The answer [link]'s watch gives: to the frame sequencer that waits and to the link's own time routes. */
    private fun answer(link: WatchLink, hex: String) {
        link.onNotification(CasioConstants.CASIO_ALL_FEATURES_CHARACTERISTIC_UUID, SingleWatchLink.parseHex(hex))
        link.time.routes[SingleWatchLink.parseHex(hex)[0].toInt() and 0xFF]?.invoke(hex)
    }

    /** [gatt] plays [link]'s watch: every two-byte read of a time register is answered on that link. */
    private fun watch(link: WatchLink, gatt: FakeGatt) {
        gatt.onWrite = { frame ->
            val code = frame[0].toInt() and 0xFF
            val reply = replies[code]
            if (frame.size == 2 && reply != null) {
                watches.launch { delay(5); answer(link, reply.format(frame[1].toInt() and 0xFF)) }
            }
        }
    }

    @Test fun eachLinkAndTheSingleWatchLinkHaveTheirOwnTime(): Unit = runBlocking {
        val a = readyLink(links, "A", FakeGatt())
        val b = readyLink(links, "B", FakeGatt())
        assertNotSame(a.time, b.time)
        assertNotSame(a.time, SingleWatchLink.time)
        assertNotSame(a.time.routes, b.time.routes)
    }

    @Test fun aDstReadOnAWritesOnATakesOnlyAsAnswerAndKeepsItInAsCache(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", gattB)
        val readA = async { a.time.requestDstWatchState(IO.DstState.ZERO) }
        waitUntil("1D 00 asked on A") { gattA.writes() == listOf("w 1D 00") }
        assertEquals(emptyList<String>(), gattB.writes())

        b.time.onDstWatchState("1D 00 01 03 02 34 12 00 00 FF FF FF FF FF FF")
        SingleWatchLink.time.onDstWatchState("1D 00 01 03 02 34 12 00 00 FF FF FF FF FF FF")
        delay(200)
        assertFalse("A took B's or the single link's answer", readA.isCompleted)
        answer(a, "1D 00 01 00 02 34 12 00 00 FF FF FF FF FF FF")
        assertEquals("1D 00 01 00 02 34 12 00 00 FF FF FF FF FF FF", withTimeout(3_000) { readA.await() })
        assertEquals("1D 00 01 00 02 34 12 00 00 FF FF FF FF FF FF", a.cache.get<String>("1D00"))
        assertEquals("B asks its own watch", "B", b.cache.request("1D00") { "B" })
    }

    @Test fun eachWorldCityAndHomeTimeReadTakesOnlyItsLinksAnswer(): Unit = runBlocking {
        val gattA = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", FakeGatt())
        val reads = listOf<Pair<suspend () -> String, String>>(
            suspend { a.time.requestDstForWorldCities(1) } to "1E 01 02 76 10 00 00",
            suspend { a.time.requestWorldCities(1) } to "1F 01 41",
            suspend { a.time.requestHomeTime(1) } to "24 01 41",
        )
        for ((read, reply) in reads) {
            val r = async { read() }
            waitUntil("$reply asked on A") { gattA.writes().size == reads.indexOfFirst { it.second == reply } + 1 }
            b.time.routes.values.forEach { it(reply) }
            delay(100)
            assertFalse("A took B's answer $reply", r.isCompleted)
            answer(a, reply)
            assertEquals(reply, withTimeout(3_000) { r.await() })
        }
        assertEquals(listOf("w 1E 01", "w 1F 01", "w 24 01"), gattA.writes())
    }

    @Test fun aTimeSyncOnAWritesOnlyOnAInAsOwnTimeZone(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        val b = readyLink(links, "B", gattB)
        watch(a, gattA)
        watch(b, gattB)
        val single = SingleWatchLink.time.timeZone
        a.time.setTimezone("Asia/Tokyo")
        b.time.setTimezone("America/New_York")
        assertEquals("Asia/Tokyo", a.time.timeZone)
        assertEquals("America/New_York", b.time.timeZone)
        assertEquals("the single link keeps its zone", single, SingleWatchLink.time.timeZone)
        assertNotEquals(a.time.casioTimezone.offset, b.time.casioTimezone.offset)

        withTimeout(10_000) { a.time.set(timeMs = 0L) }
        waitUntil("A's time written") { gattA.writes().any { it.startsWith("w 09 ") } }
        assertEquals("B wrote nothing", emptyList<String>(), gattB.writes())
        // The home city of A's zone, not what the watch had.
        assertTrue(gattA.writes().toString(), gattA.writes().any { it.startsWith("w 1F 00 54 4F 4B 59 4F") })
        // The DST and world city values went back to A's watch (GENERIC: one DST block, two cities).
        assertTrue(gattA.writes().toString(), gattA.writes().containsAll(listOf("w 1D 00", "w 1E 00", "w 1E 01", "w 1F 01")))
    }

    /** The end of a registry link's setup writes that link's time: a link has no single-watch time code behind it. */
    @Test fun aLinksSetupWritesItsOwnTime(): Unit = runBlocking {
        val gattA = FakeGatt()
        val gattB = FakeGatt()
        val a = readyLink(links, "A", gattA)
        readyLink(links, "B", gattB)
        watch(a, gattA)
        a.connection.setup.timeWriter()
        waitUntil("A's time written after its setup", 10_000) { gattA.writes().any { it.startsWith("w 09 ") } }
        assertEquals(emptyList<String>(), gattB.writes())
    }

    private val reports = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun hearReports() {
        org.avmedia.gshockapi.log.SyncReport.sink = org.avmedia.gshockapi.log.SyncReportSink { kind, ok, _, detail -> reports += "$kind $ok ${detail.orEmpty()}".trim() }
    }

    @After fun noSink() { org.avmedia.gshockapi.log.SyncReport.sink = null }

    /** [gatt] plays a watch that refuses the read [refused] (`FF 81`) the first [times] times and answers the others. */
    private fun watchRefusing(link: WatchLink, gatt: FakeGatt, refused: String, times: Int = Int.MAX_VALUE) {
        val left = java.util.concurrent.atomic.AtomicInteger(times)
        gatt.onWrite = { frame ->
            val code = frame[0].toInt() and 0xFF
            val reply = replies[code]
            if (frame.size == 2 && reply != null) {
                val asked = frame.joinToString(" ") { "%02X".format(it) }
                val hex = if (asked == refused && left.getAndDecrement() > 0) "FF 81" else reply.format(frame[1].toInt() and 0xFF)
                watches.launch { delay(5); answer(link, hex) }
            }
        }
    }

    /** A read the watch refuses (or never answers) ended nothing: the sync waited for ever and the time was never written. */
    @Test fun aTimeSyncWhoseReadFailsEndsWithTheReasonAndIsReported(): Unit = runBlocking {
        hearReports()
        val gatt = FakeGatt()
        val a = readyLink(links, "A", gatt)
        watchRefusing(a, gatt, "1E 01")
        val failed = runCatching { withTimeout(10_000) { a.time.set(timeMs = 0L) } }.exceptionOrNull()
        assertTrue("ended with $failed", failed is org.avmedia.gshockapi.io.TimeSyncFailed)
        assertTrue(failed!!.message, failed.message!!.startsWith("read 1E01: The watch refused the read"))
        assertFalse("no time written after a failed read", gatt.writes().any { it.startsWith("w 09 ") })
        assertEquals(1, reports.size)
        assertTrue(reports.toString(), reports[0].startsWith("time false read 1E01"))
    }

    @Test fun aTimeSyncThatReachedTheWatchIsReportedOnce(): Unit = runBlocking {
        hearReports()
        val gatt = FakeGatt()
        val a = readyLink(links, "A", gatt)
        watch(a, gatt)
        withTimeout(10_000) { a.time.set(timeMs = 0L) }
        assertTrue("the time write's result is in when the sync ends", gatt.writes().any { it.startsWith("w 09 ") })
        assertEquals(listOf("time true"), reports.toList())
    }

    /** The setup's time write tries once more when a read failed and the watch is still there. */
    @Test fun theSetupsTimeWriteTriesOnceMoreAfterAFailedRead(): Unit = runBlocking {
        hearReports()
        val gatt = FakeGatt()
        val a = readyLink(links, "A", gatt)
        a.timeRetryMs = 20
        watchRefusing(a, gatt, "1D 00", times = 1)
        a.connection.setup.timeWriter()
        waitUntil("the time written at the second try", 10_000) { gatt.writes().any { it.startsWith("w 09 ") } }
        waitUntil("both tries reported") { reports.size == 2 }
        assertTrue(reports.toString(), reports[0].startsWith("time false") && reports[1] == "time true")
    }

    /** Two syncs asked together (the setup's and the app's at a connection) run one after the other, both to the end. */
    @Test fun twoTimeSyncsAtOnceBothEnd(): Unit = runBlocking {
        val gatt = FakeGatt()
        val a = readyLink(links, "A", gatt)
        watch(a, gatt)
        val one = async { a.time.set(timeMs = 0L) }
        val two = async { a.time.set(timeMs = 0L) }
        withTimeout(10_000) { one.await(); two.await() }
        assertEquals(2, gatt.writes().count { it.startsWith("w 09 ") })
    }
}
