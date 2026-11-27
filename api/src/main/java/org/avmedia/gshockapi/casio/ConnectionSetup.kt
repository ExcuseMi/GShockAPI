package org.avmedia.gshockapi.casio

import org.avmedia.gshockapi.utils.Utils

/**
 * The cheap, proven part of the per-connection setup the DW-H5600 family expects:
 * read `0x11` and write it back with byte index 4 bit 2 cleared; on a reconnect of the always-connect kind (9 or 10)
 * also read `0x3B` and write it back unchanged, then read `0x3A` and write it back with bytes index 7 and 8 set to
 * `DC 05`. The "settings data for this kind of connection" step is not traced and is left out.
 *
 * Driven by the frames the watch sends; one run per connection ([reset] at the start of a connection).
 * Never answers a frame it did not ask for. One per link ([ConnectionContext]): its writes go through that link's
 * [ConnectionKeepAlive.writer]; its hooks are no-ops until the link's owner sets them.
 */
class ConnectionSetup internal constructor(private val keepAlive: ConnectionKeepAlive) {
    companion object {
        /** Codes whose answers the setup (or the button info read) reacts to: a settings read of them must not swallow the answer. */
        val WATCHED_CODES: Set<Int> = setOf(CODE_KIND, CODE_BLE, CODE_3A, CODE_3B, CODE_NAME, CODE_CONDITION, CODE_TIME_MANAGER)

        const val SETUP_WAIT_MS = 20_000L

        /** The single-watch link's [isAutomaticConnect], for the current apps. New code asks its link's setup. */
        @Deprecated("Single-watch: ask the link's own setup (link.connection.setup)")
        fun isAutomaticConnect(): Boolean = WatchLink.connection.setup.isAutomaticConnect()

        /** The single-watch link's [watchSetupDone], for the current apps. New code asks its link's setup. */
        @Deprecated("Single-watch: ask the link's own setup (link.connection.setup)")
        val watchSetupDone: Boolean get() = WatchLink.connection.setup.watchSetupDone

        /**
         * The value written back after reading [code]: `0x11` with bit 2 of value index 4 cleared, `0x3A` with value index 7 and 8 set
         * to `DC 05`, every other value as read. Used by the connection setup and by the LifeLog setup, so both write the same bytes.
         */
        fun writeBackValue(code: Int, reply: ByteArray): ByteArray {
            val b = reply.copyOf()
            if (code == CODE_BLE && b.size >= 6) b[5] = (b[5].toInt() and 0x04.inv()).toByte()
            if (code == CODE_3A && b.size >= 10) {
                b[8] = 0xDC.toByte()
                b[9] = 0x05
            }
            return b
        }

        private const val CODE_KIND = 0x10
        private const val CODE_BLE = 0x11
        private const val CODE_3A = 0x3A
        private const val CODE_3B = 0x3B
        private const val CODE_NAME = 0x23
        private const val CODE_CONDITION = 0x28
        private const val CODE_TIME_MANAGER = 0x39
        private val ALWAYS_CONNECT_KINDS = setOf(9, 10)

        /** 13: the watch connected itself after sleep, 14: after a link loss. The setup on these kinds: no 0x11, 0x3A, 0x3B; the 0x39 exchange. */
        private val SELF_CONNECT_KINDS = setOf(13, 14)
    }

    private enum class Step { IDLE, WAIT_KIND, WAIT_11, WAIT_3B, WAIT_3A, WAIT_23, WAIT_39, DONE }

    private var selfConnect = false
    private var condition: List<Int> = emptyList()

    @Volatile private var step = Step.IDLE
    private var reconnectKind = false
    private var workoutsOffered = false

    /** Called once per connection with the first `0x28` answer after the setup; the workout download starts from it. */
    @Volatile var afterSetup: (List<Int>) -> Unit = {}

    /** Set by the transfer code: while a LifeLog transfer runs the setup does not start. */
    @Volatile var busy: () -> Boolean = { false }

    /** The end of the setup, run on every connection (kind 14 included): the time and the DST values. */
    @Volatile var timeWriter: () -> Unit = {}

    /** Run at every [reset]: what else the link's owner forgets with the setup (the single-watch button answer). */
    @Volatile var onReset: () -> Unit = {}

    /** The `0x10` answer held back because a transfer was active; run by [onTransferEnded]. */
    @Volatile private var deferredKind: String? = null

    /** The link dropped (the watch reconnects by itself, no `connect()`): the next `0x10` answer starts a new setup. */
    fun onLinkLost() = reset()

    /**
     * A LifeLog or workout transfer ended: run the setup that was deferred while it was active.
     * Chosen over running the setup first: the transfer already holds the exchange lock when the `0x10` answer arrives.
     */
    fun onTransferEnded() {
        if (busy()) return
        val data = deferredKind
        if (data != null) {
            // The deferred setup reads `0x39` itself: a value held from before it is not answered twice.
            deferredKind = null
            deferredTime = null
            ConnectionKeepAlive.log("transfer ended: deferred setup starts")
            onKind(data)
            return
        }
        val time = deferredTime ?: return
        deferredTime = null
        ConnectionKeepAlive.log("transfer ended: the held 39 value is answered")
        on39(time)
    }

    /**
     * The last `39` value the watch sent while a transfer was active; answered by [onTransferEnded]. Only the last one
     * is kept: each value replaces the one before in the exchange. Before, it was dropped and the watch never got its time.
     */
    @Volatile private var deferredTime: String? = null

    /** The kind of the last `0x10` answer of this link, noted before anything is deferred or skipped. */
    @Volatile var lastKind: Int? = null

    /** True when the watch connected itself (kind 13 or 14): nobody pressed a button. */
    fun isAutomaticConnect(): Boolean = lastKind in SELF_CONNECT_KINDS

    /** True while the setup's own steps wait for the watch's answers (it started and is not done). */
    fun isRunning(): Boolean = when (step) {
        Step.WAIT_11, Step.WAIT_3B, Step.WAIT_3A, Step.WAIT_23, Step.WAIT_39 -> true
        else -> false
    }

    /**
     * Waits (at most [timeoutMs]) until the setup is not running, so a LifeLog transfer or a settings read does not queue its own
     * requests in front of the setup's and take the answers it waits for (then the setup gets none of its answers).
     * Returns true when the setup is idle, false when it still ran at the timeout (the caller goes on and the log says so).
     */
    suspend fun awaitIdle(timeoutMs: Long = SETUP_WAIT_MS, pollMs: Long = 100): Boolean {
        if (!isRunning()) return true
        ConnectionKeepAlive.log("waiting for the connection setup ($step) before starting")
        val done = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            while (isRunning()) kotlinx.coroutines.delay(pollMs)
            true
        } ?: false
        if (!done) ConnectionKeepAlive.log("connection setup still at $step after ${timeoutMs / 1000} s, going on")
        return done
    }

    /** How long a LifeLog read waits for `47 01` before it leaves this link alone; a test seam. */
    @Volatile var watchSetupWaitMs: Long = SETUP_WAIT_MS

    /** True once the watch sent `47 01` on this link: its setup is done and a LifeLog read needs no setup replay. */
    @Volatile var watchSetupDone = false
        private set

    /**
     * Before a LifeLog or workout transfer, on every kind of connection: our setup idle and the watch's `47 01` in. True when
     * both are so within [timeoutMs]; false: the transfer is left out on this link (a transfer started without `47 01` on a
     * first connection after pairing got no answer and the watch dropped the link). Waiting holds nothing: settings reads go on.
     */
    suspend fun awaitWatchSetup(timeoutMs: Long = watchSetupWaitMs): Boolean {
        awaitIdle()
        if (watchSetupDone) return true
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            while (!watchSetupDone) kotlinx.coroutines.delay(100)
            true
        } ?: false
    }

    /** `47 01` came (called by this link's [ConnectionKeepAlive.onSetup]). */
    fun onWatchSetupDone() {
        watchSetupDone = true
    }

    fun reset() {
        watchSetupDone = false
        lastKind = null
        runCatching { onReset() }
        deferredKind = null
        deferredTime = null
        step = Step.WAIT_KIND
        reconnectKind = false
        workoutsOffered = false
        selfConnect = false
        condition = emptyList()
    }

    private fun bytes(data: String): List<Int> = runCatching { Utils.toIntArray(data) }.getOrDefault(emptyList())

    /** The `0x10` answer: byte index 7 after the code is the kind of connection. Starts the run. */
    fun onKind(data: String) {
        bytes(data).getOrNull(8)?.let { lastKind = it }
        if (step != Step.WAIT_KIND) {
            ConnectionKeepAlive.log("Connection kind answer: setup skipped, already $step")
            return
        }
        if (busy()) {
            deferredKind = data
            ConnectionKeepAlive.log("Connection kind answer: setup deferred, a transfer is active")
            return
        }
        val b = bytes(data)
        val kind = b.getOrNull(8) ?: return
        reconnectKind = kind in ALWAYS_CONNECT_KINDS
        selfConnect = kind in SELF_CONNECT_KINDS
        ConnectionKeepAlive.log("Connection kind $kind: setup starts")
        if (selfConnect) {
            // No 0x11 (it holds the link-end flag), no 0x3A, no 0x3B.
            ConnectionKeepAlive.log("kind $kind: 0x11, 0x3A and 0x3B are not written")
            nextName()
            return
        }
        step = Step.WAIT_11
        keepAlive.writer.request(CODE_BLE)
    }

    fun onBle(data: String) {
        if (step != Step.WAIT_11) return
        val b = bytes(data)
        if (b.size < 6 || b[0] != CODE_BLE) return
        keepAlive.writer.writeAllFeatures(writeBackValue(CODE_BLE, b.map { it.toByte() }.toByteArray()))
        ConnectionKeepAlive.log("0x11 written with bit 2 of byte 4 cleared")
        if (reconnectKind) {
            step = Step.WAIT_3B
            keepAlive.writer.request(CODE_3B)
        } else {
            nextName()
        }
    }

    /** Every kind: read the watch name and write it back, on every connection. */
    private fun nextName() {
        step = Step.WAIT_23
        keepAlive.writer.request(CODE_NAME)
    }

    fun on23(data: String) {
        if (step != Step.WAIT_23) return
        val b = bytes(data)
        if (b.firstOrNull() != CODE_NAME) return
        keepAlive.writer.writeAllFeatures(b.map { it.toByte() }.toByteArray())
        ConnectionKeepAlive.log("0x23 name written back")
        if (selfConnect) {
            step = Step.WAIT_39
            for (code in listOf(0x26, CODE_CONDITION, 0x20, CODE_CONDITION, CODE_TIME_MANAGER)) {
                keepAlive.writer.request(code)
            }
            ConnectionKeepAlive.log("read 0x26, 0x28, 0x20, 0x28, 0x39; waiting for the 0x39 value")
            return
        }
        step = Step.DONE
        keepAlive.writer.request(CODE_CONDITION)
        ConnectionKeepAlive.log("0x28 watch condition requested")
        timeWriter()
        ConnectionKeepAlive.log("time and DST write started")
    }

    fun on3b(data: String) {
        if (step != Step.WAIT_3B) return
        val b = bytes(data)
        if (b.firstOrNull() != CODE_3B) return
        keepAlive.writer.writeAllFeatures(b.map { it.toByte() }.toByteArray())
        ConnectionKeepAlive.log("0x3B written back unchanged")
        step = Step.WAIT_3A
        keepAlive.writer.request(CODE_3A)
    }

    fun on3a(data: String) {
        if (step != Step.WAIT_3A) return
        val b = bytes(data)
        if (b.size < 10 || b[0] != CODE_3A) return
        keepAlive.writer.writeAllFeatures(writeBackValue(CODE_3A, b.map { it.toByte() }.toByteArray()))
        ConnectionKeepAlive.log("0x3A written with bytes 7 and 8 set to DC 05")
        nextName()
    }

    /** The `0x28` answer: kept for the `0x39` decision; "index 7" counts after the code, like `0x10` and `0x11`. */
    fun on28(data: String) {
        if (step == Step.DONE && !workoutsOffered) {
            val c = bytes(data)
            if (c.firstOrNull() == CODE_CONDITION) {
                workoutsOffered = true
                afterSetup(c)
            }
        }
        if (!selfConnect) return
        condition = bytes(data)
        ConnectionKeepAlive.log("0x28 received: " + condition.take(12).joinToString(" ") { "%02X".format(it) })
    }

    private fun timeChangeAllowed(): Boolean {
        val v = condition.getOrNull(8) ?: return true.also { ConnectionKeepAlive.log("0x28 unknown: time change taken as allowed") }
        return v and 0x04 == 0
    }

    private fun timeUpdate() {
        ConnectionKeepAlive.log("leap-second write 25 18 18 17 20 01 01 skipped (values not confirmed)")
        timeWriter()
        keepAlive.writer.request(CODE_CONDITION)
        ConnectionKeepAlive.log("time update: time and DST write started, 0x28 requested")
    }

    /** The watch's `39` value (read at setup, and whenever it notifies again). Kinds 13 and 14 only. */
    fun on39(data: String) {
        if (!selfConnect) return
        val value = bytes(data).getOrNull(1) ?: return
        if (busy()) {
            deferredTime = data
            ConnectionKeepAlive.log("39 %02X received during a transfer: answered when it ends".format(value))
            return
        }
        ConnectionKeepAlive.log("39 %02X received".format(value))
        when (value) {
            0 -> if (timeChangeAllowed()) answer(3) else answer(5)
            2 -> timeUpdate().also { step = Step.DONE }
            4 -> answer(4) // time zone, DST and home city are taken as equal: nothing to do
            5 -> ConnectionKeepAlive.log("39 05: the watch aborted (ignored)")
            else -> ConnectionKeepAlive.log("39 %02X: not handled".format(value))
        }
    }

    private fun answer(value: Int) {
        keepAlive.writer.writeAllFeatures(byteArrayOf(CODE_TIME_MANAGER.toByte(), value.toByte()))
        ConnectionKeepAlive.log("39 %02X written".format(value))
        if (value != 3) {
            step = Step.DONE
            if (!workoutsOffered && condition.isNotEmpty()) {
                workoutsOffered = true
                afterSetup(condition)
            }
        }
    }
}
