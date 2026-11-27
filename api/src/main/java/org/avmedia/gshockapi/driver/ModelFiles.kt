package org.avmedia.gshockapi.driver

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.ServiceLoader
import timber.log.Timber

/**
 * A code driver for one family, made for a data file that names that [family]. A new family's driver library ships one
 * and names it in `META-INF/services/org.avmedia.gshockapi.driver.DriverFamily`.
 */
interface DriverFamily {
    val family: String

    fun create(file: DataFile): WatchDriver
}

/**
 * The data files known to the engine. Files merge, they do not replace: a family file (`kind: watch-driver`) is the
 * base, and `kind: watch-model` files that `extends` it add or change only what they state. With `models` a layer is
 * for those models only; without, it is for the whole family. A setting or capability with `"supported": false` is
 * removed. The order, later wins with a log line: library family, library models, app family additions, app models.
 * A bad file is refused with its reason, never a crash. A downloaded file is the app's to verify before [register].
 */
class ModelFiles(private val families: Map<String, DriverFamily>) {
    private class Layer(val id: String, val extends: String, val models: Set<String>, val json: JsonObject, val fromLibrary: Boolean)

    private val bases = linkedMapOf<String, JsonObject>()
    private val layers = mutableListOf<Layer>()

    /** Adds a family or model file; the failure says why it was refused. */
    @Synchronized
    fun register(text: String, fromLibrary: Boolean = false): Result<String> = runCatching {
        val o = JsonParser.parseString(text).asJsonObject
        val schema = o.get("schema")?.asInt
        require(schema == DataFile.SCHEMA) { "schema $schema, this library reads schema ${DataFile.SCHEMA}" }
        val id = requireNotNull(o.get("id")?.asString) { "no id" }
        when (o.get("kind")?.asString) {
            "watch-driver" -> {
                val file = DataFile.load(text).getOrThrow()
                require(file.family in families) { "$id: no code driver for family ${file.family}" }
                if (id in bases) Timber.i("Family file $id replaced")
                bases[id] = o
            }
            "watch-model" -> {
                val extends = requireNotNull(o.get("extends")?.asString) { "$id: a model file needs extends" }
                require(extends in bases) { "$id: extends unknown family $extends" }
                val models = o.getAsJsonArray("models")?.map { it.asJsonObject.get("name").asString }.orEmpty().toSet()
                val layer = Layer(id, extends, models, o, fromLibrary)
                requireNotNull(merge(layer.extends, layer.models.firstOrNull(), extra = layer)) // the result must load
                layers.add(layer)
            }
            else -> error("$id: not a watch-driver or watch-model file")
        }
        id
    }.onFailure { Timber.w("Watch data file refused: ${it.message}") }

    /** The merged file for [advertisedName], or null when no family knows it. */
    @Synchronized
    fun fileFor(advertisedName: String): DataFile? {
        val name = DataFile.modelName(advertisedName)
        val family = layers.lastOrNull { name in it.models }?.extends
            ?: bases.entries.firstOrNull { (_, o) -> o.getAsJsonArray("models").any { it.asJsonObject.get("name").asString == name } }?.key
            ?: return null
        return merge(family, name)
    }

    fun driverFor(advertisedName: String): WatchDriver? = fileFor(advertisedName)?.let { families[it.family]?.create(it) }

    private fun merge(family: String, model: String?, extra: Layer? = null): DataFile? {
        val out = bases[family]?.deepCopy() ?: return null
        val mine = (layers + listOfNotNull(extra)).filter { it.extends == family && (it.models.isEmpty() || model in it.models) }
            .sortedBy { (if (it.fromLibrary) 0 else 2) + (if (it.models.isEmpty()) 0 else 1) }
        for (layer in mine) apply(out, layer)
        return DataFile.load(out.toString()).onFailure { Timber.w("Merged file for $model refused: ${it.message}") }.getOrNull()
    }

    private fun apply(out: JsonObject, layer: Layer) {
        val caps = out.getAsJsonArray("capabilities")
        val settings = out.getAsJsonArray("settings")
        val models = out.getAsJsonArray("models")
        layer.json.getAsJsonArray("models")?.forEach { m ->
            val name = m.asJsonObject.get("name").asString
            models.removeAll { it.asJsonObject.get("name").asString == name }
            models.add(m)
        }
        layer.json.getAsJsonArray("capabilities")?.forEach { c ->
            val (id, supported) = if (c.isJsonObject) c.asJsonObject.get("id").asString to (c.asJsonObject.get("supported")?.asBoolean ?: true) else c.asString to true
            caps.removeAll { it.asString == id }
            if (supported) {
                caps.add(id)
            } else {
                settings.removeAll { it.asJsonObject.get("key").asString == id }
                Timber.i("${layer.id}: capability $id removed")
            }
        }
        layer.json.getAsJsonArray("settings")?.forEach { s ->
            val change = s.asJsonObject
            val key = change.get("key").asString
            val old = settings.firstOrNull { it.asJsonObject.get("key").asString == key }?.asJsonObject
            if (change.get("supported")?.asBoolean == false) {
                settings.remove(old)
                caps.removeAll { it.asString == key }
                Timber.i("${layer.id}: setting $key removed")
                return@forEach
            }
            if (old != null) Timber.i("${layer.id}: setting $key changed (${change.keySet() - "key"})")
            val merged = old?.deepCopy() ?: JsonObject()
            change.entrySet().forEach { (k, v) -> merged.add(k, v) }
            settings.remove(old)
            settings.add(merged)
            if (caps.none { it.asString == key }) caps.add(key)
        }
    }

    /**
     * Reads every `META-INF/gshockapi/watches.index` on [loader] (one resource path per line, such as
     * `META-INF/gshockapi/watches/b2100.json`): a class loader cannot list a folder, so each jar names its files.
     * Family files should come first in an index.
     */
    fun registerClasspath(loader: ClassLoader, fromLibrary: Boolean = false): List<Result<String>> =
        loader.getResources(INDEX).toList().flatMap { url ->
            url.openStream().bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        }.map { path ->
            val text = loader.getResourceAsStream(path)?.bufferedReader()?.readText()
                ?: return@map Result.failure<String>(IllegalArgumentException("$path: not found"))
            register(text, fromLibrary)
        }

    companion object {
        const val INDEX = "META-INF/gshockapi/watches.index"

        /** The families found by ServiceLoader, plus [builtIn] (a built-in wins for the same family). */
        fun families(builtIn: List<DriverFamily>): Map<String, DriverFamily> =
            (runCatching { ServiceLoader.load(DriverFamily::class.java).toList() }.getOrDefault(emptyList()) + builtIn).associateBy { it.family }

        private fun JsonArray.removeAll(p: (JsonElement) -> Boolean) {
            filter(p).forEach { remove(it) }
        }
    }
}

/** The H5600 family's code driver, for any file that names family "h5600". */
@androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
class H5600Family : DriverFamily {
    override val family: String = "h5600"

    override fun create(file: DataFile): WatchDriver = H5600Driver(file)
}
