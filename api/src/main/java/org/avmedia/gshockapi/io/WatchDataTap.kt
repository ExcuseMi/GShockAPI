package org.avmedia.gshockapi.io

import org.avmedia.gshockapi.model.StepCounterData
import org.avmedia.gshockapi.model.WorkoutSession
import timber.log.Timber

/**
 * A copy of the watch reads that feed a screen (LifeLog, sleep, `0x28`, workouts, setting reads), raw and decoded, for
 * an app that keeps them for analysis. Nothing is done while [sink] is null. A sink that throws never touches the read.
 */
object WatchDataTap {
    /**
     * One read: its [kind] ("lifelog", "sleep", "condition", "workout", "setting"), the [raw] bytes, more raw parts by
     * name (a LifeLog section by its tag, "0x53"), the [decoded] result as the library made it, the request [code] of a
     * setting read and the watch's [model] name ("" when unknown).
     */
    class Read(
        val kind: String,
        val raw: ByteArray,
        val decoded: Any?,
        val parts: Map<String, ByteArray> = emptyMap(),
        val code: Int? = null,
        val model: String = "",
    )

    @Volatile
    var sink: ((Read) -> Unit)? = null

    private const val FRAME_HEADER_SIZE = 5
    private const val TAG_SLEEP_STAGES = 0x53
    private const val TAG_NIGHT = 0x5A
    private const val WORKOUT_FLAG_INDEX = 7
    private const val ACTIVITY_BYTE = 8
    private const val LIFELOG_BYTE = 9

    fun emit(read: Read) {
        val s = sink ?: return
        runCatching { s(read) }.onFailure { Timber.w(it, "Watch data copy failed") }
    }

    private fun tag(t: Int) = "0x%02X".format(t)

    /**
     * A decoded LifeLog read: the stream's header with every section as a part ("lifelog"), the sleep sections with the
     * decoded nights and stages ("sleep"), and the `0x28` answer of its setup ("condition").
     */
    internal fun lifeLog(model: String, notifications: List<ByteArray>, announced: Map<Int, Int>, condition: ByteArray?, decoded: StepCounterData) {
        if (sink == null) return
        runCatching {
            val stream = H5600HealthDecoder.lifeLogStream(notifications, announced)
            val spans = H5600HealthDecoder.locateFrames(stream, announced)
            val bodies = spans.mapValues { (_, r) -> if (r.first + FRAME_HEADER_SIZE <= r.last + 1) stream.copyOfRange(r.first + FRAME_HEADER_SIZE, r.last + 1) else ByteArray(0) }
            val headerEnd = spans.values.minOfOrNull { it.first } ?: stream.size
            emit(Read("lifelog", stream.copyOf(headerEnd), decoded.copy(raw = null), bodies.mapKeys { tag(it.key) }, model = model))
            val night = bodies[TAG_NIGHT]
            val stages = bodies[TAG_SLEEP_STAGES]
            if (night != null || stages != null) {
                emit(Read("sleep", night ?: ByteArray(0), decoded.h5600?.sleep, stages?.let { mapOf(tag(TAG_SLEEP_STAGES) to it) }.orEmpty(), model = model))
            }
            if (condition != null) emit(Read("condition", condition, conditionFlags(condition), model = model))
        }.onFailure { Timber.w(it, "Watch data copy failed") }
    }

    /** What the library reads from a `0x28` answer (code byte first): the workout flag and the two pending flags. */
    fun conditionFlags(b: ByteArray): Map<String, Any?> = mapOf(
        "workoutFlag" to b.getOrNull(WORKOUT_FLAG_INDEX)?.let { it.toInt() and 0xFF },
        "activityLogPending" to b.getOrNull(ACTIVITY_BYTE)?.let { it.toInt() != 0 },
        "lifeLogPending" to b.getOrNull(LIFELOG_BYTE)?.let { it.toInt() != 0 },
    )

    /** A workout read: the header, the data and lap blocks as read (each block whole), and the decoded session. */
    internal fun workout(header: WorkoutHeader, data: List<ByteArray>, laps: List<ByteArray>, session: WorkoutSession) {
        if (sink == null) return
        emit(Read("workout", header.raw, session, mapOf("data" to concat(data), "laps" to concat(laps))))
    }

    private fun concat(blocks: List<ByteArray>): ByteArray = blocks.fold(ByteArray(0)) { a, b -> a + b }

    /** A setting read that was answered: the frame, code first. */
    internal fun setting(model: String, frame: IntArray) {
        if (sink == null || frame.isEmpty()) return
        emit(Read("setting", ByteArray(frame.size) { frame[it].toByte() }, null, code = frame[0], model = model))
    }
}
