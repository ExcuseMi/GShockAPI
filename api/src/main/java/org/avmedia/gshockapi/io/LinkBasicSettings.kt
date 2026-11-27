package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.link.WatchLink
import org.avmedia.gshockapi.model.Settings
import org.avmedia.gshockapi.model.SoundMode
import org.avmedia.gshockapi.model.WatchFaceItem
import org.json.JSONObject
import timber.log.Timber

/**
 * One link's basic settings (`13`), encoded for that link's model. A DW-H5600 has no `13`: its settings are the
 * link's [LinkH5600Settings] packets. The read is a command job on this link and its answer comes from this link's
 * watch only. Made only by its link's [LinkIO].
 */
@RequiresApi(Build.VERSION_CODES.O)
class LinkBasicSettings internal constructor(private val link: WatchLink, private val h5600: LinkH5600Settings) {
    private val answer = LinkAnswer<Settings>()

    suspend fun request(): Settings = link.cache.request("GET_SETTINGS") { answer.ask { sendToWatch() } }

    fun set(settings: Settings) {
        val json = Gson().toJson(settings)
        link.cache.set("GET_SETTINGS") { sendToWatchSet("{action: \"SET_SETTINGS\", value: $json}") }
    }

    fun onReceived(data: String) {
        SettingsIOFunctional.decode(data, link.watchInfo.info).fold(
            onSuccess = { answer.complete(it) },
            onFailure = {
                Timber.e("Failed to decode settings: ${it.message}")
                answer.fail(it)
            },
        )
    }

    /** The watch refused the read (`FF 81 13`): the read ends with empty settings. */
    fun onRunError() = answer.complete(Settings())

    /** The read of this link's settings ("GET_SETTINGS"). */
    fun sendToWatch() {
        if (!h5600.isSupported()) {
            link.command(GetSetMode.GET, SettingsIOFunctional.buildFetchCommand())
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val settings = Settings()
            runCatching { settings.soundMode = h5600.getSoundMode()?.name }.onFailure { Timber.w(it, "Failed to read soundMode") }
            runCatching { settings.watchFaceNumber = h5600.getWatchFaceNumber() }.onFailure { Timber.w(it, "Failed to read watchFaceNumber") }
            runCatching { settings.watchFaceItem = h5600.getWatchFaceItem()?.name }.onFailure { Timber.w(it, "Failed to read watchFaceItem") }
            runCatching { settings.smartAlarm = h5600.getSmartAlarm() }.onFailure { Timber.w(it, "Failed to read smartAlarm") }
            answer.complete(settings)
        }
    }

    /** The write of this link's settings ("SET_SETTINGS", the settings in `value`). */
    fun sendToWatchSet(message: String) {
        val jsonObj = JSONObject(message)
        val value = jsonObj.optJSONObject("value") ?: jsonObj
        if (!h5600.isSupported()) {
            link.command(GetSetMode.SET, SettingsIOFunctional.encode(value, link.watchInfo.info))
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { setH5600(value) }.onFailure { Timber.e(it, "Failed to set DW-H5600 settings") }
        }
    }

    private suspend fun setH5600(value: JSONObject) {
        value.optString("soundMode", "").takeIf { it.isNotEmpty() }?.let { s ->
            SoundMode.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }?.let { h5600.setSoundMode(it) }
        }
        if (value.has("watchFaceNumber")) h5600.setWatchFaceNumber(value.getInt("watchFaceNumber"))
        value.optString("watchFaceItem", "").takeIf { it.isNotEmpty() }?.let { s ->
            WatchFaceItem.entries.firstOrNull { it.name.equals(s, ignoreCase = true) }?.let { h5600.setWatchFaceItem(it) }
        }
        // The smart alarm (0x50) is never written from here: a Settings object read earlier carries an old copy of it,
        // and writing that back undid the wake-up alarm's smart alarm. It has its own writes.
    }
}
