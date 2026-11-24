package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.model.ActivityPeriod
import org.avmedia.gshockapi.model.StepCounterData
import timber.log.Timber
import java.time.LocalDateTime

// ============================================================================
// Pure Functional Core: Step Counter Decoding
// ============================================================================

/**
 * Pure functional core for step counter processing.
 */
@RequiresApi(Build.VERSION_CODES.O)
object StepCounterIOFunctional {
    private const val HEADER_SIZE = 6
    private const val ACTIVITY_RECORD_SIZE = 10
    private const val SENTINEL_BUCKET_VALUE = 0xFFFE
    private const val SENTINEL_DAILY_VALUE = -2 // 0xFFFFFFFE
    private const val ACTIVITY_SCAN_LIMIT = 146
    private const val COMMITTED_DISTANCE_OFFSET = 246
    private const val COMMITTED_DISTANCE_END = 318
    private const val DAILY_SUMMARY_OFFSET = 318
    private const val DAILY_SUMMARY_COUNT = 7
    private const val DAILY_SUMMARY_SIZE = 8
    private const val CURRENT_STEPS_OFFSET = 374
    private const val CURRENT_DISTANCE_OFFSET = 378
    private const val PENDING_INTENSITY_OFFSET = 382
    private const val PENDING_DISTANCE_OFFSET = 392
    private const val BCD_TOTAL_OFFSET = 396
    private const val PACKET_HEADER_MARKER = 0x26

    fun parse(payload: ByteArray): StepCounterData? {
        if (payload.isEmpty() || (payload[0].toInt() and 0xFF) != PACKET_HEADER_MARKER) {
            return null
        }

        val warnings = mutableListOf<String>()
        var timestamp: LocalDateTime? = null

        if (payload.size >= 6) {
            try {
                val year = 2000 + decodeBcd(payload[0].toInt() and 0xFF)
                val month = decodeBcd(payload[1].toInt() and 0xFF)
                val day = decodeBcd(payload[2].toInt() and 0xFF)
                val hour = decodeBcd(payload[3].toInt() and 0xFF)
                val minute = decodeBcd(payload[4].toInt() and 0xFF)
                val second = decodeBcd(payload[5].toInt() and 0xFF)
                timestamp = LocalDateTime.of(year, month, day, hour, minute, second)
            } catch (e: Exception) {
                Timber.e(e, "invalid BCD timestamp in step counter header")
                warnings.add("invalid BCD timestamp in step counter header")
            }
        }

        val currentDaySteps =
            readUnsignedIntOrNull(payload, CURRENT_STEPS_OFFSET).let {
                if (it == SENTINEL_DAILY_VALUE) null else it
            }

        var pendingSteps = 0
        val pendingIntensity = IntArray(3)
        if (payload.size >= PENDING_INTENSITY_OFFSET + 6) {
            for (i in 0 until 3) {
                val value = readUnsignedShortOrNull(payload, PENDING_INTENSITY_OFFSET + i * 2) ?: 0
                pendingIntensity[i] = value
                if (value != SENTINEL_BUCKET_VALUE) {
                    pendingSteps += value
                }
            }
        }

        // Activity records are variable-length 10-byte records. Find the
        // boundary by reconciling their bucket sums with today's total.
        var recordEnd = HEADER_SIZE
        if (currentDaySteps != null) {
            var minDiff = Int.MAX_VALUE
            for (end in HEADER_SIZE..ACTIVITY_SCAN_LIMIT step ACTIVITY_RECORD_SIZE) {
                var frontTotal = 0
                for (offset in HEADER_SIZE until end step ACTIVITY_RECORD_SIZE) {
                    for (i in 0 until 5) {
                        val bucket = readUnsignedShortOrNull(payload, offset + i * 2)
                        if (bucket != null && bucket != SENTINEL_BUCKET_VALUE) {
                            frontTotal += bucket
                        }
                    }
                }
                val diff = Math.abs(currentDaySteps - pendingSteps - frontTotal)
                if (diff < minDiff) {
                    minDiff = diff
                    recordEnd = end
                }
            }
        }

        val activitySteps = mutableListOf<Int?>()
        val hourlyIntensities = mutableListOf<IntArray>()
        val hourlyIntervals = mutableListOf<ActivityPeriod>()

        for (offset in HEADER_SIZE until recordEnd step ACTIVITY_RECORD_SIZE) {
            var steps = 0
            val buckets = IntArray(5)
            for (i in 0 until 5) {
                val bucket = readUnsignedShortOrNull(payload, offset + i * 2) ?: 0
                buckets[i] = bucket
                if (bucket != SENTINEL_BUCKET_VALUE) {
                    steps += bucket
                }
            }
            val stepsOrNull = if (steps > 0) steps else null
            activitySteps.add(stepsOrNull)
            hourlyIntensities.add(buckets)
            hourlyIntervals.add(
                ActivityPeriod(
                    index = (offset - HEADER_SIZE) / ACTIVITY_RECORD_SIZE,
                    steps = stepsOrNull,
                    intensity = buckets,
                ),
            )
        }

        val distanceMeters = readUnsignedIntOrNull(payload, CURRENT_DISTANCE_OFFSET)
        val pendingDistanceMeters = readUnsignedIntOrNull(payload, PENDING_DISTANCE_OFFSET)

        val committedDistances = mutableListOf<Int>()
        if (distanceMeters != null && pendingDistanceMeters != null && distanceMeters >= pendingDistanceMeters) {
            val committedTarget = distanceMeters - pendingDistanceMeters
            if (committedTarget > 0) {
                var distanceSum = 0
                for (offset in COMMITTED_DISTANCE_OFFSET until COMMITTED_DISTANCE_END step 2) {
                    val value = readUnsignedShortOrNull(payload, offset) ?: 0
                    if (value == SENTINEL_BUCKET_VALUE) continue
                    committedDistances.add(value)
                    distanceSum += value
                    if (distanceSum >= committedTarget) break
                }
                if (distanceSum != committedTarget) {
                    committedDistances.clear()
                    warnings.add("distance components do not reconcile to $committedTarget m")
                }
            }
        }

        val dailyHistory = mutableListOf<Int?>()
        val dailyDistances = mutableListOf<Int?>()
        for (i in 0 until DAILY_SUMMARY_COUNT) {
            val offset = DAILY_SUMMARY_OFFSET + i * DAILY_SUMMARY_SIZE
            if (offset + DAILY_SUMMARY_SIZE > payload.size) break

            val steps = readUnsignedIntOrNull(payload, offset)
            val distance = readUnsignedIntOrNull(payload, offset + 4)

            if (steps == SENTINEL_DAILY_VALUE && distance == SENTINEL_DAILY_VALUE) {
                dailyHistory.add(null)
                dailyDistances.add(null)
            } else {
                dailyHistory.add(if (steps == SENTINEL_DAILY_VALUE) null else steps)
                dailyDistances.add(if (distance == SENTINEL_DAILY_VALUE) null else distance)
            }
        }

        var bcdTotalSteps: Int? = null
        if (payload.size >= BCD_TOTAL_OFFSET + 4) {
            try {
                bcdTotalSteps = 0
                for (i in 0 until 4) {
                    val b = payload[BCD_TOTAL_OFFSET + i].toInt() and 0xFF
                    if (b != 0) {
                        bcdTotalSteps = bcdTotalSteps!! + decodeBcd(b) * Math.pow(100.0, i.toDouble()).toInt()
                    }
                }
            } catch (e: Exception) {
                bcdTotalSteps = null
            }
        }

        if (bcdTotalSteps != null && bcdTotalSteps > 0 && currentDaySteps != null && bcdTotalSteps != currentDaySteps) {
            warnings.add("BCD total $bcdTotalSteps differs from current step count $currentDaySteps")
        }

        val hourlyByHour = MutableList<Int?>(24) { null }
        if (timestamp != null) {
            activitySteps.forEachIndexed { index, steps ->
                if (steps != null) {
                    val h = (timestamp.hour - index - 1).let { if (it < 0) it + 24 else it }
                    hourlyByHour[h] = steps
                }
            }
            if (pendingSteps > 0) {
                hourlyByHour[timestamp.hour] = pendingSteps
            }
        }

        return StepCounterData(
            timestamp = timestamp,
            dayOfWeek = timestamp?.dayOfWeek?.value,
            month = timestamp?.monthValue,
            dayOfMonth = timestamp?.dayOfMonth,
            hourlySteps = activitySteps,
            dailyHistory = dailyHistory,
            dailyDistances = dailyDistances,
            currentDaySteps = currentDaySteps,
            distanceMeters = distanceMeters,
            pendingDistanceMeters = pendingDistanceMeters,
            totalDistanceMeters = distanceMeters,
            bcdTotalSteps = bcdTotalSteps,
            hourlyIntensities = hourlyIntensities,
            pendingIntensity = pendingIntensity,
            committedDistances = committedDistances,
            hourlyIntervals = hourlyIntervals,
            hourlyByHour = hourlyByHour,
            raw = payload,
            warnings = warnings,
        )
    }

    private fun decodeBcd(byte: Int): Int {
        val high = byte / 16
        val low = byte % 16
        if (high > 9 || low > 9) throw IllegalArgumentException("invalid BCD byte")
        return high * 10 + low
    }

    private fun readUnsignedShortOrNull(
        payload: ByteArray,
        offset: Int,
    ): Int? {
        if (offset + 2 > payload.size) return null
        val value = (payload[offset].toInt() and 0xFF) or ((payload[offset + 1].toInt() and 0xFF) shl 8)
        return if (value == 0xFFFF) null else value
    }

    private fun readUnsignedIntOrNull(
        payload: ByteArray,
        offset: Int,
    ): Int? {
        if (offset + 4 > payload.size) return null
        return (payload[offset].toInt() and 0xFF) or
            ((payload[offset + 1].toInt() and 0xFF) shl 8) or
            ((payload[offset + 2].toInt() and 0xFF) shl 16) or
            ((payload[offset + 3].toInt() and 0xFF) shl 24)
    }
}

// ============================================================================
// Imperative Shell: Side Effects & State Management
// ============================================================================

@RequiresApi(Build.VERSION_CODES.O)
object StepCounterIO {
    private val one: LinkStepCounter get() = org.avmedia.gshockapi.casio.WatchLink.io.stepCounter

    suspend fun request(peek: Boolean = true): StepCounterData = one.request(peek)

    fun onDrspReceived(data: ByteArray) = one.onDrspReceived(data)

    fun onReceived(data: String) = one.onReceived(data)
}
