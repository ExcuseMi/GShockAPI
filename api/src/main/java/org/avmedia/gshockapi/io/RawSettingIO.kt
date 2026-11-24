package org.avmedia.gshockapi.io


/** The single-watch settings reader / writer: the [LinkRawSettings] of the one link. New code uses `link.rawSettings`. */
object RawSettingIO {
    private val settings: LinkRawSettings get() = org.avmedia.gshockapi.casio.WatchLink.rawSettings

    const val READ_TIMEOUT_MS = LinkRawSettings.READ_TIMEOUT_MS

    val linkWire: SettingLink get() = settings.linkWire

    var link: SettingLink
        get() = settings.link
        set(value) { settings.link = value }

    var tryTimeoutMs: Long
        get() = settings.tryTimeoutMs
        set(value) { settings.tryTimeoutMs = value }

    var retryBaseDelayMs: Long
        get() = settings.retryBaseDelayMs
        set(value) { settings.retryBaseDelayMs = value }

    var settleMs: Long
        get() = settings.settleMs
        set(value) { settings.settleMs = value }

    fun requestFor(code: Int): ByteArray = LinkRawSettings.requestFor(code)

    fun failureStatus(ints: IntArray, code: Int): Int = LinkRawSettings.failureStatus(ints, code)

    suspend fun request(code: Int): IntArray = settings.request(code)

    suspend fun read(request: ByteArray, timeoutMs: Long = READ_TIMEOUT_MS, accept: (ByteArray) -> Boolean = { true }): LinkRawSettings.Reply =
        settings.read(request, timeoutMs, accept)

    suspend fun readFrame(code: Int): ByteArray? = settings.readFrame(code)

    fun offer(data: String): Boolean = settings.offer(data)

    fun onReceived(data: String) = settings.onReceived(data)

    suspend fun modifySafe(code: Int, touched: Set<Int>, change: (ByteArray) -> ByteArray?): SettingWriteResult =
        settings.modifySafe(code, touched, change)

    fun safeWriter(read: suspend (Int) -> ByteArray? = ::readFrame): SafeSettingWrite = settings.safeWriter(read)

    suspend fun <T> withLock(code: Int, block: suspend () -> T): T = settings.withLock(code, block)

    fun write(packet: IntArray) = settings.write(packet)
}
