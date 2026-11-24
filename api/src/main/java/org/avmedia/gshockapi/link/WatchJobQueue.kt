package org.avmedia.gshockapi.link

import java.util.TreeSet
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.casio.Outcome
import org.avmedia.gshockapi.casio.WatchQueue

/** DISCONNECTED: no GATT. SETTING_UP: GATT up, only the setup job runs. READY: every job may run. */
enum class LinkState { DISCONNECTED, SETTING_UP, READY }

/**
 * The jobs of one watch link and the only way to its frame sequencer ([WatchQueue]): one job holds the link at a time.
 * The next one is the waiting job with the highest priority; within a priority the sources take turns (round robin),
 * and a source's own jobs run in the order asked; the setup after a
 * (re)connect goes before all of them and nothing else runs until it ended. A long job gives the link up at its block
 * boundaries ([JobScope.yieldPoint]) when a job of higher priority waits, and gets it back before any job of its own
 * priority that came later.
 */
class WatchJobQueue internal constructor(
    private val scope: CoroutineScope,
    private val frames: () -> WatchQueue,
    initial: LinkState = LinkState.DISCONNECTED,
) {
    private class Ticket(val spec: JobSpec, val rank: Int, val seq: Long, val setup: Boolean) {
        /** The round of its source within its priority: set once, before the ticket first waits. */
        var round = 0L
        var granted = CompletableDeferred<Unit>()
        var started = false

        @Volatile var endReason: FailReason? = null
        var job: Job? = null
    }

    private val lock = Any()
    private val waiting = TreeSet<Ticket>(compareBy<Ticket>({ it.rank }, { it.round }, { it.seq }))
    private val live = mutableSetOf<Ticket>()
    private var holder: Ticket? = null
    private val seq = AtomicLong()

    /** Per priority: the round of the job that got the link last. */
    private val servedRound = HashMap<Int, Long>()

    /** Per priority and source: the round its next job gets. */
    private val nextRound = HashMap<Pair<Int, JobSource>, Long>()
    private var setupGeneration = 0L

    /** Until when a job that starts quiet waits ([JobSpec.settleMs] of the jobs that ended). */
    private var quietUntil = 0L
    private var quietTimerAt = 0L
    private val _state = MutableStateFlow(initial)

    val state: StateFlow<LinkState> = _state

    /** The jobs not ended yet (waiting or running), for status lines and tests. */
    fun pending(): Int = synchronized(lock) { live.size }

    /** True while a job holds the link, for status lines. */
    fun busy(): Boolean = synchronized(lock) { holder != null }

    /** Asks for a job. The result always completes: done, or failed with a reason. */
    fun <T> submit(spec: JobSpec, block: suspend JobScope.() -> T): Deferred<JobOutcome<T>> =
        start(spec, spec.priority.ordinal, setup = false, block)

    /** The GATT is up: the setup runs first, and alone; then the link is ready. A setup still running is ended first. */
    internal fun linkUp(setup: suspend JobScope.() -> Unit): Deferred<JobOutcome<Unit>> {
        val generation: Long
        synchronized(lock) {
            generation = ++setupGeneration
            _state.value = LinkState.SETTING_UP
        }
        val spec = JobSpec("connection setup", JobSource.System("link"), JobPriority.USER, SETUP_DEADLINE_MS, OnDisconnect.CANCEL)
        return start(spec, SETUP_RANK, setup = true) {
            try {
                setup()
            } finally {
                synchronized(lock) {
                    if (generation == setupGeneration && _state.value == LinkState.SETTING_UP) _state.value = LinkState.READY
                }
            }
        }
    }

    /** The link dropped: jobs with [OnDisconnect.CANCEL] and the setup end now, the others wait for the link (up to their deadline). */
    internal fun linkDown(reason: String) {
        val ending = synchronized(lock) {
            _state.value = LinkState.DISCONNECTED
            live.filter { it.setup || it.spec.onDisconnect == OnDisconnect.CANCEL }.onEach { it.endReason = FailReason.DISCONNECTED }
        }
        ending.forEach { it.job?.cancel() }
        frames().clear(reason)
    }

    /**
     * The single-watch adapter only: the app closed its manager. Like [linkDown], then the link counts as ready again, so a
     * write fails at once (no manager) as before the queue existed, instead of waiting for a link nobody will open.
     */
    internal fun detach(reason: String) {
        linkDown(reason)
        synchronized(lock) {
            if (_state.value == LinkState.DISCONNECTED) _state.value = LinkState.READY
            dispatchLocked()
        }
    }

    /** The link is gone for good: every job ends. */
    internal fun close(reason: String) {
        synchronized(lock) { _state.value = LinkState.DISCONNECTED }
        endAll(reason)
    }

    /** Every job ends ([FailReason.LINK_CLOSED]); the state stays. */
    internal fun endAll(reason: String) {
        val ending = synchronized(lock) { live.toList().onEach { it.endReason = FailReason.LINK_CLOSED } }
        ending.forEach { it.job?.cancel() }
        frames().clear(reason)
    }

    private fun <T> start(spec: JobSpec, rank: Int, setup: Boolean, block: suspend JobScope.() -> T): Deferred<JobOutcome<T>> {
        val t = Ticket(spec, rank, seq.incrementAndGet(), setup)
        val result = CompletableDeferred<JobOutcome<T>>()
        var outcome: JobOutcome<T>? = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            outcome =
                try {
                    withTimeout(spec.deadlineMs) {
                        t.granted.await()
                        try {
                            JobOutcome.Done(TicketScope(t).block())
                        } finally {
                            release(t)
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    JobOutcome.Failed(FailReason.DEADLINE, deadlineText(t))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    JobOutcome.Failed(FailReason.ERROR, e.message ?: e.javaClass.simpleName)
                }
        }
        t.job = job
        job.invokeOnCompletion {
            synchronized(lock) {
                live.remove(t)
                if (waiting.remove(t) || holder === t) {
                    if (holder === t) holder = null
                    dispatchLocked()
                }
            }
            // Completed after the clean-up above: a caller that sees the result also sees the job gone from [pending].
            result.complete(outcome ?: JobOutcome.Failed(t.endReason ?: FailReason.CANCELLED, endText(t)))
        }
        result.invokeOnCompletion { if (it != null) job.cancel() }
        // In the line before the job's coroutine runs: the order of asking is the order of running.
        synchronized(lock) {
            live += t
            t.round = roundLocked(t)
            enlistLocked(t)
        }
        job.start()
        return result
    }

    private fun deadlineText(t: Ticket): String {
        val s = t.spec.deadlineMs / 1000
        return when {
            t.started -> "${t.spec.name} did not finish within $s s."
            _state.value != LinkState.READY -> "The watch was not connected within $s s."
            else -> "${t.spec.name} waited $s s behind other jobs."
        }
    }

    private fun endText(t: Ticket): String = when (t.endReason) {
        FailReason.DISCONNECTED -> "The watch disconnected."
        FailReason.LINK_CLOSED -> "The link to the watch was closed."
        else -> "Cancelled."
    }

    /**
     * Round robin between the sources of one priority: a source's jobs take rounds one after the other, and a source that
     * had nothing waiting starts at the round being served now. So one plugin with many jobs cannot hold back another.
     */
    private fun roundLocked(t: Ticket): Long {
        val key = t.rank to t.spec.source
        val round = maxOf(nextRound[key] ?: 0L, servedRound[t.rank] ?: 0L)
        nextRound[key] = round + 1
        return round
    }

    private fun enlistLocked(t: Ticket) {
        t.granted = CompletableDeferred()
        waiting += t
        dispatchLocked()
    }

    /** Whether [t] may hold the link in the current state. */
    private fun eligible(t: Ticket): Boolean = when (_state.value) {
        LinkState.READY -> true
        LinkState.SETTING_UP -> t.setup
        LinkState.DISCONNECTED -> false
    }

    /** A job's first turn waits for the quiet after the jobs that ended; a job that already ran resumes at once. */
    private fun quiet(t: Ticket, now: Long): Boolean = t.started || !t.spec.startsQuiet || now >= quietUntil

    private fun dispatchLocked() {
        if (holder != null) return
        val now = System.currentTimeMillis()
        val next = waiting.firstOrNull { eligible(it) && quiet(it, now) }
        if (next == null) {
            if (waiting.any { eligible(it) }) wakeAtQuietLocked()
            return
        }
        waiting.remove(next)
        holder = next
        next.started = true
        servedRound[next.rank] = maxOf(servedRound[next.rank] ?: 0L, next.round)
        next.granted.complete(Unit)
    }

    /** One timer for the end of the quiet: then the waiting job that starts quiet gets its turn. */
    private fun wakeAtQuietLocked() {
        if (quietTimerAt == quietUntil) return
        quietTimerAt = quietUntil
        val left = quietUntil - System.currentTimeMillis()
        scope.launch {
            delay(left)
            synchronized(lock) {
                quietTimerAt = 0L
                dispatchLocked()
            }
        }
    }

    /** [t] ended: its settle starts now. */
    private fun release(t: Ticket) = synchronized(lock) {
        if (t.started && t.spec.settleMs > 0) quietUntil = maxOf(quietUntil, System.currentTimeMillis() + t.spec.settleMs)
        if (holder === t) {
            holder = null
            dispatchLocked()
        }
    }

    /** Gives the link up and waits until it is this job's turn again (same place in the line). */
    private suspend fun reacquire(t: Ticket) {
        synchronized(lock) {
            if (holder === t) holder = null
            enlistLocked(t)
        }
        t.granted.await()
    }

    private inner class TicketScope(private val t: Ticket) : JobScope {
        override val spec: JobSpec get() = t.spec

        override suspend fun exchange(handle: GetSetMode, data: ByteArray): Outcome {
            if (!synchronized(lock) { eligible(t) }) reacquire(t)
            return frames().enqueue(handle, data).await()
        }

        override fun post(handle: GetSetMode, data: ByteArray): Deferred<Outcome> {
            if (!synchronized(lock) { holder === t }) {
                return CompletableDeferred(Outcome.failed("${t.spec.name}: not sent, the job does not hold the link"))
            }
            return frames().enqueue(handle, data)
        }

        override suspend fun yieldPoint() {
            val give = synchronized(lock) { !eligible(t) || waiting.any { it.rank < t.rank && eligible(it) } }
            if (give) reacquire(t)
        }
    }

    companion object {
        private const val SETUP_RANK = -1
        const val SETUP_DEADLINE_MS = 30_000L
    }
}
