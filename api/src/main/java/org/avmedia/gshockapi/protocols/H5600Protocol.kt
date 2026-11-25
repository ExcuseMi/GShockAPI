package org.avmedia.gshockapi.protocols

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.casio.ConnectionKeepAlive
import org.avmedia.gshockapi.io.H5600Channel
import org.avmedia.gshockapi.io.H5600LifeLogReader
import org.avmedia.gshockapi.io.H5600WorkoutReader
import org.avmedia.gshockapi.link.WatchLink
import java.util.UUID
import org.avmedia.gshockapi.model.StepCounterData

/**
 * Protocol for the DW-H5600 (module 3516 family).
 *
 * Steps and the other LifeLog data come from the LifeLog transfer. All other features use the
 * standard protocol. While a transfer is running, notifications are routed to the transfer by
 * channel; otherwise the standard routing applies.
 */
@RequiresApi(Build.VERSION_CODES.O)
object H5600Protocol : StandardProtocol() {
    /**
     * Codes the watch sends on its own and that are handled at any time, also in the middle of a transfer: the connection check `49`,
     * the setup notice `47`, the time manager `39` and the notification manager `3D`.
     */
    val UNSOLICITED_ALWAYS: Set<Int> = setOf(ConnectionKeepAlive.CHECK, ConnectionKeepAlive.SETUP, 0x39, 0x3D)

    /**
     * The key of [data] on [link]. The watch's own connection check and setup notices are answered even in the middle of
     * a transfer; a transfer of that link takes the frames of its two characteristics, the rest (a settings answer such
     * as `11 00 ...`) keeps its own handler.
     */
    override fun linkKey(link: WatchLink, data: String, source: UUID?): Int? {
        val first = extractKey(data)
        if (first != null && first in UNSOLICITED_ALWAYS) return first
        val transfers = link.transfers
        if (transfers.tide.isActive || transfers.workouts.isActive) {
            val channel = H5600WorkoutReader.channelOf(source, data)
            return if (channel == H5600Channel.STATUS) first else channel.key
        }
        if (!transfers.lifeLog.isActive) return first
        val channel = H5600LifeLogReader.channelOf(data)
        // A status notification belongs to the transfer only while one of its setup requests is waiting.
        // Anything else on status (such as the button info reply) goes through the normal routing.
        return if (channel == H5600Channel.STATUS && !transfers.lifeLog.isAwaitingSetupReply()) first else channel.key
    }

    override suspend fun getStepCount(link: WatchLink, peek: Boolean): StepCounterData = link.transfers.lifeLog.request(peek)

    override val dataReceivedHandlers: Map<Int, (String) -> Unit>
        get() = super.dataReceivedHandlers - CasioConstants.CHARACTERISTICS.CASIO_ACTIVITY_RECORD.code

    /** [link]'s upkeep and setup, its notification gate and its transfers take their frames on that link. */
    override fun linkHandlers(link: WatchLink): Map<Int, (String) -> Unit> {
        val routes = link.connection.routes
        val transfers = link.transfers
        return withSetup(
            // No activity record on this model: its steps come from the LifeLog transfer.
            super.linkHandlers(link) - CasioConstants.CHARACTERISTICS.CASIO_ACTIVITY_RECORD.code +
                routes.own +
                mapOf(
                    0x3D to link.notifications.gate::onManager,
                    H5600Channel.DRSP.key to { d: String -> if (transfers.tide.isActive) transfers.tide.onDrsp(d) else if (transfers.workouts.isActive) transfers.workouts.onDrsp(d) else transfers.lifeLog.onDrsp(d) },
                    H5600Channel.CONVOY.key to { d: String -> if (transfers.tide.isActive) transfers.tide.onConvoy(d) else if (transfers.workouts.isActive) transfers.workouts.onConvoy(d) else transfers.lifeLog.onConvoy(d) },
                    H5600Channel.STATUS.key to transfers.lifeLog::onStatus,
                ),
            routes.watched,
            link.notifications.gate::onCondition,
        )
    }

    /** The library's own handlers for 0x10, 0x11, 0x23 and 0x28 still run; the connection setup watches the same frames after them. */
    private fun withSetup(handlers: Map<Int, (String) -> Unit>, watched: Map<Int, (String) -> Unit>, onCondition: (String) -> Unit): Map<Int, (String) -> Unit> =
        handlers + watched.mapValues { (code, setup) ->
            val library = handlers[code]
            if (code == CasioConstants.CHARACTERISTICS.CASIO_WATCH_CONDITION.code) {
                { d: String -> library?.invoke(d); setup(d); onCondition(d) }
            } else {
                { d: String -> library?.invoke(d); setup(d) }
            }
        }
}
