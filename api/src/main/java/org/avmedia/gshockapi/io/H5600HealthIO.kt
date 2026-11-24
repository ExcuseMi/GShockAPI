package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.StateFlow
import org.avmedia.gshockapi.casio.WatchLink
import org.avmedia.gshockapi.link.JobScope
import org.avmedia.gshockapi.model.StepCounterData

/**
 * The single-watch LifeLog read: the [H5600LifeLogReader] of the one link. New code takes the reader of its own link
 * (`link.transfers.lifeLog`).
 */
@RequiresApi(Build.VERSION_CODES.O)
object H5600HealthIO {
    private val reader: H5600LifeLogReader get() = WatchLink.transfers.lifeLog

    val status: StateFlow<String?> get() = reader.status
    val lastError: String? get() = reader.lastError
    val isActive: Boolean get() = reader.isActive

    var afterRead: (ByteArray?) -> Unit
        get() = reader.afterRead
        set(value) { reader.afterRead = value }

    var workoutsFirst: suspend (JobScope) -> Unit
        get() = reader.workoutsFirst
        set(value) { reader.workoutsFirst = value }

    internal var readyWaitMs: Long
        get() = reader.readyWaitMs
        set(value) { reader.readyWaitMs = value }

    internal var stepCounterSupported: () -> Boolean
        get() = reader.stepCounterSupported
        set(value) { reader.stepCounterSupported = value }

    fun channelOf(hex: String): H5600Channel = H5600LifeLogReader.channelOf(hex)

    fun onDrsp(hex: String) = reader.onDrsp(hex)

    fun onConvoy(hex: String) = reader.onConvoy(hex)

    fun onStatus(hex: String) = reader.onStatus(hex)

    fun onLinkLost() = reader.onLinkLost()

    fun isAwaitingSetupReply(): Boolean = reader.isAwaitingSetupReply()

    suspend fun request(peek: Boolean = true): StepCounterData = reader.request(peek)

    internal fun syncFor(peek: Boolean): H5600LifeLogSync = reader.syncFor(peek)

    internal fun bytesOf(hex: String): ByteArray = h5600Bytes(hex)
}
