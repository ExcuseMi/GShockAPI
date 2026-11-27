package org.avmedia.gshockapi.driver

import android.os.Build
import androidx.annotation.RequiresApi
import java.util.concurrent.ConcurrentHashMap

/**
 * The library's drivers: the built-in family files (the H5600 file in the library's resources) with their code
 * drivers, the files found on the classpath ([ModelFiles.INDEX]) or registered by the app ([files]), then the drivers
 * other libraries ship ([DriverRegistry.serviceDrivers]). A built-in one wins. Asking is cheap: a driver is made once per
 * model name and made again only after a file is registered.
 */
@RequiresApi(Build.VERSION_CODES.O)
object Drivers {
    /** The library's own family file, as a class-path resource next to this class. */
    const val H5600_FILE = "org/avmedia/gshockapi/driver/h5600.json"

    /** The library's H5600 family file, parsed and checked. */
    val h5600File: DataFile by lazy { DataFile.load(resource(H5600_FILE)).getOrThrow() }

    val files: ModelFiles by lazy {
        ModelFiles(ModelFiles.families(listOf(H5600Family()))).also { f ->
            f.register(resource(H5600_FILE), fromLibrary = true).getOrThrow()
            runCatching { Drivers::class.java.classLoader?.let { f.registerClasspath(it) } }
        }
    }

    private val outside: DriverRegistry by lazy { DriverRegistry(emptyList()) }
    private val made = ConcurrentHashMap<String, WatchDriver>()
    private val none: WatchDriver = DataDriver(h5600File)

    /** Adds an app's family or model file (see [ModelFiles.register]); the drivers made so far are made again. */
    fun register(text: String): Result<String> = files.register(text).also { if (it.isSuccess) made.clear() }

    /** The driver of the watch that advertises [advertisedName], or null when no driver knows it. */
    fun driverFor(advertisedName: String): WatchDriver? {
        val name = DataFile.modelName(advertisedName)
        if (name.isEmpty()) return null
        val d = made.getOrPut(name) { files.driverFor(name) ?: outside.driverFor(advertisedName) ?: none }
        return d.takeIf { it !== none }
    }

    /** The capability ids of [advertisedName] (empty without a driver). */
    fun capabilities(advertisedName: String): Set<String> =
        (driverFor(advertisedName) as? DataDriver)?.let { d -> d.file.model(advertisedName)?.let { d.file.capabilities + it.capabilitiesAdd } }.orEmpty()

    /** The watch faces of [advertisedName] (the model's count), null without a driver that says it. */
    fun faceCount(advertisedName: String): Int? = (driverFor(advertisedName) as? DataDriver)?.file?.model(advertisedName)?.faces

    fun bind(connection: WatchConnection): BoundWatch = BoundWatch(connection, driverFor(connection.name))

    private fun resource(path: String): String =
        requireNotNull(Drivers::class.java.classLoader?.getResourceAsStream(path)) { "$path is not in the library" }.bufferedReader().use { it.readText() }
}
