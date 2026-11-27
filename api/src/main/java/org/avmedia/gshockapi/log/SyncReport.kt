package org.avmedia.gshockapi.log

import timber.log.Timber

/** Who hears how each sync of the library ended ([SyncReport]). */
fun interface SyncReportSink {
    fun report(kind: String, ok: Boolean, durationMs: Long, detail: String?)
}

/**
 * One call per sync attempt the library makes by itself or for the app (the time sync so far), with its outcome and how
 * long it took, so the app can count them. Without a sink nothing is kept.
 */
object SyncReport {
    const val TIME = "time"

    @Volatile var sink: SyncReportSink? = null

    /** A failing sink never breaks the sync. */
    fun report(kind: String, ok: Boolean, durationMs: Long, detail: String?) {
        runCatching { sink?.report(kind, ok, durationMs, detail) }.onFailure { Timber.w("SyncReport: the sink failed (%s)", it.javaClass.simpleName) }
    }
}
