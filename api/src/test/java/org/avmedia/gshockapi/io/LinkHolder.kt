package org.avmedia.gshockapi.io

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.casio.WatchLink
import org.avmedia.gshockapi.link.JobPriority
import org.avmedia.gshockapi.link.JobSource
import org.avmedia.gshockapi.link.JobSpec

/** A job of the tests that holds the single-watch link (as a LifeLog read or a settings read would) until the gate completes. */
internal suspend fun holdTheLink(priority: JobPriority = JobPriority.USER): CompletableDeferred<Unit> {
    val gate = CompletableDeferred<Unit>()
    val holding = CompletableDeferred<Unit>()
    WatchLink.link.submit(JobSpec("test holder", JobSource.System("test"), priority)) {
        holding.complete(Unit)
        gate.await()
    }
    withTimeout(5_000) { holding.await() }
    return gate
}
