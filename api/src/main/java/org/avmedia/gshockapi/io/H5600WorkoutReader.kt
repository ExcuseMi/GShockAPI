package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.link.WatchLink
import org.avmedia.gshockapi.link.JobOutcome
import org.avmedia.gshockapi.link.JobScope
import org.avmedia.gshockapi.model.WorkoutSession
import timber.log.Timber

/**
 * Downloads the workouts of one watch's DW-H5600 as a background job on its [link] (or inside the LifeLog job, before
 * its START). One per link ([LinkTransfers]): what runs, waits or failed on one watch never holds another.
 *
 * The app sets [sink] (store the workout; true when stored) and may collect [workouts] to be told about each new one.
 * Without a sink nothing is downloaded, so nothing is marked as fetched that nobody keeps. A workout is delivered
 * before it is marked, so a crash in between delivers it again: the app stores by start time and sport (upsert).
 */
@RequiresApi(Build.VERSION_CODES.O)
class H5600WorkoutReader internal constructor(private val link: WatchLink) {
    companion object {
        /** After a failed read an automatic one waits this long; the button always reads (no retry storm). */
        const val RETRY_AFTER_FAIL_MS = 10 * 60_000L

        /** Pause before the one repeat of a refused start. */
        internal const val START_RETRY_MS = 3_000L

        /** The longest a workout read holds its job (a full watch is a few minutes on the slow link). */
        internal const val JOB_DEADLINE_MS = 10 * 60 * 1000L

        /**
         * Which characteristic a message that came on [source] belongs to while a workout or tide transfer runs. The
         * characteristic decides; the guess from the bytes is only the fallback when the connection code did not say. The
         * watch's `09 1D` (all sent) is longer than 2 bytes, the guess called it a status frame and the read never answered it.
         */
        fun channelOf(source: java.util.UUID?, hex: String): H5600Channel =
            when (source) {
                org.avmedia.gshockapi.casio.CasioConstants.CASIO_DATA_REQUEST_CHARACTERISTIC_UUID -> H5600Channel.DRSP
                org.avmedia.gshockapi.casio.CasioConstants.CASIO_CONVOY_CHARACTERISTIC_UUID -> H5600Channel.CONVOY
                null -> channelOf(h5600Bytes(hex))
                else -> H5600Channel.STATUS
            }

        /** The guess from the bytes alone (no characteristic known). Single bytes are never bulk. */
        fun channelOf(data: ByteArray): H5600Channel {
            val first = data.firstOrNull()?.toInt()?.and(0xFF) ?: return H5600Channel.STATUS
            return when {
                data.size <= 2 && first in DRSP_FIRST -> H5600Channel.DRSP
                data.size > 2 && first <= BULK_LAST -> H5600Channel.CONVOY
                else -> H5600Channel.STATUS
            }
        }

        private val DRSP_FIRST = setOf(0x00, 0x03, 0x04, 0x06, 0x07, 0x08, 0x09)
        private const val BULK_LAST = 0x07
    }

    /** Called for every downloaded workout, before the fetched mark; return true only when it is stored. */
    @Volatile
    var sink: (suspend (WorkoutSession) -> Boolean)? = null

    private val _workouts = MutableSharedFlow<WorkoutSession>(extraBufferCapacity = 64)

    /** Each workout after the sink took it. A notice only; storing is the sink's job. */
    val workouts: SharedFlow<WorkoutSession> = _workouts

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    /** Why the last run stopped early; null when it finished. */
    @Volatile
    var lastError: String? = null
        private set

    @Volatile private var inbox: Channel<WorkoutMsg>? = null
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private val blockCache = HashMap<String, ByteArray>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isActive: Boolean get() = inbox != null

    fun onDrsp(hex: String) = push(H5600Channel.DRSP, hex)

    fun onConvoy(hex: String) = push(H5600Channel.CONVOY, hex)

    private fun push(channel: H5600Channel, hex: String) {
        val box = inbox ?: return
        val data = h5600Bytes(hex)
        // Every frame whole: the raw rx line shows only two bytes, too few to see a chunk or an end message.
        log("workout rx " + (if (channel == H5600Channel.CONVOY) "bulk " else "drsp ") + data.joinToString(" ") { "%02X".format(it) })
        box.trySend(WorkoutMsg(channel, data))
    }

    /** After the connection setup: `0x28` index 6 (one byte after the code, so list position 7) is 1 or 2 when the watch holds workouts. */
    fun onSetupDone(condition: List<Int>) {
        val flag = condition.getOrNull(WorkoutReadTrigger.FLAG_POSITION) ?: return
        if (!WorkoutReadTrigger.waiting(flag)) return
        log("Workout read: 0x28 index 6 is $flag after the setup, workouts are waiting")
        scope.launch { download() }
    }

    /** This link's watch (a test seam): the last check and the failure back-off are each watch's own. */
    @Volatile internal var watch: () -> String = { link.key }

    /** Per watch: the end of its last download that finished. */
    private val lastCheck = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** End of the connected watch's last download that finished (with or without workouts); null until one did. */
    val lastCheckMs: Long?
        get() = lastCheck[watch()]

    /** After a LifeLog read ([condition] = its raw `0x28` answer, code byte first): download when [WorkoutReadTrigger] says so. */
    fun afterRead(condition: ByteArray?, nowMs: Long = System.currentTimeMillis()) {
        if (sink == null) return
        val flag = WorkoutReadTrigger.flagOf(condition)
        if (!WorkoutReadTrigger.shouldDownload(flag, lastCheckMs, nowMs)) return
        log("Workout read after the LifeLog read: " + if (WorkoutReadTrigger.waiting(flag)) "0x28 index 6 is $flag" else "hourly check (index 6 is $flag)")
        scope.launch { download() }
    }

    /** A test seam for the back-off. */
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    /** How long a read waits for `47 01` (the watch's own setup) before it goes on anyway. */
    @Volatile internal var setupWaitMs: Long = org.avmedia.gshockapi.casio.ConnectionSetup.SETUP_WAIT_MS

    /** Per watch: when its last read failed; none after a read that finished. Another watch is not held back by it. */
    private val lastFail = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** An automatic read runs or waits for the link: a second automatic one is not queued behind it. */
    private val automaticPending = java.util.concurrent.atomic.AtomicBoolean(false)

    internal fun resetBackoff() {
        lastFail.clear()
    }

    /**
     * One download run as its own job; waits for its turn on the link (and says so when another job holds it). Returns the number of workouts stored, or null when it did not run.
     * [userAsked] = the button: it always runs; an automatic read is skipped while another one runs or waits, and for
     * [RETRY_AFTER_FAIL_MS] after a failed one.
     */
    suspend fun download(userAsked: Boolean = false): WorkoutRunResult? {
        log("Workout read asked" + if (userAsked) " (button)" else "")
        if (sink == null) return null.also { log("Workout read not started: the app keeps no workouts") }
        if (!userAsked && !automaticPending.compareAndSet(false, true)) return null.also { log("Workout read not started: one is already running or waiting") }
        try {
            if (!userAsked) backedOff()?.let { log(it); return null }
            if (link.jobs.busy()) log("Workout read waits: another job (a LifeLog or settings read) holds the link")
            return downloadHeld(userAsked)
        } finally {
            if (!userAsked) automaticPending.set(false)
        }
    }

    /** The log line when an automatic read must wait after a failure, else null. */
    private fun backedOff(): String? {
        val failed = lastFail[watch()] ?: return null
        val left = failed + RETRY_AFTER_FAIL_MS - clock()
        if (left <= 0) return null
        return "Workout read not started: the last one failed, next automatic try in ${(left + 59_999) / 60_000} min (the button reads now)"
    }

    /** Like the LifeLog: no transfer before the watch's setup is done (`47 01`) and our own setup is idle. False: left out on this link. */
    private suspend fun awaitWatchSetup(): Boolean {
        if (link.connection.setup.watchSetupDone) return link.connection.setup.awaitWatchSetup(setupWaitMs)
        log("Workout read waits for 47 01 (the watch's setup)")
        val came = link.connection.setup.awaitWatchSetup(setupWaitMs)
        log(if (came) "Workout read: 47 01 came" else "Workout read left out: no 47 01 in ${setupWaitMs / 1000} s on this link")
        return came
    }

    /**
     * Inside a LifeLog read (its job), before its START: when its `0x28` answer flags workouts (index 6 is 1 or 2)
     * they are read first ([run], normally [downloadHeld]): workouts first, then the LifeLog.
     * With a workout waiting (`0x28` index 6 = 1) the watch does not answer a LifeLog START.
     * True when the workouts were read (or tried): the read after the LifeLog is then left out.
     */
    suspend fun beforeLifeLog(condition: ByteArray?, run: suspend () -> Unit): Boolean {
        val flag = WorkoutReadTrigger.flagOf(condition)
        if (!WorkoutReadTrigger.waiting(flag)) return false
        log("LifeLog: 0x28 index 6 is $flag, the waiting workouts are read first")
        try {
            run()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            log("Workout read before the LifeLog failed: ${t.javaClass.simpleName} ${t.message.orEmpty()}")
        }
        log("LifeLog read goes on after the workouts")
        return true
    }

    /**
     * One download run. With [job] (the LifeLog read runs it before its START when `0x28` flags workouts: workouts first,
     * then the LifeLog) it runs inside that job; without, as its own job on the link.
     */
    suspend fun downloadHeld(userAsked: Boolean = false, job: JobScope? = null): WorkoutRunResult? {
        val store = sink ?: return null.also { log("Workout read not started: the app keeps no workouts") }
        if (!userAsked) backedOff()?.let { log(it); return null }
        if (!running.compareAndSet(false, true)) return null.also { log("Workout read not started: one is already running") }
        val ready = try {
            awaitWatchSetup()
        } catch (t: Throwable) {
            running.set(false)
            throw t
        }
        if (!ready) {
            running.set(false)
            return null
        }
        log("Workout read starts")
        try {
            _status.value = "Downloading workouts"
            lastError = null
            var result = runOnce(store, job)
            if (result.failReason != null) {
                // `03 01` at the start came while the watch ran another exchange: once more after it ended.
                log("Workout read: the watch refused the start (fail reason ${result.failReason}), trying once more after the exchange")
                delay(START_RETRY_MS)
                result = runOnce(store, job)
            }
            lastError = result.error
            if (result.error == null) lastFail.remove(watch()) else lastFail[watch()] = clock()
            if (result.error == null) lastCheck[watch()] = System.currentTimeMillis()
            if (result.error == null) log("Workout read done: ${result.downloaded} new stored")
            else problem("Workout read stopped: ${result.error}" + if (result.downloaded > 0) " (${result.downloaded} stored before)" else "")
            return result
        } catch (t: Throwable) {
            lastFail[watch()] = clock()
            if (t !is kotlinx.coroutines.CancellationException) problem("Workout read stopped: ${t.javaClass.simpleName} ${t.message.orEmpty()}")
            throw t
        } finally {
            inbox = null
            link.connection.setup.onTransferEnded()
            _status.value = null
            running.set(false)
        }
    }

    /**
     * One transfer run. As its own job it starts only after the quiet that follows a command or a transfer (a start
     * during a time exchange, or 5 ms after a LifeLog read, was refused or answered busy); while it waits the link is free
     * for others, and [inbox] is set only once it holds the link (until then the watch's messages are not ours).
     */
    private suspend fun runOnce(store: suspend (WorkoutSession) -> Boolean, job: JobScope?): WorkoutRunResult {
        inbox = null
        val channel = Channel<WorkoutMsg>(Channel.UNLIMITED)
        fun transfer(via: WorkoutLink): WorkoutTransfer {
            inbox = channel
            return WorkoutTransfer(
                link = via,
                inbox = channel,
                sink = { s -> store(s).also { ok -> if (ok) _workouts.emit(s) } },
                blockCache = blockCache,
                log = ::log,
            )
        }
        // In a job on the link: it gives way to a tap, a notification or a command after each block chain.
        if (job != null) return transfer(script ?: JobWorkoutLink(job)).run()
        return when (val out = link.transfer("workout read", JOB_DEADLINE_MS, quiet = true) { transfer(script ?: JobWorkoutLink(this)).run() }.await()) {
            is JobOutcome.Done -> out.value
            is JobOutcome.Failed -> WorkoutRunResult(0, "The workout read ended: ${out.detail}")
        }
    }

    private fun log(text: String) {
        Timber.i("H5600 workouts: $text")
        org.avmedia.gshockapi.casio.ConnectionKeepAlive.log(text)
    }

    /** Why a workout read stopped: one App log line with the source Workouts (the steps before it are Watch lines). */
    private fun problem(text: String) {
        Timber.w("H5600 workouts: $text")
        org.avmedia.gshockapi.log.LibraryLog.log(org.avmedia.gshockapi.log.LibraryLog.Source.Workouts, text)
    }

    /** A test seam: a scripted watch in place of the link. Null: the link (the transfer's own job, or the LifeLog job). */
    @Volatile
    internal var script: WorkoutLink? = null
}
