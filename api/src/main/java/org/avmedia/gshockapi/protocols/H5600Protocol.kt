package org.avmedia.gshockapi.protocols

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.casio.CasioConstants
import org.avmedia.gshockapi.casio.ConnectionKeepAlive
import org.avmedia.gshockapi.casio.NotificationGate
import org.avmedia.gshockapi.casio.WatchLink
import org.avmedia.gshockapi.io.H5600Channel
import org.avmedia.gshockapi.io.H5600HealthIO
import org.avmedia.gshockapi.io.H5600WorkoutIO
import org.avmedia.gshockapi.io.TideTransferIO
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

    override fun extractKey(data: String): Int? {
        // The watch's own connection check and setup notices are answered even in the middle of a transfer.
        val first = super.extractKey(data)
        if (first != null && first in UNSOLICITED_ALWAYS) return first
        // A transfer takes the frames of its two characteristics; the rest (a settings answer such as `11 00 ...`) keeps its own handler.
        if (TideTransferIO.isActive || H5600WorkoutIO.isActive) {
            val channel = TideTransferIO.channelOf(data)
            return if (channel == H5600Channel.STATUS) first else channel.key
        }
        if (!H5600HealthIO.isActive) return first
        val channel = H5600HealthIO.channelOf(data)
        // A status notification belongs to the transfer only while one of its setup requests is waiting.
        // Anything else on status (such as the button info reply) goes through the normal routing.
        return if (channel == H5600Channel.STATUS && !H5600HealthIO.isAwaitingSetupReply()) first else channel.key
    }

    override suspend fun getStepCount(peek: Boolean): StepCounterData = H5600HealthIO.request(peek)

    override val dataReceivedHandlers: Map<Int, (String) -> Unit>
        get() {
            // The one link's upkeep and setup, through the single-watch adapter (a link's own dispatcher takes its routes directly).
            val routes = WatchLink.connectionRoutes
            return withSetup(
                super.dataReceivedHandlers -
                    CasioConstants.CHARACTERISTICS.CASIO_ACTIVITY_RECORD.code +
                    routes.own +
                    mapOf(
                        0x3D to NotificationGate::onManager,
                        H5600Channel.DRSP.key to { d: String -> if (TideTransferIO.isActive) TideTransferIO.onDrsp(d) else if (H5600WorkoutIO.isActive) H5600WorkoutIO.onDrsp(d) else H5600HealthIO.onDrsp(d) },
                        H5600Channel.CONVOY.key to { d: String -> if (TideTransferIO.isActive) TideTransferIO.onConvoy(d) else if (H5600WorkoutIO.isActive) H5600WorkoutIO.onConvoy(d) else H5600HealthIO.onConvoy(d) },
                        H5600Channel.STATUS.key to H5600HealthIO::onStatus,
                    ),
                routes.watched,
            )
        }

    /** The library's own handlers for 0x10, 0x11, 0x23 and 0x28 still run; the connection setup watches the same frames after them. */
    private fun withSetup(handlers: Map<Int, (String) -> Unit>, watched: Map<Int, (String) -> Unit>): Map<Int, (String) -> Unit> =
        handlers + watched.mapValues { (code, setup) ->
            val library = handlers[code]
            if (code == CasioConstants.CHARACTERISTICS.CASIO_WATCH_CONDITION.code) {
                { d: String -> library?.invoke(d); setup(d); NotificationGate.onCondition(d) }
            } else {
                { d: String -> library?.invoke(d); setup(d) }
            }
        }
}
