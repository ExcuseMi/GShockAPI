package org.avmedia.gshockapi.driver

import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import org.avmedia.gshockapi.io.ActivityListCodec
import org.avmedia.gshockapi.io.ActivityModels
import org.avmedia.gshockapi.io.IO
import org.avmedia.gshockapi.io.ProfileCodec
import org.avmedia.gshockapi.io.WatchDataTap
import org.avmedia.gshockapi.link.LinkState

/**
 * The H5600 family: the data file ([file], `h5600.json` in the library's resources) for the simple settings, and code
 * for what it cannot say: the profile (0x45, inverted BCD in cm or inches) and the activity list (0x53) through their
 * codecs. The alarm slots are the file's records (each frame read fresh, changed, written, read back). The other code
 * parts hand over to today's H5600 code on the link (time sync, the time reads, the LifeLog transfer, battery steps);
 * nothing of it is copied or changed here.
 */
@RequiresApi(Build.VERSION_CODES.O)
class H5600Driver(file: DataFile) : DataDriver(file) {
    private fun faces(conn: WatchConnection): Int = file.model(conn.name)?.faces ?: 3

    private fun linkOf(conn: WatchConnection): LinkConnection? = conn as? LinkConnection

    private fun needsLink(key: Key<*>) = DriverResult.Unsupported(key.id, "${key.id} needs the link engine")

    override fun frameSetting(key: String): FrameSetting? = CODE_SETTINGS[key] ?: super.frameSetting(key)

    override suspend fun <T : Any> get(conn: WatchConnection, key: Key<T>): DriverResult<T> {
        unsupported(key, conn)?.let { return it }
        val value: Any = when {
            key == Keys.STEPS_TODAY -> {
                val link = linkOf(conn) ?: return needsLink(key)
                link.api.getStepCount(peek = true).currentDaySteps ?: return DriverResult.Failed(key.id, "No step count yet.")
            }
            key == Keys.BATTERY -> (linkOf(conn) ?: return needsLink(key)).api.getBatteryLevel()
            key == Keys.TIME -> return DriverResult.Unsupported(key.id, "the time is written, not read")
            // Health data: today's LifeLog and workout transfers on the link, as they are.
            key == Keys.LIFELOG || key == Keys.LIFELOG_PEEK ->
                return code(key) { (linkOf(conn) ?: return needsLink(key)).api.getStepCount(peek = key == Keys.LIFELOG_PEEK) }
            key == Keys.SLEEP -> return code(key) {
                (linkOf(conn) ?: return needsLink(key)).api.getStepCount(peek = true).h5600?.sleep ?: error("The LifeLog read had no sleep.")
            }
            key == Keys.WORKOUTS -> return code(key) {
                (linkOf(conn) ?: return needsLink(key)).api.downloadWorkouts(userAsked = true) ?: error("The workout read did not start.")
            }
            key == Keys.CONDITION -> conn.readFrame(CONDITION_CODE) ?: return DriverResult.Failed(key.id, NO_ANSWER)
            // Today's per-link time reads, as they are.
            key == Keys.HOME_TIME -> return code(key) { (linkOf(conn) ?: return needsLink(key)).api.getHomeTime() }
            key.id.startsWith(Keys.DST + ".") -> {
                val state = IO.DstState.entries.firstOrNull { it.state == index(key) } ?: return DriverResult.Unsupported(key.id, "no DST state ${index(key)}")
                return code(key) { (linkOf(conn) ?: return needsLink(key)).api.getDSTWatchState(state) }
            }
            key.id.startsWith(Keys.WORLD_CITIES + ".") -> {
                val i = index(key) ?: return DriverResult.Unsupported(key.id, "no city number")
                return code(key) { (linkOf(conn) ?: return needsLink(key)).api.getWorldCities(i) }
            }
            else -> return super.get(conn, key)
        }
        return DriverResult.Ok(key.type.cast(value)!!)
    }

    private fun index(key: Key<*>): Int? = key.id.substringAfterLast('.').toIntOrNull()

    /** Runs today's code for [key]: its answer, or Failed with its message. A cancellation stays a cancellation. */
    private inline fun <T : Any> code(key: Key<*>, block: () -> Any): DriverResult<T> = try {
        @Suppress("UNCHECKED_CAST")
        DriverResult.Ok(block() as T)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DriverResult.Failed(key.id, e.message ?: "The watch did not answer.")
    }

    override suspend fun <T : Any> set(conn: WatchConnection, key: Key<T>, value: T): DriverResult<Unit> {
        unsupported(key, conn)?.let { return it }
        return when (key) {
            // Today's time sync on the link: the zone, DST and cities, then the time stamped ahead by the Bluetooth lead.
            Keys.TIME -> {
                val link = linkOf(conn) ?: return needsLink(key)
                val sync = value as TimeSync
                code(key) { link.api.setTime(sync.timeZone, sync.timeMs, sync.offsetMs) }
            }
            // The data file's records, each frame from a fresh read: only the slots' fields and fixed byte change.
            Keys.ALARMS -> {
                @Suppress("UNCHECKED_CAST")
                super.set(conn, key, asWritten(value as List<AlarmSlot>) as T)
            }
            Keys.FACE ->
                if ((value as Int) > faces(conn)) DriverResult.Unsupported(key.id, "${DataFile.modelName(conn.name)} has faces 1 to ${faces(conn)}")
                else super.set(conn, key, value)
            else -> super.set(conn, key, value)
        }
    }

    /** A setting in code: [read] and [write] are today's codec calls. */
    private class CodeSetting(
        override val key: String,
        override val code: Int,
        override val touched: Set<Int>,
        private val read: (ByteArray) -> Any?,
        private val same: ((ByteArray, ByteArray) -> Boolean)? = null,
        private val write: (ByteArray, Any) -> ByteArray?,
    ) : FrameSetting {
        override fun decode(frame: ByteArray): Any? = runCatching { read(frame) }.getOrNull()

        override fun encode(frame: ByteArray, value: Any): ByteArray? = runCatching { write(frame, value) }.getOrNull()

        override fun matches(back: ByteArray, expected: ByteArray): Boolean = same?.invoke(back, expected) ?: back.contentEquals(expected)
    }

    companion object {
        /** The watch condition request. */
        const val CONDITION_CODE = 0x28

        /**
         * The slots as today's alarm writer sends them: "one time" only on an alarm that is on, and never on the first
         * (the writer has never set it there; not checked on a watch).
         */
        fun asWritten(slots: List<AlarmSlot>): List<AlarmSlot> =
            slots.mapIndexed { i, a -> a.copy(oneTime = i > 0 && a.enabled && a.oneTime) }

        private fun inches(cm: Int): Int = Math.round(cm / 2.54).toInt()

        private fun pounds(kg: Int): Int = Math.round(kg * 2.2046226).toInt()

        private val PROFILE: List<FrameSetting> = listOf(
            CodeSetting(Keys.HEIGHT_CM.id, ProfileCodec.CODE, ProfileCodec.BYTES, { f -> ProfileCodec.parse(f)?.heightCm }) { f, v ->
                val p = ProfileCodec.parse(f) ?: return@CodeSetting null
                val cm = v as Int
                ProfileCodec.withHeightAndWeight(f, if (p.imperialLength) inches(cm) else cm, p.weight)
            },
            CodeSetting(Keys.WEIGHT_KG.id, ProfileCodec.CODE, ProfileCodec.BYTES, { f -> ProfileCodec.parse(f)?.weightKg }) { f, v ->
                val p = ProfileCodec.parse(f) ?: return@CodeSetting null
                val kg = v as Int
                ProfileCodec.withHeightAndWeight(f, p.height, if (p.weightNotKg) pounds(kg) else kg)
            },
            CodeSetting(
                Keys.BIRTH_DATE.id, ProfileCodec.CODE, ProfileCodec.BIRTH_BYTES,
                { f ->
                    ProfileCodec.parse(f)?.let { p ->
                        if (p.birthYear != null && p.birthMonth != null && p.birthDay != null) "%04d-%02d-%02d".format(p.birthYear, p.birthMonth, p.birthDay) else null
                    }
                },
            ) { f, v ->
                val parts = (v as String).split("-").map(String::toInt)
                if (parts.size != 3) null else ProfileCodec.withBirthDate(f, parts[0], parts[1], parts[2])
            },
            CodeSetting(Keys.SEX.id, ProfileCodec.CODE, ProfileCodec.SEX_BYTES, { f -> ProfileCodec.parse(f)?.let { if (it.female) "female" else "male" } }) { f, v ->
                if (v != "male" && v != "female") null else ProfileCodec.withFemale(f, v == "female")
            },
        )

        /** One on/off setting per activity the family offers: the list codec toggles one code at a time in the fresh frame. */
        private val ACTIVITIES: List<FrameSetting> = ActivityModels.offered(ActivityModels.H5600).map { code ->
            CodeSetting(
                Keys.activity(ActivityModels.name(ActivityModels.H5600, code)).id, ActivityListCodec.CODE, (1..ActivityListCodec.MAX).toSet(),
                { f -> if (ActivityListCodec.layoutOk(f)) ActivityListCodec.parse(f)?.let { code in it } else null },
                ActivityListCodec::sameSlots,
            ) { f, v -> ActivityListCodec.parse(f)?.let { ActivityListCodec.encode(f, ActivityListCodec.toggle(it, code, v as Boolean)) } }
        }

        private val CODE_SETTINGS: Map<String, FrameSetting> = (PROFILE + ACTIVITIES).associateBy { it.key }
    }

    /**
     * The connection changes, the copy of each watch read of this watch ([org.avmedia.gshockapi.io.WatchDataTap], as
     * [WatchEvent.Data] of kind [Keys.WATCH_DATA]) and, on the link, the button of each new connection and each workout.
     */
    override fun events(conn: WatchConnection): Flow<WatchEvent> {
        val reads = WatchDataTap.reads
            .filter { DataFile.modelName(it.model.ifBlank { conn.name }) == DataFile.modelName(conn.name) }
            .map { WatchEvent.Data(Keys.WATCH_DATA, it) }
        val link = conn as? LinkConnection ?: return merge(super.events(conn), reads)
        val buttons = conn.state.filter { it == LinkState.READY }.map { WatchEvent.ButtonPressed(link.button()) }
        val workouts = link.api.workouts.map { WatchEvent.Data("workout", it) }
        return merge(super.events(conn), buttons, workouts, reads)
    }
}
