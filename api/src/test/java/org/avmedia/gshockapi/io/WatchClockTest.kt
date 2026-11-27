package org.avmedia.gshockapi.io

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The lead of a time write (from how long the watch acknowledges) and the wait for the whole second. */
class WatchClockTest {
    @Test fun theLeadIsAQuarterOfTheUsualAcknowledgeTime() {
        val lead = WriteLead()
        assertNull(lead.ackMs())
        assertEquals("no write measured yet", 0L, lead.ms())
        listOf(100L, 96L, 104L).forEach(lead::add)
        assertEquals(100L, lead.ackMs())
        assertEquals(25L, lead.ms())
    }

    @Test fun oneSlowWriteDoesNotMoveTheLead() {
        val lead = WriteLead()
        listOf(60L, 60L, 4_000L, 60L, 60L).forEach(lead::add)
        assertEquals(15L, lead.ms())
    }

    @Test fun theLeadFollowsTheLastWritesAndIsCapped() {
        val lead = WriteLead()
        repeat(WriteLead.KEEP) { lead.add(60) }
        repeat(WriteLead.KEEP) { lead.add(400) }
        assertEquals("a slower link (a longer connection interval) after the old values left", 100L, lead.ms())
        repeat(WriteLead.KEEP) { lead.add(9_000) }
        assertEquals(WriteLead.MAX_MS, lead.ms())
        lead.add(-5)
        assertEquals(WriteLead.MAX_MS, lead.ms())
    }

    /** A clock that only moves when the wait is taken, [late] ms more than asked. */
    private class Fake(var nowMs: Long, val late: List<Long> = emptyList()) {
        val waits = mutableListOf<Long>()
        val wait: suspend (Long) -> Unit = { ms ->
            nowMs += ms + (late.getOrNull(waits.size) ?: 0L)
            waits += ms
        }
    }

    @Test fun theFrameIsHandedOverTheLeadBeforeTheNextWholeSecond(): Unit = runBlocking {
        val clock = Fake(1_764_000_000_250L)
        val stamp = stampOnSecond({ clock.nowMs }, leadMs = 40, wait = clock.wait)
        assertEquals(TimeStamp(1_764_000_001_000L, true), stamp)
        assertEquals(listOf(710L), clock.waits)
        assertEquals("handed over 40 ms before the second", 1_764_000_000_960L, clock.nowMs)
    }

    @Test fun insideTheLeadOfASecondTheOneAfterIsTaken(): Unit = runBlocking {
        val clock = Fake(1_764_000_000_980L)
        val stamp = stampOnSecond({ clock.nowMs }, leadMs = 40, wait = clock.wait)
        assertEquals(TimeStamp(1_764_000_002_000L, true), stamp)
        assertEquals(listOf(980L), clock.waits)
    }

    @Test fun aLateWaitIsTriedAgainAndThenTheExactInstantIsSent(): Unit = runBlocking {
        val once = Fake(1_764_000_000_000L, late = listOf(200))
        assertEquals(TimeStamp(1_764_000_002_000L, true), stampOnSecond({ once.nowMs }, 0, once.wait))
        assertEquals(2, once.waits.size)

        val always = Fake(1_764_000_000_000L, late = listOf(200, 200, 200))
        val stamp = stampOnSecond({ always.nowMs }, leadMs = 40, wait = always.wait)
        assertEquals(3, always.waits.size)
        assertEquals("the instant itself plus the lead, with its fraction", TimeStamp(always.nowMs + 40, false), stamp)
    }
}
