package org.avmedia.gshockapi.driver

/** The end of a [WatchDriver.get] or [WatchDriver.set]. Never an exception for a missing feature. */
sealed interface DriverResult<out T> {
    data class Ok<T>(val value: T) : DriverResult<T>

    /** This driver or this model does not have [key]. A screen hides the setting; nothing was sent. */
    data class Unsupported(val key: String, val reason: String) : DriverResult<Nothing>

    /** The watch was asked and did not give a usable answer, or refused a write. [message] is plain words. */
    data class Failed(val key: String, val message: String) : DriverResult<Nothing>

    /** The write went out but the read-back did not answer: read again later. */
    data class Unconfirmed(val key: String) : DriverResult<Nothing>
}

fun <T> DriverResult<T>.valueOrNull(): T? = (this as? DriverResult.Ok<T>)?.value

/** What a watch sends without being asked, and the link's own changes. */
sealed interface WatchEvent {
    data object Connected : WatchEvent
    data object Disconnected : WatchEvent

    /** The button that made the watch connect (the 0x10 answer of this connection). */
    data class ButtonPressed(val button: String) : WatchEvent

    data class Steps(val today: Int) : WatchEvent
    data class Battery(val percent: Int) : WatchEvent

    /** A finished multi-packet read (a workout, a LifeLog day), by [kind]. */
    data class Data(val kind: String, val value: Any) : WatchEvent
}
