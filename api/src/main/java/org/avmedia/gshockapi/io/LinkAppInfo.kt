package org.avmedia.gshockapi.io

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.ble.GetSetMode
import org.avmedia.gshockapi.link.WatchLink
import org.avmedia.gshockapi.utils.Utils
import org.avmedia.gshockapi.utils.Utils.hexToBytes

/**
 * One link's app information (`22`): the scratchpad the library keeps on that watch. A watch that answers with an
 * empty scratchpad (after a reset) gets the magic number back, so its lower right button works again; a watch that
 * keeps its own data in `22` is never written. The read is a command job on this link. Made only by its link's [LinkIO].
 */
@RequiresApi(Build.VERSION_CODES.O)
class LinkAppInfo internal constructor(private val link: WatchLink) {
    private val answer = LinkAnswer<String>()

    @Volatile var lastReceivedData: ByteArray = ByteArray(0)
        private set

    @Volatile var wasScratchpadReset = false
        internal set

    suspend fun request(): String = link.cache.request("22") { key -> answer.ask("read $key") { link.request(key) } }

    fun onReceived(data: String) {
        wasScratchpadReset = false
        lastReceivedData = Utils.toCompactString(data).hexToBytes()
        if (link.watchInfo.info.hasScratchpad && AppInfoIOFunctional.needsReinit(lastReceivedData)) initScratchPad()
        answer.complete(data)
    }

    private fun initScratchPad() {
        val buffer = AppInfoIOFunctional.createInitializedBuffer()
        link.command(GetSetMode.SET, buffer)
        lastReceivedData = buffer
        wasScratchpadReset = true
    }

    /** Writes the whole user data area of the scratchpad; throws when the scratchpad is not set up or [data] does not fit. */
    fun setUserData(data: ByteArray) {
        check(AppInfoIOFunctional.isScratchpadInitialized(lastReceivedData)) { "Scratchpad not initialized. Magic number missing." }
        val buffer = AppInfoIOFunctional.writeUserData(lastReceivedData, data, 0).getOrThrow()
        lastReceivedData = buffer
        link.command(GetSetMode.SET, buffer)
    }

    /** The whole user data area of the scratchpad; throws when the scratchpad is not set up. */
    fun getUserData(): ByteArray {
        check(AppInfoIOFunctional.isScratchpadInitialized(lastReceivedData)) { "Scratchpad not initialized. Magic number missing." }
        val size = AppInfoIOFunctional.BUFFER_SIZE - AppInfoIOFunctional.USER_DATA_START_INDEX
        return AppInfoIOFunctional.readUserData(lastReceivedData, 0, size).getOrThrow()
    }
}
