package org.avmedia.gshockapi.casio

import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.utils.Utils
import org.avmedia.gshockapi.log.LibraryLog

/** Link Loss is written as `00 level` to the all-features characteristic, never to the standard Link Loss characteristic. */
fun linkLossFrame(level: Int): ByteArray = byteArrayOf(0x00, level.toByte())

/** The writes the keep-alive and the connection setup make. A fake in the unit tests. */
interface WatchWriter {
    /** A write with response to the all-features characteristic (used for `49 01`, `0x11`, `0x3A`). */
    fun writeAllFeatures(bytes: ByteArray)

    /** A read request (one code) on the request characteristic. */
    fun request(code: Int)

    /** Link Loss alert level (0 none, 1 mild, 2 high): the frame `00 level` on the all-features characteristic. */
    fun writeLinkLoss(level: Int)
}

/** The writer of one link: every write is a command job on that link, so it reaches only that link's watch. */
internal class LinkWriter(private val link: org.avmedia.gshockapi.link.WatchLink) : WatchWriter {
    override fun writeAllFeatures(bytes: ByteArray) {
        link.command(GetSetMode.SET, bytes)
    }

    override fun request(code: Int) {
        link.command(GetSetMode.GET, byteArrayOf(code.toByte()))
    }

    override fun writeLinkLoss(level: Int) {
        link.command(GetSetMode.SET, linkLossFrame(level))
    }
}

/** The connection upkeep and setup of one link: made by the link, never shared with another. */
class ConnectionContext internal constructor(linkWriter: WatchWriter) {
    val keepAlive: ConnectionKeepAlive = ConnectionKeepAlive(linkWriter) { setup.onWatchSetupDone() }

    val setup: ConnectionSetup = ConnectionSetup(keepAlive)

    /** The watch's frames this link's upkeep and setup take: a protocol routes them here, never to another link's. */
    val routes: ConnectionRoutes = ConnectionRoutes(this)
}

/** The handlers of one link's upkeep and setup, by frame code. Made only by its [ConnectionContext]. */
class ConnectionRoutes internal constructor(context: ConnectionContext) {
    /** Codes the link's upkeep and setup handle alone: the check `49`, the setup notice `47`, `3A`, `3B` and the time manager `39`. */
    val own: Map<Int, (String) -> Unit> = mapOf(
        ConnectionKeepAlive.CHECK to context.keepAlive::onCheck,
        ConnectionKeepAlive.SETUP to context.keepAlive::onSetup,
        0x3A to context.setup::on3a,
        0x3B to context.setup::on3b,
        CasioConstants.CHARACTERISTICS.CASIO_CURRENT_TIME_MANAGER.code to context.setup::on39,
    )

    /** Answers the library handles itself that the setup watches as well, after the library's handler: `28`, `23`, `10`, `11`. */
    val watched: Map<Int, (String) -> Unit> = mapOf(
        CasioConstants.CHARACTERISTICS.CASIO_WATCH_CONDITION.code to context.setup::on28,
        CasioConstants.CHARACTERISTICS.CASIO_WATCH_NAME.code to context.setup::on23,
        CasioConstants.CHARACTERISTICS.CASIO_BLE_FEATURES.code to context.setup::onKind,
        CasioConstants.CHARACTERISTICS.CASIO_SETTING_FOR_BLE.code to context.setup::onBle,
    )
}

/**
 * The connection upkeep of the DW-H5600 family: the watch notifies `49 00` (connection check)
 * and the app answers at once with `49 01`; the watch notifies `47 01` when its setup is done and `47 02` to ask for the
 * Link Loss alert level 1. Every such notification is logged with its time so a health-log share shows whether the
 * drops stop. Pure apart from the [writer]. One per link ([ConnectionContext]).
 */
class ConnectionKeepAlive internal constructor(
    /** The link's own writer, what [writer] is when no test put another in. */
    val linkWriter: WatchWriter,
    private val watchSetupDone: () -> Unit,
) {
    companion object {
        const val CHECK = 0x49
        const val SETUP = 0x47

        /** The watch's connection events go to the library log, source Watch. */
        fun log(text: String) = LibraryLog.log(LibraryLog.Source.Watch, text)
    }

    @Volatile var writer: WatchWriter = linkWriter

    /** Called on `47 01` (the watch's setup is done): the app writes what was waiting for it. */
    @Volatile var onSetupDone: () -> Unit = {}

    /** The time source of the link age checks (a fixed clock in the tests). */
    @Volatile var clock: () -> Long = System::currentTimeMillis

    private fun values(data: String): List<Int> = runCatching { Utils.toIntArray(data) }.getOrDefault(emptyList())

    /** `49 00`: write `49 01` once. Any other value is only logged. */
    fun onCheck(data: String) {
        val v = values(data)
        if (v.getOrNull(1) == 0) {
            writer.writeAllFeatures(byteArrayOf(0x49, 0x01))
            log("49 00 received, 49 01 written")
        } else {
            log("49 ${v.getOrNull(1)?.let { "%02X".format(it) } ?: "?"} received (not answered)")
        }
    }

    /** `47 01`: setup done. `47 02`: write the Link Loss alert level 1. */
    fun onSetup(data: String) {
        when (val value = values(data).getOrNull(1)) {
            1 -> {
                log("47 01 received: the watch's setup is done")
                watchSetupDone()
                runCatching { onSetupDone() }
            }
            2 -> {
                writer.writeLinkLoss(1)
                log("47 02 received, Link Loss alert level 1 written")
            }
            else -> log("47 ${value?.let { "%02X".format(it) } ?: "?"} received (ignored)")
        }
    }
}
