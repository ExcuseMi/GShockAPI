package org.avmedia.gshockapi.io

import kotlinx.coroutines.delay

/**
 * One time write that reached the watch: the instant it was stamped with ([setToMs], on the clock of [WatchClock.now]
 * plus the caller's offset), how far ahead of the hand-over that was ([leadMs]), whether it was sent to land on a whole
 * second ([onSecond]) and how long the watch took to acknowledge it ([ackMs]).
 */
data class TimeWrite(val watch: String, val setToMs: Long, val leadMs: Long, val onSecond: Boolean, val ackMs: Long?)

/**
 * The clock a time sync reads when its caller names no time: the phone's clock unless the app puts a better one in
 * (network time). [prepare] runs at the start of each such sync, beside its reads (the app refreshes its clock there;
 * it must end by itself within a few seconds). [written] hears every time write that reached a watch.
 */
object WatchClock {
    @Volatile var now: () -> Long = System::currentTimeMillis

    @Volatile var prepare: suspend () -> Unit = {}

    @Volatile var written: ((TimeWrite) -> Unit)? = null
}

/**
 * How far ahead a time has to be stamped so it is right when it reaches the watch, from how long the watch took to
 * acknowledge the last writes. An acknowledged write issued right after another takes about two connection intervals;
 * one issued at any other moment waits half an interval on average before it goes out: a quarter of the usual
 * acknowledge time. The middle of the last [KEEP] values, so one slow write does not move it; never more than [MAX_MS].
 *
 * The idea and the quarter are adapted from G-Sync by krax1337 (https://github.com/krax1337/gsync), Apache License 2.0.
 */
class WriteLead {
    private val acks = ArrayDeque<Long>()

    /** One acknowledged write took [ackMs]. */
    @Synchronized fun add(ackMs: Long) {
        if (ackMs < 0) return
        acks.addLast(ackMs)
        while (acks.size > KEEP) acks.removeFirst()
    }

    /** The usual acknowledge time, null before the first write. */
    @Synchronized fun ackMs(): Long? = if (acks.isEmpty()) null else acks.sorted()[acks.size / 2]

    /** The lead for the next time write: 0 before the first measured write. */
    fun ms(): Long = ((ackMs() ?: 0L) / 4).coerceIn(0L, MAX_MS)

    companion object {
        const val KEEP = 15
        const val MAX_MS = 500L
    }
}

/** The instant to put in a time frame and whether it lands on a whole second. */
internal data class TimeStamp(val ms: Long, val onSecond: Boolean)

/**
 * Waits until [leadMs] before the next whole second of [now] and gives that second: the frame then carries no fraction,
 * so the watch is right whether it applies the fraction or starts its second when the frame arrives. A wait that ended
 * more than [LATE_TOLERANCE_MS] off (a busy or dozing phone, a clock that was set meanwhile) is tried again; after
 * [BOUNDARY_ATTEMPTS] the exact instant plus the lead is given, with its fraction.
 *
 * Adapted from G-Sync by krax1337 (https://github.com/krax1337/gsync), Apache License 2.0.
 */
internal suspend fun stampOnSecond(now: () -> Long, leadMs: Long, wait: suspend (Long) -> Unit = { delay(it) }): TimeStamp {
    repeat(BOUNDARY_ATTEMPTS) {
        val target = Math.floorDiv(now() + leadMs, 1000L) * 1000L + 1000L
        val sendAt = target - leadMs
        wait((sendAt - now()).coerceAtLeast(0L))
        if (now() - sendAt in -LATE_TOLERANCE_MS..LATE_TOLERANCE_MS) return TimeStamp(target, true)
    }
    return TimeStamp(now() + leadMs, false)
}

private const val BOUNDARY_ATTEMPTS = 3
private const val LATE_TOLERANCE_MS = 30L
