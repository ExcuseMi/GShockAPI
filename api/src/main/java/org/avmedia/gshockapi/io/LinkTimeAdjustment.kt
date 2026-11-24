package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.link.WatchLink
import org.avmedia.gshockapi.model.Settings
import org.avmedia.gshockapi.utils.Utils
import org.json.JSONObject
import timber.log.Timber

/**
 * One link's BLE settings (`11`): the automatic time adjustment and its minute. A write changes only those bytes of
 * what this link's watch last sent. The read is a command job on this link, its answer comes from this link's watch
 * only. Made only by its link's [LinkIO].
 */
@RequiresApi(Build.VERSION_CODES.O)
class LinkTimeAdjustment internal constructor(private val link: WatchLink) {
    private val answer = LinkAnswer<TimeAdjustmentInfo>()

    /** The `11` frame this link's watch last sent (or the library last wrote): a write starts from it. */
    @Volatile var original: String = ""
        internal set

    suspend fun request(): TimeAdjustmentInfo {
        if (!link.watchInfo.info.hasTimeAdjustment) return TimeAdjustmentInfo()
        return link.cache.request("GET_TIME_ADJUSTMENT") { answer.ask { sendToWatch() } }
    }

    fun set(settings: Settings) {
        sendToWatchSet("{action: \"SET_TIME_ADJUSTMENT\", value: ${Gson().toJson(settings)}}")
        // Keep the new value instead of reading it back: a read sent now can reach the watch before the queued write,
        // and the watch then answers with the old value.
        link.cache.put("GET_TIME_ADJUSTMENT", TimeAdjustmentInfo(settings.timeAdjustment, settings.adjustmentTimeMinutes))
    }

    fun onReceived(data: String) {
        original = data
        TimeAdjustmentIOFunctional.decode(data).fold(
            onSuccess = { answer.complete(it) },
            onFailure = {
                Timber.e("Failed to decode time adjustment: ${it.message}")
                answer.fail(it)
            },
        )
    }

    /** The watch refused the read (`FF 81 11`): the read ends with the defaults. */
    fun onRunError() = answer.complete(TimeAdjustmentInfo())

    /** The read of this link's BLE settings ("GET_TIME_ADJUSTMENT"). */
    fun sendToWatch() {
        link.command(GetSetMode.GET, TimeAdjustmentIOFunctional.buildFetchCommand())
    }

    /** The write ("SET_TIME_ADJUSTMENT", the settings in `value`). */
    fun sendToWatchSet(message: String) {
        val settings = JSONObject(message).get("value") as JSONObject
        TimeAdjustmentIOFunctional.encode(original, settings).fold(
            onSuccess = {
                link.command(GetSetMode.SET, it)
                // The next change in this connection starts from what was written.
                original = Utils.fromByteArrayToHexStr(it)
            },
            onFailure = { Timber.e("Failed to encode time adjustment: ${it.message}") },
        )
    }
}
