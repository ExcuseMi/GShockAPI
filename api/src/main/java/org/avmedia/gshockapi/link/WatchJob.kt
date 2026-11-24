package org.avmedia.gshockapi.link

import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.casio.Outcome

/** Who asked for a job: shown with its result and used for the order within one priority. */
sealed interface JobSource {
    data object User : JobSource

    data class Plugin(val id: String) : JobSource

    data class System(val name: String) : JobSource
}

/** The order between jobs that wait: a tap in the app first, a background read last. Equal priority: first asked, first run. */
enum class JobPriority { USER, NOTIFICATION, COMMAND, BACKGROUND }

/** What a job does when the link drops: wait for it to come back (up to its deadline) or end at once. */
enum class OnDisconnect { WAIT, CANCEL }

/**
 * One job on one watch link. [deadlineMs] bounds the whole job from the moment it is asked: waiting for the link, waiting
 * behind other jobs and running. A job never outlives it.
 */
data class JobSpec(
    val name: String,
    val source: JobSource,
    val priority: JobPriority,
    val deadlineMs: Long = COMMAND_DEADLINE_MS,
    val onDisconnect: OnDisconnect = OnDisconnect.WAIT,
) {
    companion object {
        /** How long a command waits for a watch that is away before it fails (owner 2026-11-24: 3 min). */
        const val COMMAND_DEADLINE_MS = 3 * 60 * 1000L
    }
}

enum class FailReason {
    /** The deadline passed: while the watch was away, behind other jobs, or while running. */
    DEADLINE,

    /** The link dropped and the job's rule is [OnDisconnect.CANCEL]. */
    DISCONNECTED,

    /** The link was closed for good (watch removed, app ends). */
    LINK_CLOSED,

    /** The caller cancelled it. */
    CANCELLED,

    /** The job's own code failed. */
    ERROR,
}

/** How a job ended. Every job ends in exactly one of these. */
sealed class JobOutcome<out T> {
    data class Done<T>(val value: T) : JobOutcome<T>()

    data class Failed(val reason: FailReason, val detail: String) : JobOutcome<Nothing>()

    val ok: Boolean get() = this is Done
}

/** What a running job can do on its link. Only a job can reach the watch. */
interface JobScope {
    val spec: JobSpec

    /** One request or write through the link's frame sequencer. While the link is away it waits (holding nothing). */
    suspend fun exchange(handle: GetSetMode, data: ByteArray): Outcome

    /**
     * A block boundary of a long job: when a job of higher priority waits it runs now, then this job goes on where it was.
     * Only the write already on the air finishes first.
     */
    suspend fun yieldPoint()
}
