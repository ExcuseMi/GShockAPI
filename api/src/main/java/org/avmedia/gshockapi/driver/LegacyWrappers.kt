package org.avmedia.gshockapi.driver

import org.avmedia.gshockapi.model.Alarm

/**
 * How an old call would move over (an example only: the real calls are not annotated). Each old call stays, as a
 * wrapper over the engine, @Deprecated at WARNING first, ERROR in a later minor release, removed in the next major.
 * Same names and types as today's calls, so a caller changes nothing until it follows the ReplaceWith.
 */
class LegacyWrappersExample(private val watch: BoundWatch) {
    /** Today: WatchApi.getAlarms(). */
    @Deprecated("Use the engine's get", ReplaceWith("watch.get(Keys.ALARMS)"), DeprecationLevel.WARNING)
    suspend fun getAlarms(): ArrayList<Alarm> =
        ArrayList(watch.get(Keys.ALARMS).valueOrNull().orEmpty().map { Alarm(it.hour, it.minute, it.enabled, it.hourlyChime, it.oneTime) })

    /** Today: WatchApi.setAlarms(alarms). */
    @Deprecated("Use the engine's set", ReplaceWith("watch.set(Keys.ALARMS, alarms)"), DeprecationLevel.WARNING)
    suspend fun setAlarms(alarms: ArrayList<Alarm>) {
        watch.set(Keys.ALARMS, alarms.map { AlarmSlot(it.hour, it.minute, it.enabled, it.oneTime, it.hasHourlyChime) })
    }

    /** Today: the app reads 0x13 and decodes it with LightSettingsCodec.autoLight. */
    @Deprecated("Use the engine's get", ReplaceWith("watch.get(Keys.AUTO_LIGHT)"), DeprecationLevel.WARNING)
    suspend fun getAutoLight(): Boolean? = watch.get(Keys.AUTO_LIGHT).valueOrNull()

    /** Today: H5600SettingsIO.getSoundMode(). */
    @Deprecated("Use the engine's get", ReplaceWith("watch.get(Keys.SOUND_MODE)"), DeprecationLevel.WARNING)
    suspend fun getSoundMode(): String? = watch.get(Keys.SOUND_MODE).valueOrNull()
}
