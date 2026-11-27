package org.avmedia.gshockapi.io

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One answer a read of a link waits for: the read asks, the link's watch completes it. Only the read that asked last
 * gets the answer; an answer with nobody waiting is dropped. Made only by the per-link objects of its link.
 */
internal class LinkAnswer<T> {
    @Volatile private var pending: CompletableDeferred<T>? = null

    /** True while a read waits for its answer. */
    val waiting: Boolean get() = pending != null

    /** Waits for the answer after [send] put the request on the link. */
    suspend fun ask(send: () -> Unit): T {
        val deferred = CompletableDeferred<T>()
        synchronized(this) { pending = deferred }
        send()
        return deferred.await()
    }

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
}
