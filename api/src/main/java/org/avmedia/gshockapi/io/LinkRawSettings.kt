package org.avmedia.gshockapi.io

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.avmedia.gshockapi.casio.ConnectionKeepAlive
import kotlinx.coroutines.withTimeoutOrNull
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.link.JobOutcome
import org.avmedia.gshockapi.link.WatchLink
import org.avmedia.gshockapi.utils.Utils
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/** "42 05 3D …" */
internal fun IntArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

/** The wire as a settings reader needs it. The real one writes through its link; a fake in the unit tests. */
interface SettingLink {
    /** A read request: one byte (the code), or several (for example `54 00 01 40 00`). */
    fun sendRequest(bytes: ByteArray)

    /** A full packet, not waited for. */
    fun sendPacket(packet: IntArray)

    /** A full packet; true when the watch confirmed the write. */
    suspend fun sendChecked(bytes: ByteArray): Boolean
}

/** The wire of one link: each request and packet is a command job on that link. */
internal class LinkSettingWire(private val link: WatchLink) : SettingLink {
    override fun sendRequest(bytes: ByteArray) {
        link.command(GetSetMode.GET, bytes.copyOf())
    }

    override fun sendPacket(packet: IntArray) {
        link.command(GetSetMode.SET, Utils.byteArrayOfIntArray(packet))
    }

    override suspend fun sendChecked(bytes: ByteArray): Boolean =
        when (val out = link.command(GetSetMode.SET, bytes.copyOf()).await()) {
            is JobOutcome.Done -> out.value.ok
            is JobOutcome.Failed -> false.also { ConnectionKeepAlive.log("request %02X: %s".format(bytes.firstOrNull() ?: 0, out.detail)) }
        }
}

/**
 * The one reader / writer for settings packets whose first byte is the command code (DW-H5600: 0x24, 0x39, 0x3D,
 * 0x41, 0x42, 0x45, 0x4F, 0x50, 0x51, 0x2E, 0x54 and the rest of the raw table, and raw 0x13 restores).
 *
 * Read:  the request on the read-request characteristic (GetSetMode.GET, 26eb002c): the code alone, `3D 30` for 0x3D,
 *        or several bytes. The watch answers with a notification whose first byte is the code, or `FF ...` when it refuses.
 *        The link's dispatcher offers every notification to [offer] first: it takes only the answer to the request that waits
 *        and passes everything else (LifeLog, CONVOY, other codes) on, so no handler is ever replaced.
 * Write: the full packet on the all-features characteristic (GetSetMode.SET, 26eb002d); [safeWriter] reads, changes
 *        only the target bytes, writes with a write result and reads back.
 *
 * One per link: its own request in flight, its own locks, the wait for its own setup; another watch's answer never
 * completes it. One request at a time ([sendLock]). During a transfer the request is a command job that goes at the transfer's next
 * boundary.
 * [request] (retried, shared by concurrent readers of the same request) and [read] (one try, plain-words failure)
 * are the same exchange; [modifySafe] serializes read-modify-write per code, so two quick changes cannot undo each other.
 */
class LinkRawSettings internal constructor(private val watchLink: WatchLink) {
    companion object {
        private const val TRIES = 3

        /** The alarm frames (0x15, 0x16) and the cache key of their read ([LinkAlarms]). */
        private val ALARM_CODES = setOf(0x15, 0x16)
        private const val ALARMS_CACHE_KEY = "GET_ALARMS"

        /** The watch answers a settings read within 10 s. */
        const val READ_TIMEOUT_MS = 10_000L

        private const val LOG_BYTES = 8

        /** What a read of [code] sends: the code alone, except 0x3D, which is read as `3D 30` and never bare. */
        fun requestFor(code: Int): ByteArray = if (code == 0x3D) byteArrayOf(0x3D, 0x30) else byteArrayOf(code.toByte())

        /** The status of an `FF` answer: `FF status`, or `FF code status` (-1 when there is none). */
        fun failureStatus(ints: IntArray, code: Int): Int =
            when {
                ints.size >= 3 && ints[1] == code -> ints[2]
                ints.size >= 2 && ints[1] != code -> ints[1]
                else -> -1
            }
    }

    /** This link's own wire, what [link] is when no test put another in. */
    val linkWire: SettingLink = LinkSettingWire(watchLink)

    // Seams for unit tests (a fake watch); production values below.
    @Volatile var link: SettingLink = linkWire
    var tryTimeoutMs = 3_000L
    var retryBaseDelayMs = 400L
    var settleMs = 1_000L

    /** The result of [read]: the [frame] (code first), or the plain-words [failure]. */
    class Reply(val frame: ByteArray?, val failure: String?)

    private class Pending(val key: String, val code: Int, val accept: (IntArray) -> Boolean) {
        val deferred = CompletableDeferred<IntArray>()
    }

    private val lock = Any()
    private var current: Pending? = null
    private val sendLock = Mutex()
    private val locks = ConcurrentHashMap<Int, Mutex>()

    private class Failure(val text: String) : IllegalStateException(text)

    private class NoAnswer(code: Int) : IllegalStateException("no answer to 0x%02X".format(code))

    /** The default check of a code read: the answer is longer than the code and, for `3D 30`, names the same sub-request. */
    private fun answersRequest(request: ByteArray, frame: IntArray): Boolean =
        frame.size > 1 && (request.size < 2 || frame[1] == (request[1].toInt() and 0xFF))

    /** Reads packet [code] from the watch; throws after [tries] unanswered or refused tries. */
    suspend fun request(code: Int, tries: Int = TRIES): IntArray {
        val request = requestFor(code)
        var last: Throwable = NoAnswer(code)
        for (attempt in 0 until tries) {
            try {
                return attempt(request, tryTimeoutMs) { true }
            } catch (e: Failure) {
                last = e
            } catch (e: NoAnswer) {
                last = e
            }
            Timber.w("0x%02X read try %d failed: %s".format(code, attempt + 1, last.message))
            if (attempt < tries - 1) delay(retryBaseDelayMs * (attempt + 1))
        }
        throw last
    }

    /**
     * One settings read: sends [request], waits up to [timeoutMs] (the watch answers within 10 s) and returns the first answer that
     * begins with the code and that [accept] takes. A refusal (`FF`) names its status; nothing is retried. [fresh]: sent for this
     * call, never joined to the same request already asked for (that answer may predate a write).
     */
    suspend fun read(request: ByteArray, timeoutMs: Long = READ_TIMEOUT_MS, fresh: Boolean = false, accept: (ByteArray) -> Boolean = { true }): Reply =
        try {
            val frame = attempt(request, timeoutMs, join = !fresh) { it.size > 1 && accept(Utils.byteArrayOfIntArray(it)) }
            Reply(Utils.byteArrayOfIntArray(frame), null)
        } catch (e: Failure) {
            Reply(null, e.text)
        } catch (e: NoAnswer) {
            Reply(null, "The watch did not answer within ${timeoutMs / 1000} s.")
        }

    /**
     * The answer to the default request of [code] as frame bytes (code first), or null; [read] says why. [fresh]: the
     * request is sent for this call and never joins one already asked for (which may predate a write); every write's
     * read and read-back are fresh.
     */
    suspend fun readFrame(code: Int, fresh: Boolean = false): ByteArray? {
        val request = requestFor(code)
        return read(request, READ_TIMEOUT_MS, fresh) { answersRequest(request, it.map { b -> b.toInt() and 0xFF }.toIntArray()) }.frame
    }

    private suspend fun attempt(request: ByteArray, timeoutMs: Long, join: Boolean = true, accept: (IntArray) -> Boolean): IntArray {
        val code = request[0].toInt() and 0xFF
        val key = request.joinToString("") { "%02X".format(it) }
        // A reader of the same request waits for the answer that is already asked for (never a fresh read).
        if (join) synchronized(lock) { current?.takeIf { it.key == key && it.deferred.isActive } }?.let { return await(it, timeoutMs, code) }
        watchLink.connection.setup.awaitIdle()
        return sendLock.withLock {
            val pending = Pending(key, code, accept)
            synchronized(lock) { current = pending }
            try {
                ConnectionKeepAlive.log("read tx " + request.toHex() + " (waits up to ${timeoutMs / 1000} s)")
                link.sendRequest(request)
                await(pending, timeoutMs, code)
            } finally {
                synchronized(lock) { if (current === pending) current = null }
            }
        }
    }

    private suspend fun await(pending: Pending, timeoutMs: Long, code: Int): IntArray {
        val reply = withTimeoutOrNull(timeoutMs) { pending.deferred.await() }
        if (reply != null) return reply
        ConnectionKeepAlive.log("read 0x%02X: no answer within %d ms".format(code, timeoutMs))
        // readers that joined it retry on their own
        pending.deferred.completeExceptionally(NoAnswer(code))
        throw NoAnswer(code)
    }

    private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /**
     * Called by this link's [org.avmedia.gshockapi.casio.LinkDispatcher] for every notification before anything else.
     * Takes the answer to the request that waits (first byte = its code), or an `FF` refusal while one waits;
     * returns false for everything else, which then goes to the library as usual.
     */
    fun offer(data: String): Boolean {
        val pending = synchronized(lock) { current } ?: return false
        val ints = runCatching { Utils.toIntArray(data).toIntArray() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        if (ints[0] != pending.code && ints[0] != 0xFF) return false
        // Every answer that arrives while a read waits is logged (first bytes only), so a refused or lost answer can be seen.
        ConnectionKeepAlive.log("read 0x%02X rx %s".format(pending.code, ints.take(LOG_BYTES).toIntArray().toHex()))
        Timber.i("RawSettingIO received: ${ints.toHex()}")
        if (ints[0] == 0xFF) {
            val status = failureStatus(ints, pending.code)
            pending.deferred.completeExceptionally(
                Failure(if (status < 0) "watch refused: no status" else "watch refused: status %02X".format(status)),
            )
        } else if (pending.accept(ints)) {
            pending.deferred.complete(ints)
            WatchDataTap.setting(watchLink.watchInfo.name, ints)
        }
        // The connection setup and the button info wait for these codes too: the frame goes on to them as well.
        return !(ints[0] != 0xFF && ints[0] in org.avmedia.gshockapi.casio.ConnectionSetup.WATCHED_CODES)
    }

    /** Handler registered in WatchProtocol.dataReceivedHandlers for the codes upstream reads; answers are taken by [offer]. */
    fun onReceived(data: String) {
        Timber.d("RawSettingIO: unsolicited answer ignored: $data")
    }

    /**
     * Read, change only [touched] bytes, write (the write result must be OK), read back and compare, serialized per code.
     * [change] gets a fresh copy of the frame and returns the frame to write, or null (refused) / an equal frame (no write).
     */
    suspend fun modifySafe(code: Int, touched: Set<Int>, change: (ByteArray) -> ByteArray?): SettingWriteResult =
        withLock(code) { safeWriter().update(code, touched) { change(it.copyOf()) } }

    /** [modifySafe] for a frame the watch answers differently from the one written ([expectedBack]), compared by [matches]. */
    suspend fun modifySafe(
        code: Int,
        touched: Set<Int>,
        expectedBack: (before: ByteArray, written: ByteArray) -> ByteArray,
        matches: (back: ByteArray, expected: ByteArray) -> Boolean,
        change: (ByteArray) -> ByteArray?,
    ): SettingWriteResult = withLock(code) { safeWriter().update(code, touched, expectedBack, matches) { change(it.copyOf()) } }

    /** The checked writer every setting write goes through: [read] defaults to a fresh [readFrame]. */
    fun safeWriter(read: suspend (Int) -> ByteArray? = { readFrame(it, fresh = true) }): SafeSettingWrite =
        SafeSettingWrite(
            read = read,
            write = { frame ->
                Timber.i("RawSettingIO write: ${frame.toHex()}")
                val ok = link.sendChecked(frame)
                // The alarms' own read keeps its answer: a slot written here makes it stale (the next read asks the watch).
                if (((frame.firstOrNull()?.toInt() ?: 0) and 0xFF) in ALARM_CODES) watchLink.cache.remove(ALARMS_CACHE_KEY)
                if (!ok) throw WriteNotConfirmed("no write result")
            },
            settle = { delay(settleMs) },
        )

    /** Runs [block] holding the per-code lock of [modifySafe] (for packets read through another IO). */
    suspend fun <T> withLock(code: Int, block: suspend () -> T): T = locks.getOrPut(code) { Mutex() }.withLock { block() }

    /** Writes a full packet (first byte = code) to the watch, not waited for. */
    fun write(packet: IntArray) {
        Timber.i("RawSettingIO write: ${packet.toHex()}")
        link.sendPacket(packet)
    }
}
