package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.link.WatchLink
import timber.log.Timber

/**
 * What one link's watch says about itself: its name (`23`), its battery and temperature (`28`) and its last error
 * (`FF`). The reads are command jobs on this link, the answers come from this link's watch only and are kept in this
 * link's cache. Made only by its link's [LinkIO].
 */
@RequiresApi(Build.VERSION_CODES.O)
class LinkWatchStatus internal constructor(private val link: WatchLink) {
    private val name = LinkAnswer<String>()
    private val condition = LinkAnswer<WatchConditionIO.WatchConditionValue>()

    @Volatile private var error: String = ""

    suspend fun requestName(): String = link.cache.request("23") { key -> name.ask { link.request(key) } }

    fun onName(data: String) {
        WatchNameIOFunctional.parseWatchName(data).fold(
            onSuccess = { name.complete(it) },
            onFailure = {
                Timber.e("Failed to parse watch name: ${it.message}")
                name.complete("")
            },
        )
    }

    suspend fun requestCondition(requestString: String = "28"): WatchConditionIO.WatchConditionValue =
        link.cache.request(requestString) { key -> condition.ask { link.request(key) } }

    fun onCondition(data: String) {
        val info = link.watchInfo.info
        WatchConditionIOFunctional.decode(data, info.batteryLevelLowerLimit, info.batteryLevelUpperLimit).fold(
            onSuccess = {
                // An all-zero answer is not a reading: the read keeps waiting for the next one.
                if (it.batteryLevel == 0 && it.temperature == 0) return@fold
                condition.complete(WatchConditionIO.WatchConditionValue(it.batteryLevel, it.temperature))
            },
            onFailure = {
                Timber.e("Failed to decode watch condition: ${it.message}")
                condition.complete(WatchConditionIO.WatchConditionValue(0, 0))
            },
        )
    }

    /** The last error frame of this link's watch ("ERROR" when none came). */
    fun lastError(): String = ErrorIOFunctional.getErrorOrDefault(error)

    fun onError(data: String) {
        error = data
        Timber.d("LinkWatchStatus ${link.key}: error $data")
    }
}
