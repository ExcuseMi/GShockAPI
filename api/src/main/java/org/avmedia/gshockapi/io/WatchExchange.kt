package org.avmedia.gshockapi.io

import kotlinx.coroutines.sync.Mutex

/**
 * Kept so the current apps compile: the exchange lock is gone (every exchange is a job in its link's queue, which
 * orders them), so the library never takes [mutex]. [transferRunning] says whether a transfer of the single-watch
 * link runs. New code asks its link (`link.transfers`) or its [org.avmedia.gshockapi.WatchApi].
 */
@Deprecated("Single-watch: the link's job queue orders the exchanges; ask the link's transfers")
object WatchExchange {
    /** Never locked by the library. */
    val mutex = Mutex()

    /** True while a LifeLog, workout or tide transfer of the single-watch link runs. */
    fun transferRunning(): Boolean = H5600HealthIO.isActive || H5600WorkoutIO.isActive || TideTransferIO.isActive
}
