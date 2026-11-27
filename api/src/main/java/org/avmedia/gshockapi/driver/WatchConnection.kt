package org.avmedia.gshockapi.driver

import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.StateFlow
import org.avmedia.gshockapi.WatchApi
import org.avmedia.gshockapi.io.SettingWriteResult
import org.avmedia.gshockapi.link.LinkState
import org.avmedia.gshockapi.link.WatchLink

/**
 * The engine side a driver sees: one connected watch. Every call here is a job on that watch's one job queue, so two
 * calls never run at the same time on the wire, whoever asks. A driver never reaches the GATT any other way.
 */
interface WatchConnection {
    /** The advertised name ("CASIO GBX-H5600"). */
    val name: String

    val state: StateFlow<LinkState>

    /** The answer to the default request of [code], code first; null when the watch did not answer or refused. */
    suspend fun readFrame(code: Int): ByteArray?

    /** Checked read-modify-write: [change] may touch only the frame bytes in [touched]; written, then read back. */
    suspend fun modify(code: Int, touched: Set<Int>, change: (ByteArray) -> ByteArray?): SettingWriteResult
}

/**
 * The engine's connection on today's per-watch link: reads and writes go through the link's settings reader, which
 * queues them as jobs ([org.avmedia.gshockapi.link.WatchJobQueue]); the reconnect and keep-connected policy stay the link's.
 * [api] is for code drivers that hand a step over to the existing protocol code.
 */
@RequiresApi(Build.VERSION_CODES.O)
class LinkConnection(private val link: WatchLink) : WatchConnection {
    override val name: String get() = link.watchInfo.name
    override val state: StateFlow<LinkState> get() = link.state
    val api: WatchApi get() = link.api

    /** The button of this connection, as its enum name. */
    fun button(): String = link.button.get().name

    override suspend fun readFrame(code: Int): ByteArray? = link.rawSettings.readFrame(code)

    override suspend fun modify(code: Int, touched: Set<Int>, change: (ByteArray) -> ByteArray?): SettingWriteResult =
        link.rawSettings.modifySafe(code, touched, change)
}
