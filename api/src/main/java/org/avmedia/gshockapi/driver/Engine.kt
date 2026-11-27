package org.avmedia.gshockapi.driver

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The engine's face toward the app: one bound watch, its driver picked from the advertised name. Calls are queued by
 * the connection (one job at a time per watch); the reconnect and keep-connected policy are the connection's.
 */
class BoundWatch(val connection: WatchConnection, val driver: WatchDriver?) {
    val capabilities: Set<String> get() = driver?.capabilities(connection).orEmpty()

    fun has(key: Key<*>): Boolean = key.id in capabilities

    suspend fun <T : Any> get(key: Key<T>): DriverResult<T> =
        driver?.get(connection, key) ?: DriverResult.Unsupported(key.id, "no driver for ${connection.name}")

    suspend fun <T : Any> set(key: Key<T>, value: T): DriverResult<Unit> =
        driver?.set(connection, key, value) ?: DriverResult.Unsupported(key.id, "no driver for ${connection.name}")

    /** The pure codec of the frame setting [key] of this watch's driver, null when it has none. */
    fun frameSetting(key: Key<*>): FrameSetting? = (driver as? DataDriver)?.takeIf { has(key) }?.frameSetting(key.id)

    val events: Flow<WatchEvent> get() = driver?.events(connection) ?: emptyFlow()
}

class DriverEngine(private val registry: DriverRegistry) {
    fun bind(connection: WatchConnection): BoundWatch = BoundWatch(connection, registry.driverFor(connection.name))
}
