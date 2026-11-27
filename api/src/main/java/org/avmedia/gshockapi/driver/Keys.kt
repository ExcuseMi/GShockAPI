package org.avmedia.gshockapi.driver

/**
 * A typed capability key: what a driver can read ([get]) or change ([set]), and the type of its value. The [id] is the
 * name the data files use; two keys with the same id are the same capability.
 */
class Key<T : Any>(val id: String, val type: Class<T>, val writable: Boolean = true) {
    override fun equals(other: Any?): Boolean = other is Key<*> && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id

    companion object {
        inline fun <reified T : Any> of(id: String, writable: Boolean = true): Key<T> = Key(id, T::class.javaObjectType, writable)
    }
}

data class TimeOfDay(val hour: Int, val minute: Int)

/** One alarm slot, as a data file's record fields name it. */
data class AlarmSlot(val hour: Int, val minute: Int, val enabled: Boolean, val oneTime: Boolean = false, val hourlyChime: Boolean = false)

/** A time sync request: the zone to set, and the clock to read (null: the phone's). */
data class TimeSync(val timeZone: String, val timeMs: Long? = null)

/**
 * The known keys. A data file may name others; [Key.of] makes a key for any id. A choice is the value name in the
 * data file ("short", "always"); a record list is a list of field maps.
 */
object Keys {
    val AUTO_LIGHT = Key.of<Boolean>("light.auto_light")
    val LIGHT_DURATION = Key.of<String>("light.duration")
    val LENGTH_UNIT = Key.of<String>("units.length")
    val STEP_REMINDER = Key.of<Boolean>("steps.reminder")
    val STEP_GOAL = Key.of<Int>("steps.goal")
    val HR_MEASUREMENT = Key.of<String>("hr.measurement")
    val SOUND_MODE = Key.of<String>("sound.mode")
    val FACE = Key.of<Int>("face.number")
    val BEDTIME = Key.of<TimeOfDay>("sleep.bedtime")
    val WAKE_UP = Key.of<TimeOfDay>("sleep.wake_up")
    val SMART_ALARM_ON = Key.of<Boolean>("smart_alarm.on")
    val SMART_ALARM_SNOOZE = Key.of<Boolean>("smart_alarm.snooze")
    val SMART_ALARM_TIME = Key.of<TimeOfDay>("smart_alarm.time")
    val SMART_ALARM_WINDOW = Key.of<Int>("smart_alarm.window")

    @Suppress("UNCHECKED_CAST")
    val ALARMS: Key<List<AlarmSlot>> = Key("alarms", List::class.java as Class<List<AlarmSlot>>)

    // Code-only keys: protocol logic a data file cannot express.
    val TIME = Key.of<TimeSync>("time.sync")
    val STEPS_TODAY = Key.of<Int>("steps.today", writable = false)
    val BATTERY = Key.of<Int>("battery.level", writable = false)
}
