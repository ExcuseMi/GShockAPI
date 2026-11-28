package org.avmedia.gshockapi.io

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.avmedia.gshockapi.casio.Outcome
import org.avmedia.gshockapi.link.JobOutcome
import org.avmedia.gshockapi.link.JobSpec

/** A read of a watch value that got no answer: its request failed (refused, the link gone) or nothing came in time. */
class WatchAnswerFailed(message: String) : Exception(message)

/**
 * One answer a read of a link waits for: the read asks, the link's watch completes it. Only the read that asked last
 * gets the answer; an answer with nobody waiting is dropped. Made only by the per-link objects of its link.
 */
internal class LinkAnswer<T> {
    @Volatile private var pending: CompletableDeferred<T>? = null

    /** True while a read waits for its answer. */
    val waiting: Boolean get() = pending != null

    /**
     * Waits for the answer after [send] put the request on the link. [send] may give the command job of its request:
     * when that job fails (refused, the link gone, its deadline) the read ends at once with [WatchAnswerFailed]. Any
     * read ends with it after [limitMs], which covers the wait behind other jobs (a command's own deadline) and the
     * answer. Before, a read whose answer never came waited for ever; reads take turns (see [askOrFail]).
     */
    suspend fun ask(what: String = "watch value", limitMs: Long = ASK_LIMIT_MS, send: () -> Any?): T =
        withTimeoutOrNull(limitMs) {
            Box(
                askOrFail { fail ->
                    val sent = send()
                    if (sent is Deferred<*>) sent.invokeOnCompletion { commandFailure(sent)?.let { fail(WatchAnswerFailed("$what: $it")) } }
                },
            )
        }?.value ?: throw WatchAnswerFailed("$what: no answer within ${limitMs / 1000} s")

    private class Box<T>(val value: T)

    /** One [askOrFail] at a time, from its request to its answer: the next one waits here. */
    private val turn = Mutex()

    /**
     * As [ask], and [send] gets a way to end this very read with an error (its request failed: no answer, refused, the
     * link gone). Without it a read whose request failed waited for ever, and with it everything after it.
     *
     * These reads take turns: the answer names no asker, so two that waited together shared one place, the first
     * answer went to the read that asked last and the other read waited for ever. A read that is given up (its
     * caller's time limit) leaves its place and the turn, and fails nobody else.
     */
    suspend fun askOrFail(send: (fail: (Throwable) -> Unit) -> Unit): T = turn.withLock {
        val deferred = CompletableDeferred<T>()
        synchronized(this) { pending = deferred }
        try {
            send { error ->
                synchronized(this) { if (pending === deferred) pending = null }
                deferred.completeExceptionally(error)
            }
            deferred.await()
        } finally {
            synchronized(this) { if (pending === deferred) pending = null }
        }
    }

    fun complete(value: T) = end { it.complete(value) }

    fun fail(error: Throwable) = end { it.completeExceptionally(error) }

    private fun end(how: (CompletableDeferred<T>) -> Unit) {
        synchronized(this) {
            val current = pending ?: return
            how(current)
            if (pending === current) pending = null
        }
    }

    companion object {
        /** The longest [ask] waits: a command's deadline in the queue, then half a minute for the answer. */
        const val ASK_LIMIT_MS: Long = JobSpec.COMMAND_DEADLINE_MS + 30_000L

        /** Why the command job [sent] did not get its frame out, null when it did or it is no command job. */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        internal fun commandFailure(sent: Deferred<*>): String? =
            when (val out = runCatching { sent.getCompleted() }.getOrNull()) {
                is JobOutcome.Done<*> -> (out.value as? Outcome)?.let { if (it.ok) null else it.reason ?: "failed" }
                is JobOutcome.Failed -> out.detail
                null -> "cancelled"
                else -> null
            }
    }
}
