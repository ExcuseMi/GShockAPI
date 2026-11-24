package org.avmedia.gshockapi.casio

import kotlinx.coroutines.Deferred

/** The single-watch notification gate: the [LinkNotificationGate] of the one link. New code uses `link.notifications.gate`. */
object NotificationGate {
    private val gate: LinkNotificationGate get() = WatchLink.notifications.gate

    val mtu: Int get() = gate.mtu

    fun reset() = gate.reset()

    fun onManager(data: String) = gate.onManager(data)

    fun onCondition(data: String) = gate.onCondition(data)

    fun allowed(): Boolean = gate.allowed()

    fun chunkSize(): Int = gate.chunkSize()
}

/** The single-watch notification texts: the [LinkNotificationSender] of the one link. New code uses `link.notifications.sender`. */
object NotificationSender {
    /** How one text ended. Only [SENT] means every chunk got its write result. */
    enum class Outcome { SENT, HELD_TOO_LONG, WRITE_FAILED, EMPTY }

    private val sender: LinkNotificationSender get() = WatchLink.notifications.sender

    var spacingMs: Long
        get() = sender.spacingMs
        set(value) { sender.spacingMs = value }

    var busy: () -> Boolean
        get() = sender.busy
        set(value) { sender.busy = value }

    var writer: suspend (ByteArray) -> Boolean
        get() = sender.writer
        set(value) { sender.writer = value }

    var holdLimitMs: Long
        get() = sender.holdLimitMs
        set(value) { sender.holdLimitMs = value }

    var holdPollMs: Long
        get() = sender.holdPollMs
        set(value) { sender.holdPollMs = value }

    fun chunks(buffer: ByteArray, size: Int): List<ByteArray> = sender.chunks(buffer, size)

    fun send(buffer: ByteArray): Deferred<Outcome> = sender.send(buffer)
}
