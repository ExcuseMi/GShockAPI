package org.avmedia.gshockapi.io

import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.link.JobScope

/**
 * A transfer's writes inside its own job on the link: each frame goes in line in the order written, and at each
 * [boundary] a more urgent job (a tap, a notification, a command) runs first.
 */
class JobWorkoutLink(private val job: JobScope) : WorkoutLink {
    override fun drsp(data: ByteArray) {
        job.post(GetSetMode.DATA_REQUEST, data)
    }

    override fun bulk(data: ByteArray) {
        job.post(GetSetMode.CONVOY, data)
    }

    override suspend fun bulkConfirmed(data: ByteArray): Boolean = job.exchange(GetSetMode.CONVOY, data).ok

    override suspend fun boundary() = job.yieldPoint()
}
