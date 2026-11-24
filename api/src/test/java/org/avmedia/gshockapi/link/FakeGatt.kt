package org.avmedia.gshockapi.link

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import org.avmedia.gshockapi.casio.WatchChannel

/** A watch's GATT in a test: every operation is logged ("sub 1", "w B0 05"), with how many ran at once. */
class FakeGatt(
    private val writeMs: Long = 2,
    private val subscribeMs: Long = 5,
    private val chars: List<UUID> = List(3) { UUID(0L, it.toLong()) },
    /** Shared between the fakes of several links: how many operations ran at once over all links. */
    val global: AtomicInteger = AtomicInteger(),
) : LinkGatt {
    val ops = CopyOnWriteArrayList<String>()
    val maxInFlight = AtomicInteger()
    val maxGlobal = AtomicInteger()
    private val inFlight = AtomicInteger()

    /** The watch is away: writes fail. */
    @Volatile var down = false

    /** While set, every write waits for it on the air (a block that takes as long as the test wants). */
    @Volatile var hold: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /** Called at the start of each write, with the frame; a test hooks in here (ask a job "while block 5 is on the air"). */
    @Volatile var onWrite: (ByteArray) -> Unit = {}

    private suspend fun <T> op(label: String, ms: Long, body: suspend () -> T): T {
        maxInFlight.updateAndGet { maxOf(it, inFlight.incrementAndGet()) }
        maxGlobal.updateAndGet { maxOf(it, global.incrementAndGet()) }
        ops += label
        try {
            delay(ms)
            return body()
        } finally {
            inFlight.decrementAndGet()
            global.decrementAndGet()
        }
    }

    override suspend fun write(channel: WatchChannel, frame: ByteArray): Boolean {
        onWrite(frame)
        val label = "w " + frame.joinToString(" ") { "%02X".format(it) }
        return op(label, writeMs) {
            hold?.await()
            !down
        }
    }

    override fun notifiable(): List<UUID> = chars

    override suspend fun subscribe(characteristic: UUID): Boolean = op("sub ${characteristic.leastSignificantBits}", subscribeMs) { !down }

    fun writes(): List<String> = ops.filter { it.startsWith("w ") }
}
