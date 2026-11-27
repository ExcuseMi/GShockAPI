package org.avmedia.gshockapi.driver

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** One model a data file matches, by its exact name (the advertised name without "CASIO "). */
data class ModelEntry(val name: String, val faces: Int?, val capabilitiesAdd: Set<String>)

/** One field of a record (an alarm slot): [byte] counts from the record start. */
data class RecordField(val name: String, val type: String, val byte: Int, val mask: Int, val max: Int?)

/** One frame that holds records: they start at [offset] and there are at most [count]. */
data class RecordFrame(val code: Int, val offset: Int, val count: Int)

/**
 * One setting of a data file. [byte] is the frame position (the command code is byte 0). [mask] picks the bits; a
 * [type] of "bool", "choice", "uint", "time" or "records".
 */
data class SettingSpec(
    val key: String,
    val type: String,
    val code: Int,
    val byte: Int,
    val mask: Int = 0xFF,
    val invert: Boolean = false,
    val length: Int = 1,
    val littleEndian: Boolean = true,
    val minLength: Int = 0,
    val values: Map<String, Int> = emptyMap(),
    val allowed: List<Int> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
    val clearWhenFalse: Int = 0,
    val requires: String? = null,
    /** A value may be written over a current value this file does not know (else such a frame is never written). */
    val writeOverUnknown: Boolean = false,
    val recordSize: Int = 0,
    val frames: List<RecordFrame> = emptyList(),
    val fields: List<RecordField> = emptyList(),
    val fixed: Map<Int, Int> = emptyMap(),
    val verified: Boolean = true,
)

/** A watch family's data file (see docs/drivers/h5600.json). */
data class DataFile(
    val id: String,
    /** The code driver family this file is for ([DriverFamily.family]). */
    val family: String,
    val codeDriver: String?,
    val models: List<ModelEntry>,
    val capabilities: Set<String>,
    val settings: Map<String, SettingSpec>,
) {
    fun model(advertisedName: String): ModelEntry? = models.firstOrNull { it.name == modelName(advertisedName) }

    companion object {
        /** "CASIO GBX-H5600" is "GBX-H5600"; only exact names match, never a part. */
        fun modelName(advertisedName: String): String = advertisedName.removePrefix("CASIO ").trim()

        const val SCHEMA = 1
        private val TYPES = setOf("bool", "choice", "uint", "time", "records")

        /** [parse] and [validate]; a bad file is a failure with its reason, never an exception. */
        fun load(text: String): Result<DataFile> = runCatching { parse(text).also(::validate) }

        /** Throws [IllegalArgumentException] naming the first thing wrong. */
        fun validate(file: DataFile) {
            require(file.models.isNotEmpty()) { "${file.id}: no models" }
            for (s in file.settings.values) {
                val where = "${file.id} ${s.key}"
                require(s.type in TYPES) { "$where: unknown type ${s.type}" }
                require(s.key in file.capabilities) { "$where: not in capabilities" }
                if (s.type == "records") {
                    require(s.recordSize > 0 && s.frames.isNotEmpty() && s.fields.isNotEmpty()) { "$where: records need record_size, frames and fields" }
                    require(s.fields.all { it.byte in 0 until s.recordSize && it.mask != 0 }) { "$where: a field outside its record" }
                } else {
                    require(s.code in 1..0xFF && s.byte >= 1 && s.mask in 1..0xFF && s.length in 1..4) { "$where: bad code, byte, mask or length" }
                    require(s.type != "choice" || s.values.isNotEmpty()) { "$where: a choice without values" }
                }
                require(s.requires == null || s.requires in file.settings) { "$where: requires unknown ${s.requires}" }
            }
        }

        fun parse(text: String): DataFile {
            val root = JsonParser.parseString(text).asJsonObject
            val schema = root.int("schema")
            require(schema == SCHEMA) { "schema $schema, this library reads schema $SCHEMA" }
            require(root.str("kind") == "watch-driver") { "not a watch-driver file" }
            return DataFile(
                id = requireNotNull(root.str("id")) { "no id" },
                family = requireNotNull(root.str("family")) { "no family" },
                codeDriver = root.str("code_driver"),
                models = root.getAsJsonArray("models").map { m ->
                    val o = m.asJsonObject
                    ModelEntry(o.str("name")!!, o.int("faces"), o.strings("capabilities_add").toSet())
                },
                capabilities = root.strings("capabilities").toSet(),
                settings = root.getAsJsonArray("settings").map { setting(it.asJsonObject) }.associateBy { it.key },
            )
        }

        private fun setting(o: JsonObject) = SettingSpec(
            key = o.str("key")!!,
            type = o.str("type")!!,
            code = o.hex("code") ?: 0,
            byte = o.int("byte") ?: 0,
            mask = o.hex("mask") ?: 0xFF,
            invert = o.bool("invert"),
            length = o.int("length") ?: 1,
            littleEndian = o.str("order") != "big",
            minLength = o.int("min_length") ?: 0,
            values = o.getAsJsonObject("values")?.entrySet()?.associate { it.key to it.value.asInt }.orEmpty(),
            allowed = o.getAsJsonArray("allowed")?.map { it.asInt }.orEmpty(),
            min = o.int("min"),
            max = o.int("max"),
            clearWhenFalse = o.hex("clear_when_false") ?: 0,
            requires = o.str("requires"),
            writeOverUnknown = o.bool("write_over_unknown"),
            recordSize = o.int("record_size") ?: 0,
            frames = o.getAsJsonArray("frames")?.map { f ->
                val fo = f.asJsonObject
                RecordFrame(fo.hex("code")!!, fo.int("offset") ?: 1, fo.int("count") ?: 1)
            }.orEmpty(),
            fields = o.getAsJsonArray("fields")?.map { f ->
                val fo = f.asJsonObject
                RecordField(fo.str("name")!!, fo.str("type")!!, fo.int("byte")!!, fo.hex("mask") ?: 0xFF, fo.int("max"))
            }.orEmpty(),
            fixed = o.getAsJsonObject("fixed")?.entrySet()?.associate { it.key.toInt() to hexValue(it.value) }.orEmpty(),
            verified = o.get("verified")?.asBoolean ?: true,
        )

        private fun JsonObject.str(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
        private fun JsonObject.int(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive }?.asInt
        private fun JsonObject.bool(name: String): Boolean = get(name)?.asBoolean ?: false
        private fun JsonObject.hex(name: String): Int? = get(name)?.let(::hexValue)
        private fun JsonObject.strings(name: String): List<String> = getAsJsonArray(name)?.map { it.asString }.orEmpty()

        /** "0x13" or 19. */
        private fun hexValue(e: JsonElement): Int =
            e.asString.let { s -> if (s.startsWith("0x", ignoreCase = true)) s.substring(2).toInt(16) else s.toInt() }
    }
}
