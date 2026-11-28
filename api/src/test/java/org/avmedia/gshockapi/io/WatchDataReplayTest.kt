package org.avmedia.gshockapi.io

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import java.io.File
import java.time.ZoneId
import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.driver.DataFile
import org.avmedia.gshockapi.driver.DataSettingCodec
import org.avmedia.gshockapi.driver.Drivers
import org.avmedia.gshockapi.driver.Keys
import org.avmedia.gshockapi.driver.TimeOfDay
import org.junit.Assume.assumeTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Replays watch reads an app kept for analysis (JSON lines: `kind`, `raw` hex, `parts`, the app's `decoded`) through
 * today's decoders and compares the results, per kind. The reads are personal, so none are in the repository: the
 * test runs only when `SQUAREWAVE_WATCH_DATA` names a folder of `.jsonl` files (or one file), and is skipped otherwise.
 * `SQUAREWAVE_WATCH_ZONE` is the watch's time zone for the LifeLog times (default the JVM's).
 *
 * A mismatch is a field whose value differs; it can be a change of the library since the read (a fix) or an app-side
 * decoding. The test prints every differing field once per kind with how often it differed, and fails when any did.
 */
class WatchDataReplayTest {
    private val source = System.getenv("SQUAREWAVE_WATCH_DATA")?.let(::File)
    private val zone: ZoneId = System.getenv("SQUAREWAVE_WATCH_ZONE")?.let(ZoneId::of) ?: ZoneId.systemDefault()

    private fun lines(kind: String): List<JsonObject> {
        assumeTrue("SQUAREWAVE_WATCH_DATA not set: no watch data to replay", source?.exists() == true)
        val files = if (source!!.isDirectory) source.listFiles { f -> f.name.endsWith(".jsonl") }!!.sortedBy { it.name } else listOf(source)
        // The upload can hold a line twice (a part sent again): one per sequence number and moment.
        return files.asSequence().flatMap { it.readLines().asSequence() }.filter { it.isNotBlank() }
            .mapNotNull { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
            .filter { it["kind"]?.asString == kind }
            .distinctBy { it["seq"]?.asString + "|" + it["t"]?.asString }
            .toList()
    }

    private fun bytes(hex: String?): ByteArray =
        hex.orEmpty().split(' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /** The app's way of writing a decoded value, so the two sides compare as JSON. */
    private val gson = GsonBuilder()
        .serializeNulls()
        .serializeSpecialFloatingPointValues()
        .registerTypeAdapter(ByteArray::class.java, JsonSerializer<ByteArray> { b, _, _ -> JsonPrimitive(hex(b)) })
        .registerTypeHierarchyAdapter(java.time.temporal.Temporal::class.java, JsonSerializer<Any> { t, _, _ -> JsonPrimitive(t.toString()) })
        .registerTypeHierarchyAdapter(ZoneId::class.java, JsonSerializer<ZoneId> { z, _, _ -> JsonPrimitive(z.id) })
        .registerTypeAdapter(java.time.Duration::class.java, JsonSerializer<java.time.Duration> { d, _, _ -> JsonPrimitive(d.toString()) })
        .create()

    private fun json(v: Any?): JsonElement = if (v == null) JsonNull.INSTANCE else gson.toJsonTree(v)

    /** Field paths that differ (array indexes as `[]`, so a path counts once however many items differ). */
    private fun diff(path: String, a: JsonElement?, b: JsonElement?, out: MutableSet<String>) {
        val x = a ?: JsonNull.INSTANCE
        val y = b ?: JsonNull.INSTANCE
        when {
            x.isJsonObject && y.isJsonObject -> (x.asJsonObject.keySet() + y.asJsonObject.keySet()).forEach {
                diff("$path.$it", x.asJsonObject[it], y.asJsonObject[it], out)
            }
            x.isJsonArray && y.isJsonArray -> {
                if (x.asJsonArray.size() != y.asJsonArray.size()) out += "$path (length)"
                for (i in 0 until minOf(x.asJsonArray.size(), y.asJsonArray.size())) diff("$path[]", x.asJsonArray[i], y.asJsonArray[i], out)
            }
            x.isJsonPrimitive && y.isJsonPrimitive && x.asJsonPrimitive.isNumber && y.asJsonPrimitive.isNumber ->
                if (x.asDouble != y.asDouble) out += path
            x != y -> out += path
        }
    }

    private class Tally(val kind: String) {
        var reads = 0
        var undecodable = 0
        val fields = sortedMapOf<String, Int>()
        val examples = mutableMapOf<String, String>()
        fun add(paths: Set<String>, example: (String) -> String) {
            paths.forEach { fields[it] = (fields[it] ?: 0) + 1; examples.getOrPut(it) { example(it) } }
        }
        fun finish() {
            println("== $kind: $reads reads, ${fields.size} differing fields, $undecodable not decodable")
            fields.forEach { (f, n) -> println("  $f: $n reads; ${examples[f]}") }
            if (fields.isNotEmpty() || undecodable > 0) fail("$kind: ${fields.keys} differ, $undecodable not decodable (see the output)")
        }
    }

    private fun at(e: JsonElement?, path: String): String {
        var cur: JsonElement? = e
        for (p in path.removePrefix("$").split('.').filter { it.isNotEmpty() }) {
            val name = p.removeSuffix("[]").removeSuffix(" (length)")
            cur = cur?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)
            if (p.endsWith("[]")) cur = cur?.takeIf { it.isJsonArray && it.asJsonArray.size() > 0 }?.asJsonArray?.get(0)
        }
        return cur.toString().take(120)
    }

    /**
     * A LifeLog read as the library got it: the 20 byte header, then every section as its tag, its length (the five
     * byte section head included, low byte first) and its body; sent as 512 byte chunks with a checksum, inverted,
     * in bulk notifications.
     */
    private fun lifeLogNotifications(header: ByteArray, parts: Map<Int, ByteArray>): Pair<List<ByteArray>, LinkedHashMap<Int, Int>> {
        val announced = linkedMapOf(0 to header.size)
        val blocks = mutableListOf(header)
        for ((tag, body) in parts) {
            val len = body.size + 5
            announced[tag] = len
            blocks += byteArrayOf(tag.toByte(), len.toByte(), (len shr 8).toByte(), (len shr 16).toByte(), (len shr 24).toByte()) + body
        }
        val wire = blocks.flatMap { b -> b.toList().chunked(512).flatMap { it + listOf<Byte>(0, 0) } }.map { (it.toInt() xor 0xFF).toByte() }
        return wire.chunked(200).map { byteArrayOf(0x05) + it.toByteArray() } to announced
    }

    private fun parts(o: JsonObject): Map<Int, ByteArray> =
        o.getAsJsonObject("parts")?.entrySet()?.associate { (k, v) -> k.removePrefix("0x").toInt(16) to bytes(v.asString) }.orEmpty()

    @Test
    fun lifeLogReadsDecodeAsTheyWereDecoded() {
        val all = lines("lifelog")
        // The 0x28 answer of a LifeLog read's setup is kept as its own line right after it (after its sleep line, if
        // any), without a request code: a 0x28 setting read has one.
        val conditions = lines("condition").filter { !it.has("code") }.associateBy { it["seq"].asLong }
        val t = Tally("lifelog")
        for (o in all) {
            t.reads++
            val (bulk, announced) = lifeLogNotifications(bytes(o["raw"].asString), parts(o))
            val old = o["decoded"]
            val goal = old.asJsonObject.getAsJsonObject("h5600")?.get("stepGoal")?.takeIf { !it.isJsonNull }?.asInt
            val seq = o["seq"].asLong
            val cond = (conditions[seq + 1] ?: conditions[seq + 2])?.let { bytes(it["raw"].asString) }
            val now = H5600HealthDecoder.decode(bulk, announced, zone, goal, cond)
            if (now == null) { t.undecodable++; continue }
            val out = mutableSetOf<String>()
            diff("$", old, json(now.copy(raw = null)), out)
            t.add(out) { p -> "was ${at(old, p)} now ${at(json(now.copy(raw = null)), p)}" }
        }
        t.finish()
    }

    @Test
    fun sleepReadsDecodeAsTheyWereDecoded() {
        val t = Tally("sleep")
        for (o in lines("sleep")) {
            t.reads++
            val night = bytes(o["raw"].asString)
            val now = json(H5600SleepDecoder.decode(parts(o)[0x53], night.takeIf { it.isNotEmpty() }))
            val out = mutableSetOf<String>()
            diff("$", o["decoded"], now, out)
            t.add(out) { p -> "was ${at(o["decoded"], p)} now ${at(now, p)}" }
        }
        t.finish()
    }

    @Test
    fun conditionReadsDecodeAsTheyWereDecoded() {
        val t = Tally("condition")
        for (o in lines("condition")) {
            t.reads++
            val raw = bytes(o["raw"].asString)
            val old = o["decoded"].asJsonObject
            val now = JsonObject()
            WatchDataTap.conditionFlags(raw).forEach { (k, v) -> now.add(k, json(v)) }
            val info = WatchInfo.infoFor(o["model"]?.asString ?: "")
            WatchConditionIOFunctional.decode(hex(raw).replace(" ", ""), info.batteryLevelLowerLimit, info.batteryLevelUpperLimit).getOrNull()?.let {
                now.addProperty("temperature", it.temperature)
            }
            // The app adds its own battery level, label and percent; only what the library reads is compared.
            // A 0x28 setting read has only the app's values: the library's flags are compared where the line has them.
            now.keySet().filter { !old.has(it) }.forEach { now.remove(it) }
            val compared = JsonObject().apply { now.keySet().forEach { add(it, old[it]) } }
            val out = mutableSetOf<String>()
            diff("$", compared, now, out)
            t.add(out) { p -> "was ${at(compared, p)} now ${at(now, p)}" }
        }
        t.finish()
    }

    /** The app's setting names and the data file's keys (or a codec) that read the same value. */
    private val file = DataFile.parse(File("src/main/resources/" + Drivers.H5600_FILE).readText())
    private val byAppName: Map<String, String> = mapOf(
        "auto_light" to "light.auto_light", "light_duration" to "light.duration", "length_unit" to "units.length",
        "step_reminder" to "steps.reminder", "night_start" to "sleep.night_start", "night_end" to "sleep.night_end",
        "hr_measurement" to "hr.measurement", "bedtime" to "sleep.bedtime", "wake_time" to "sleep.wake_up",
        "training_background" to "fitness.training_background", "max_heart_rate" to "hr.max", "resting_heart_rate" to "hr.resting",
        "vo2max" to "fitness.vo2max", "step_goal" to "steps.goal", "smart_alarm" to "smart_alarm.on",
        "smart_alarm_snooze" to "smart_alarm.snooze", "smart_alarm_time" to "smart_alarm.time", "smart_alarm_window_min" to "smart_alarm.window",
        "custom_slot" to "face.custom_item", "notification_sound_mode" to "notifications.sound",
        "notification_vibration_mode" to "notifications.vibration", "notification_pattern" to "notifications.pattern",
        "notification_language" to "notifications.language", "notification_off_at_night" to "notifications.off_at_night",
    )

    private fun libraryValue(name: String, frame: ByteArray): Pair<Boolean, Any?> {
        byAppName[name]?.let { key ->
            val spec = file.settings[key] ?: return false to null
            return true to when (val v = DataSettingCodec.decode(spec, frame)) {
                is TimeOfDay -> "%02d:%02d".format(v.hour, v.minute)
                else -> v
            }
        }
        return when {
            name == "watch_face" -> true to WatchFaceCodec.parse(frame)?.let { Keys.word(WatchFaceCodec.faceName(it.face)) }
            name.startsWith("activity_") -> {
                val enabled = ActivityListCodec.parse(frame) ?: return true to null
                true to enabled.any { "activity_" + Keys.word(ActivityModels.name(ActivityModels.H5600, it)) == name }
            }
            name.startsWith("profile_") -> {
                val p = ProfileCodec.parse(frame) ?: return true to null
                true to when (name) {
                    "profile_height_cm" -> p.heightCm
                    "profile_weight_kg" -> p.weightKg
                    "profile_sex" -> if (p.female) "female" else "male"
                    "profile_birth_date" -> if (p.birthYear != null && p.birthMonth != null && p.birthDay != null) "%04d-%02d-%02d".format(p.birthYear, p.birthMonth, p.birthDay) else null
                    else -> return false to null
                }
            }
            else -> false to null
        }
    }

    @Test
    fun settingReadsDecodeAsTheyWereDecoded() {
        val t = Tally("setting")
        val unmapped = sortedSetOf<String>()
        for (o in lines("setting")) {
            val old = o["decoded"]?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            t.reads++
            val frame = bytes(o["raw"].asString)
            val out = mutableSetOf<String>()
            val ex = mutableMapOf<String, String>()
            for ((name, was) in old.entrySet()) {
                val (known, v) = libraryValue(name, frame)
                if (!known) { unmapped += name; continue }
                val now = json(v)
                val d = mutableSetOf<String>()
                diff("${o["code"].asString}.$name", was, now, d)
                d.forEach { ex[it] = "was $was now $now (frame ${o["code"].asString}, ${frame.size} bytes)" }
                out += d
            }
            t.add(out) { ex.getValue(it) }
        }
        if (unmapped.isNotEmpty()) println("setting names not compared: $unmapped")
        t.finish()
    }
}
