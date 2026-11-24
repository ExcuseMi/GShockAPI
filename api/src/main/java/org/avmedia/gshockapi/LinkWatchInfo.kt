package org.avmedia.gshockapi

import android.os.Build
import androidx.annotation.RequiresApi

/**
 * What one link's watch is: its name, model, address and the model's features. Each link has its own, so a second
 * watch never reads the first one's model. Made only by its [org.avmedia.gshockapi.link.WatchLink]; the model tables
 * stay in [WatchInfo].
 */
@RequiresApi(Build.VERSION_CODES.O)
class LinkWatchInfo internal constructor() {
    @Volatile private var current: WatchInfo.State? = null

    internal val state: WatchInfo.State get() = current ?: WatchInfo.State().also { current = it }

    val name: String get() = state.name
    val shortName: String get() = state.shortName
    val model: WatchInfo.WatchModel get() = state.model

    /** The features of this watch's model (GENERIC until its name is known). */
    val info: WatchInfo.ModelInfo get() = state.info

    /** Empty until the connection code set it. */
    val address: String get() = current?.address ?: ""

    /** The watch's Bluetooth name is known: model and features follow from it (the address is set again after it). */
    fun setNameAndModel(name: String) {
        current = WatchInfo.buildState(name)
    }

    fun setAddress(address: String) {
        current = state.copy(address = address)
    }

    fun reset() {
        current = null
    }

    internal fun setModelForTest(name: String) = setNameAndModel(name)
}
