package org.avmedia.gshockapi.protocols

import android.os.Build
import androidx.annotation.RequiresApi
import org.avmedia.gshockapi.io.GwBx5600TimeIO
import org.avmedia.gshockapi.link.WatchLink

@RequiresApi(Build.VERSION_CODES.O)
object MipProtocol : StandardProtocol() {
    /** The GW-B5600 family's time has only its single-watch adapter ([GwBx5600TimeIO]) so far. */
    override suspend fun setTime(
        link: WatchLink,
        timeMs: Long?,
        offset: Long?,
    ) {
        singleWatchOnly(link, "The GW-B5600 time sync")
        GwBx5600TimeIO.set(timeMs)
    }
}
