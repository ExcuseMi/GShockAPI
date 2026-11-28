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

/**
 * A time sync request: the zone to set, the clock to read (null: the library's clock at the moment of the write) and
 * the person's offset from it in ms (null: none), as `WatchApi.setTime` takes them.
 */
data class TimeSync(val timeZone: String, val timeMs: Long? = null, val offsetMs: Long? = null)

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
    val NIGHT_START = Key.of<TimeOfDay>("sleep.night_start")
    val NIGHT_END = Key.of<TimeOfDay>("sleep.night_end")
    val HR_MAX = Key.of<Int>("hr.max")
    val HR_RESTING = Key.of<Int>("hr.resting")
    val VO2MAX = Key.of<Int>("fitness.vo2max")
    val TRAINING_BACKGROUND = Key.of<String>("fitness.training_background")
    val FACE_CUSTOM_ITEM = Key.of<String>("face.custom_item")
    val NOTIFICATION_SOUND = Key.of<String>("notifications.sound")
    val NOTIFICATION_VIBRATION = Key.of<String>("notifications.vibration")
    val NOTIFICATION_PATTERN = Key.of<String>("notifications.pattern")
    val NOTIFICATION_LANGUAGE = Key.of<Int>("notifications.language")
    val NOTIFICATION_OFF_AT_NIGHT = Key.of<Int>("notifications.off_at_night")

    // Code keys: settings whose layout a data file cannot say (the H5600 driver's code).
    /** Whole centimetres; the driver converts for a watch that counts in inches. */
    val HEIGHT_CM = Key.of<Int>("profile.height_cm")

    /** Whole kilograms; the driver converts for a watch that counts in pounds. */
    val WEIGHT_KG = Key.of<Int>("profile.weight_kg")

    /** `YYYY-MM-DD`. */
    val BIRTH_DATE = Key.of<String>("profile.birth_date")

    /** "male" or "female". */
    val SEX = Key.of<String>("profile.sex")

    /** One activity in the watch's activity list, on or off: `activities.running`, `activities.gym_workout`. */
    fun activity(name: String): Key<Boolean> = Key.of("activities.${word(name)}")

    /** "Gym workout" is "gym_workout": lower case, words joined by `_`. */
    fun word(text: String): String =
        text.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").trim('_').replace(Regex("_+"), "_")

    @Suppress("UNCHECKED_CAST")
    val ALARMS: Key<List<AlarmSlot>> = Key("alarms", List::class.java as Class<List<AlarmSlot>>)

    // Code-only keys: protocol logic a data file cannot express.
    /** The whole time sync: DST, world cities, the home location and the time, stamped ahead by the Bluetooth lead. */
    val TIME = Key.of<TimeSync>("time.sync")

    /** The home city as the watch names it (`WatchApi.getHomeTime`). */
    val HOME_TIME = Key.of<String>("time.home_time", writable = false)

    /** The watch's DST state answer of [state] (`WatchApi.getDSTWatchState`), raw as the watch sent it. */
    fun dstWatchState(state: Int): Key<String> = Key.of("$DST.$state", writable = false)

    /** World city [index] (0 first) as the watch sent it (`WatchApi.getWorldCities`). */
    fun worldCity(index: Int): Key<String> = Key.of("$WORLD_CITIES.$index", writable = false)

    const val DST = "time.dst"
    const val WORLD_CITIES = "time.world_cities"

    /** The capability a key needs: its id, or for a key with an index ([worldCity], [dstWatchState]) its family's id. */
    fun capabilityOf(id: String): String = listOf(DST, WORLD_CITIES).firstOrNull { id.startsWith("$it.") } ?: id
    val STEPS_TODAY = Key.of<Int>("steps.today", writable = false)
    val BATTERY = Key.of<Int>("battery.level", writable = false)
}
