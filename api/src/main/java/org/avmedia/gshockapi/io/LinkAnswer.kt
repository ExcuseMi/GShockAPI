package org.avmedia.gshockapi.io

import kotlinx.coroutines.CompletableDeferred

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
