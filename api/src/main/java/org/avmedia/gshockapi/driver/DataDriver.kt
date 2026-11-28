package org.avmedia.gshockapi.driver

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.avmedia.gshockapi.io.SettingWriteResult
import org.avmedia.gshockapi.link.LinkState

/**
 * A driver made from a data file alone: model match, capabilities and the simple settings. A family whose settings
 * are all bits in frames needs nothing more. A code driver extends it for what the file cannot say.
 */
open class DataDriver(val file: DataFile) : WatchDriver {
    override val id: String get() = file.id

    override fun matches(advertisedName: String): Boolean = file.model(advertisedName) != null

    override fun capabilities(conn: WatchConnection): Set<String> =
        file.model(conn.name)?.let { file.capabilities + it.capabilitiesAdd }.orEmpty()

    protected fun unsupported(key: Key<*>, conn: WatchConnection): DriverResult.Unsupported? = when {
        file.model(conn.name) == null -> DriverResult.Unsupported(key.id, "${conn.name} is not a ${file.id} watch")
        Keys.capabilityOf(key.id) !in capabilities(conn) -> DriverResult.Unsupported(key.id, "${DataFile.modelName(conn.name)} does not have ${key.id}")
        else -> null
    }

    /**
     * The pure codec of the frame setting [key] for this family (no watch needed), or null when the key is not one
     * frame's setting here. A code driver adds its own.
     */
    open fun frameSetting(key: String): FrameSetting? = file.frameSetting(key)

    override suspend fun <T : Any> get(conn: WatchConnection, key: Key<T>): DriverResult<T> {
        unsupported(key, conn)?.let { return it }
        val value: Any = if (file.settings[key.id]?.type == "records") {
            val spec = file.settings.getValue(key.id)
            val maps = spec.frames.flatMap { rf ->
                val frame = conn.readFrame(rf.code) ?: return DriverResult.Failed(key.id, NO_ANSWER)
                DataSettingCodec.decodeRecords(spec, frame).orEmpty()
            }
            if (key == Keys.ALARMS) maps.map(::slot) else maps
        } else {
            val setting = frameSetting(key.id) ?: return DriverResult.Unsupported(key.id, "no layout for ${key.id}")
            val frame = conn.readFrame(setting.code) ?: return DriverResult.Failed(key.id, NO_ANSWER)
            setting.decode(frame) ?: return DriverResult.Failed(key.id, NOT_KNOWN)
        }
        if (!key.type.isInstance(value)) return DriverResult.Failed(key.id, "${key.id} is not a ${key.type.simpleName}")
        return DriverResult.Ok(key.type.cast(value)!!)
    }

    override suspend fun <T : Any> set(conn: WatchConnection, key: Key<T>, value: T): DriverResult<Unit> {
        unsupported(key, conn)?.let { return it }
        if (!key.writable) return DriverResult.Unsupported(key.id, "${key.id} is read-only")
        file.settings[key.id]?.takeIf { it.type == "records" }?.let { return setRecords(conn, key, it, value) }
        val setting = frameSetting(key.id) ?: return DriverResult.Unsupported(key.id, "no layout for ${key.id}")
        return write(conn, setting, value).toDriverResult(key.id)
    }

    /** The checked write of [value] to [setting]: read, change only its bytes, write, read back. */
    suspend fun write(conn: WatchConnection, setting: FrameSetting, value: Any): SettingWriteResult =
        conn.modify(setting.code, setting.touched, setting::expectedBack, setting::matches) { frame -> setting.encode(frame, value) }

    private suspend fun setRecords(conn: WatchConnection, key: Key<*>, spec: SettingSpec, value: Any): DriverResult<Unit> {
        @Suppress("UNCHECKED_CAST")
        val maps = (value as? List<Any>)?.map { if (it is AlarmSlot) fields(it) else it as? Map<String, Any> ?: return DriverResult.Failed(key.id, "not a record") }
            ?: return DriverResult.Failed(key.id, "not a list of records")
        var rest = maps
        for (rf in spec.frames) {
            val mine = rest.take(rf.count).also { rest = rest.drop(rf.count) }
            if (mine.isEmpty()) break
            val r = conn.modify(rf.code, DataSettingCodec.touchedRecords(spec, rf.code)) { DataSettingCodec.encodeRecords(spec, it, mine) }
            val d = r.toDriverResult(key.id)
            if (d !is DriverResult.Ok) return d
        }
        return DriverResult.Ok(Unit)
    }

    override fun events(conn: WatchConnection): Flow<WatchEvent> =
        conn.state.map { if (it == LinkState.DISCONNECTED) WatchEvent.Disconnected else WatchEvent.Connected }.distinctUntilChanged()

    companion object {
        const val NO_ANSWER = "The watch did not answer."
        const val NOT_KNOWN = "The watch sent a value this file does not know."

        fun slot(m: Map<String, Any>) = AlarmSlot(
            hour = m["hour"] as Int, minute = m["minute"] as Int, enabled = m["enabled"] == true,
            oneTime = m["one_time"] == true, hourlyChime = m["hourly_chime"] == true,
        )

        fun fields(a: AlarmSlot): Map<String, Any> =
            mapOf("hour" to a.hour, "minute" to a.minute, "enabled" to a.enabled, "one_time" to a.oneTime, "hourly_chime" to a.hourlyChime)

        fun SettingWriteResult.toDriverResult(key: String): DriverResult<Unit> = when (this) {
            SettingWriteResult.Unchanged, SettingWriteResult.Done, SettingWriteResult.Written -> DriverResult.Ok(Unit)
            SettingWriteResult.Unconfirmed -> DriverResult.Unconfirmed(key)
            is SettingWriteResult.Failed -> DriverResult.Failed(key, message)
        }
    }
}
