package org.avmedia.gshockapi.casio

import android.os.Build
import androidx.annotation.RequiresApi
import java.util.UUID
import org.avmedia.gshockapi.link.WatchLink
import org.avmedia.gshockapi.protocols.WatchProtocol
import org.json.JSONObject
import timber.log.Timber

/**
 * The frames of one link's watch to their handlers: that link's settings read first, then the protocol's handlers with
 * that link's own objects (its upkeep and setup, time reads, button info, notification gate and transfers) in place of
 * the single-watch ones. Another link's frames never reach it. Made only by its [WatchLink].
 */
@RequiresApi(Build.VERSION_CODES.O)
class LinkDispatcher internal constructor(private val link: WatchLink) {
    /**
     * A notification of this link's watch, as the manager logs it ("0x28 06 1D"), that came on [source] (null when not
     * known). [protocol]: this link's own model's, unless a test names one.
     */
    fun onReceived(data: String, protocol: WatchProtocol = link.watchInfo.info.protocol, source: UUID? = null) {
        // The answer to a settings read that waits goes to that read; everything else is dispatched as usual.
        if (link.rawSettings.offer(data)) return
        val key = protocol.linkKey(link, data, source) ?: return
        Timber.d("LinkDispatcher ${link.key}: key $key, data $data")
        val handler = protocol.linkHandlers(link)[key]
        if (handler == null) {
            Timber.e("No handler registered for key: $key")
            return
        }
        handler(protocol.unwrapPayload(data, key))
    }

    /** An outbound message of this link (`{action: "GET_ALARMS"}`): the link's own object for its action carries it out. */
    fun sendToWatch(message: String) {
        val action = runCatching { JSONObject(message).getString("action") }.getOrNull()
        val sender = action?.let { link.io.senders[it] }
        if (sender == null) {
            Timber.e("LinkDispatcher ${link.key}: no sender for $message")
            return
        }
        sender(message)
    }

    /** This link's watch has an outbound action of that name. */
    fun sends(action: String): Boolean = action in link.io.senders
}
