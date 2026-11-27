package org.avmedia.gshockapi.driver

import java.util.ServiceLoader
import kotlinx.coroutines.flow.Flow

/**
 * The protocol of one watch family. Stateless: everything about one watch is in its [WatchConnection], so one driver
 * serves any number of watches. A driver never throws for a missing feature: it answers [DriverResult.Unsupported].
 */
interface WatchDriver {
    val id: String

    /** True when this driver speaks the protocol of the watch that advertises [advertisedName]. */
    fun matches(advertisedName: String): Boolean

    /** The capability ids of the connected model (the family's minus what this model lacks, plus its extras). */
    fun capabilities(conn: WatchConnection): Set<String>

    suspend fun <T : Any> get(conn: WatchConnection, key: Key<T>): DriverResult<T>

    suspend fun <T : Any> set(conn: WatchConnection, key: Key<T>, value: T): DriverResult<Unit>

    /** What the watch sends on its own, and the connection's own changes. */
    fun events(conn: WatchConnection): Flow<WatchEvent>

    /** After every (re)connect, before other jobs: the family's setup steps. Default: nothing. */
    suspend fun onConnected(conn: WatchConnection) = Unit
}

/**
 * How a separately published library adds drivers: it ships a class with a no-argument constructor and names it in
 * `META-INF/services/org.avmedia.gshockapi.driver.WatchDriverProvider`.
 */
interface WatchDriverProvider {
    fun drivers(): List<WatchDriver>
}

/**
 * Finds the driver for an advertised name. Built-in drivers are asked first, in order, then the discovered ones; the
 * first that matches wins, so an outside driver cannot take a watch a built-in one knows.
 */
class DriverRegistry(
    private val builtIn: List<WatchDriver>,
    private val discovered: () -> List<WatchDriver> = ::serviceDrivers,
) {
    private val all: List<WatchDriver> by lazy { builtIn + runCatching(discovered).getOrDefault(emptyList()) }

    fun driverFor(advertisedName: String): WatchDriver? = all.firstOrNull { it.matches(advertisedName) }

    companion object {
        fun serviceDrivers(): List<WatchDriver> =
            ServiceLoader.load(WatchDriverProvider::class.java).flatMap { it.drivers() }
    }
}
