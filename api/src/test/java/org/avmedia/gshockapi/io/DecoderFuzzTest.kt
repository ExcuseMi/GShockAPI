package org.avmedia.gshockapi.io

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random
import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.driver.DataFile
import org.avmedia.gshockapi.driver.DataSettingCodec
import org.avmedia.gshockapi.driver.Drivers
import org.avmedia.gshockapi.utils.Utils
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every decoder and codec gets random, truncated, oversized and error (`FF 81`) frames. None may throw out of its
 * public function or run on: each gives a failure value (null, empty, a failed Result) instead. Seeded, so a failure
 * is the same on every run.
 */
class DecoderFuzzTest {
    private val rnd = Random(0x5EED)
    private val failures = mutableListOf<String>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

    private fun hex(b: ByteArray) = Utils.fromByteArrayToHexStr(b)

    private fun bytes(n: Int, fill: (Int) -> Int = { rnd.nextInt(256) }) = ByteArray(n) { fill(it).toByte() }

    /** Frames for one decoder whose first byte is often [code]: empty, short, long, random, all FF, all 00 and `FF 81`. */
    private fun frames(code: Int? = null, typical: Int = 20, count: Int = 150): List<ByteArray> {
        val out = mutableListOf(ByteArray(0), byteArrayOf(0xFF.toByte()), byteArrayOf(0xFF.toByte(), 0x81.toByte()))
        out += byteArrayOf(0xFF.toByte(), 0x81.toByte(), (code ?: 0).toByte())
        out += bytes(typical) { 0xFF }
        out += bytes(typical) { 0 }
        out += bytes(typical * 8 + 300)
        repeat(count) {
            val n = when (rnd.nextInt(4)) {
                0 -> rnd.nextInt(0, typical + 1)
                1 -> typical
                2 -> rnd.nextInt(typical, typical * 4 + 8)
                else -> rnd.nextInt(0, 1100)
            }
            val b = bytes(n)
            if (code != null && n > 0 && rnd.nextInt(4) != 0) b[0] = code.toByte()
            if (n > 1 && rnd.nextInt(6) == 0) { b[0] = 0xFF.toByte(); b[1] = 0x81.toByte() }
            out += b
        }
        return out
    }

    /** Runs [block]; a throw or a run over [limitMs] is a failure named [name] with its input. */
    private fun check(name: String, input: () -> String, limitMs: Long = 3_000, block: () -> Any?) {
        val f = pool.submit<Any?> { block() }
        try {
            f.get(limitMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            f.cancel(true)
            failures += "$name ran over ${limitMs}ms on ${input()}"
        } catch (e: java.util.concurrent.ExecutionException) {
            val c = e.cause ?: e
            failures += "$name threw ${c.javaClass.simpleName}: ${c.message?.take(80)} on ${input().take(120)}"
        }
    }

    private fun each(name: String, inputs: List<ByteArray>, block: (ByteArray) -> Any?) {
        for (b in inputs) check(name, { hex(b) }) { block(b) }
    }

    private fun report() {
        pool.shutdownNow()
        // One line per decoder is enough to find it.
        val firstPerName = failures.groupBy { it.substringBefore(' ') }.map { (k, v) -> "${v.first()} (${v.size} inputs)".also { k } }
        if (firstPerName.isNotEmpty()) fail("${firstPerName.size} decoders failed:\n" + firstPerName.joinToString("\n"))
    }

    @Test
    fun settingCodecsNeverThrow() {
        val one: List<Pair<String, (ByteArray) -> Any?>> = listOf(
            "HeartRateSettingCodec.parse" to HeartRateSettingCodec::parse,
            "HeartRateSettingCodec.withMode" to { b -> HeartRateSettingCodec.withMode(b, rnd.nextInt(-2, 300)) },
            "PhysicalSettingsCodec.parse" to PhysicalSettingsCodec::parse,
            "PhysicalSettingsCodec.parseBody" to PhysicalSettingsCodec::parseBody,
            "PhysicalSettingsCodec.withBedtime" to { b -> PhysicalSettingsCodec.withBedtime(b, rnd.nextInt(-1, 30), rnd.nextInt(-1, 70)) },
            "PhysicalSettingsCodec.withMaxHeartRate" to { b -> PhysicalSettingsCodec.withMaxHeartRate(b, rnd.nextInt(0, 300)) },
            "PhysicalSettingsCodec.withRestingHeartRate" to { b -> PhysicalSettingsCodec.withRestingHeartRate(b, rnd.nextInt(0, 300)) },
            "PhysicalSettingsCodec.withVo2max" to { b -> PhysicalSettingsCodec.withVo2max(b, rnd.nextInt(0, 300)) },
            "PhysicalSettingsCodec.withTrainingBackground" to { b -> PhysicalSettingsCodec.withTrainingBackground(b, rnd.nextInt(-1, 10)) },
            "BasicHealthFieldsCodec.parse" to BasicHealthFieldsCodec::parse,
            "BasicHealthFieldsCodec.withNightWindow" to { b -> BasicHealthFieldsCodec.withNightWindow(b, rnd.nextInt(-1, 30) to rnd.nextInt(-1, 70), rnd.nextInt(-1, 30) to rnd.nextInt(-1, 70)) },
            "BasicHealthFieldsCodec.withStepReminder" to { b -> BasicHealthFieldsCodec.withStepReminder(b, rnd.nextBoolean()) },
            "StepGoalCodec.parse" to StepGoalCodec::parse,
            "StepGoalCodec.withSteps" to { b -> StepGoalCodec.withSteps(b, rnd.nextInt(-5, 200_000)) },
            "ProfileCodec.parse" to ProfileCodec::parse,
            "ProfileCodec.withHeightAndWeight" to { b -> ProfileCodec.withHeightAndWeight(b, rnd.nextInt(-5, 400), rnd.nextInt(-5, 800)) },
            "ProfileCodec.withBirthDate" to { b -> ProfileCodec.withBirthDate(b, rnd.nextInt(1800, 2200), rnd.nextInt(-1, 14), rnd.nextInt(-1, 33)) },
            "ProfileCodec.withFemale" to { b -> ProfileCodec.withFemale(b, rnd.nextBoolean()) },
            "SmartAlarmCodec.parse" to SmartAlarmCodec::parse,
            "SmartAlarmCodec.withTime" to { b -> SmartAlarmCodec.withTime(b, rnd.nextInt(-1, 30), rnd.nextInt(-1, 70)) },
            "SmartAlarmCodec.withOn" to { b -> SmartAlarmCodec.withOn(b, rnd.nextBoolean()) },
            "SmartAlarmCodec.withSnooze" to { b -> SmartAlarmCodec.withSnooze(b, rnd.nextBoolean()) },
            "SmartAlarmCodec.withWindow" to { b -> SmartAlarmCodec.withWindow(b, rnd.nextInt(-5, 100)) },
            "LightSettingsCodec.autoLight" to LightSettingsCodec::autoLight,
            "LightSettingsCodec.duration" to LightSettingsCodec::duration,
            "LightSettingsCodec.withAutoLight" to { b -> LightSettingsCodec.withAutoLight(b, rnd.nextBoolean()) },
            "LightSettingsCodec.withDuration" to { b -> LightSettingsCodec.withDuration(b, listOf("1.5s", "3s", "x", "").random(rnd)) },
            "WatchFaceCodec.parse" to WatchFaceCodec::parse,
            "NotificationSoundCodec.parse" to NotificationSoundCodec::parse,
            "NotificationSoundCodec.withSoundEvent" to { b -> NotificationSoundCodec.withSoundEvent(b, rnd.nextInt(-2, 40), rnd.nextBoolean()) },
            "NotificationSoundCodec.withPattern" to { b -> NotificationSoundCodec.withPattern(b, rnd.nextInt(-2, 300)) },
            "NotificationSoundCodec.withSoundMode" to { b -> NotificationSoundCodec.withSoundMode(b, rnd.nextInt(-2, 300)) },
            "NotificationManagerCodec.parse" to NotificationManagerCodec::parse,
            "NotificationManagerCodec.withLanguage" to { b -> NotificationManagerCodec.withLanguage(b, rnd.nextInt(-2, 300)) },
            "NotificationManagerCodec.withOffAtNight" to { b -> NotificationManagerCodec.withOffAtNight(b, rnd.nextInt(-2, 300)) },
            "NotificationManagerCodec.expectedBack" to { b -> NotificationManagerCodec.expectedBack(b, bytes(rnd.nextInt(0, 30))) },
            "ActivityListCodec.parse" to ActivityListCodec::parse,
            "ActivityListCodec.layoutOk" to ActivityListCodec::layoutOk,
            "ActivityListCodec.encode" to { b -> ActivityListCodec.encode(b, ActivityListCodec.ACTIVITIES.shuffled(rnd).take(rnd.nextInt(0, 12))) },
            "ActivityListCodec.sameSlots" to { b -> ActivityListCodec.sameSlots(b, bytes(rnd.nextInt(0, 30))) },
            "ActivityFaceCodec.parse" to { b -> ActivityFaceCodec.parse(b, rnd.nextInt(-1, 20)) },
            "ActivityItemCodec.parse" to { b -> ActivityItemCodec.parse(b, rnd.nextInt(-1, 20), rnd.nextInt(-1, 260)) },
            "RunMeasurementCodec.parse" to RunMeasurementCodec::parse,
            "RunMeasurementCodec.withAutoPause" to { b -> RunMeasurementCodec.withAutoPause(b, rnd.nextBoolean()) },
            "HeartRateFlagsCodec.parse" to HeartRateFlagsCodec::parse,
            "HeartRateFlagsCodec.withZoneAlert" to { b -> HeartRateFlagsCodec.withZoneAlert(b, rnd.nextBoolean()) },
            "TrainingAlertCodec.parse" to TrainingAlertCodec::parse,
            "TrainingAlertCodec.withDistanceRepeat" to { b -> TrainingAlertCodec.withDistanceRepeat(b, rnd.nextInt(-2, 5)) },
            "TideSettingCodec.parse" to TideSettingCodec::parse,
            "TideUserPointCodec.parse" to { b -> TideUserPointCodec.parse(b, rnd.nextInt(-2, 6)) },
            "H5600CurrentValues.groups" to H5600CurrentValues::groups,
            "H5600CurrentValues.cardio" to { b -> H5600CurrentValues.cardio(b, LocalDate.of(2026, 1, 1)) },
        )
        for ((name, f) in one) each(name, frames(code = null, typical = 24), f)

        // An activity item read is decoded only after its own parse: its inner bytes come from that item.
        each("ActivityItemCodec.inner", frames(typical = 30)) { b ->
            val a = rnd.nextInt(0, 16)
            val c = rnd.nextInt(0, 8)
            ActivityItemCodec.parse(b, a, c)?.let { ActivityItemCodec.inner(b, it) }
        }
        report()
    }

    @Test
    fun dataFileSettingsNeverThrow() {
        val file = DataFile.parse(File("src/main/resources/" + Drivers.H5600_FILE).readText())
        for ((key, spec) in file.settings) {
            val inputs = frames(code = spec.code, typical = maxOf(spec.minLength, spec.byte + 4))
            each("DataSettingCodec.decode[$key]", inputs) { b -> DataSettingCodec.decode(spec, b) }
            each("DataSettingCodec.encode[$key]", inputs) { b ->
                val v: Any = listOf(true, false, rnd.nextInt(-5, 70000), "x", spec.values.keys.firstOrNull() ?: "y").random(rnd)
                DataSettingCodec.encode(spec, b, v)
            }
            if (spec.frames.isNotEmpty()) {
                for (rf in spec.frames) {
                    val rin = frames(code = rf.code, typical = rf.offset + rf.count * spec.recordSize)
                    each("DataSettingCodec.decodeRecords[$key]", rin) { b -> DataSettingCodec.decodeRecords(spec, b) }
                    each("DataSettingCodec.encodeRecords[$key]", rin) { b ->
                        val recs = DataSettingCodec.decodeRecords(spec, b).orEmpty()
                        DataSettingCodec.encodeRecords(spec, b, recs + recs)
                    }
                }
            }
        }
        report()
    }

    @Test
    fun healthDecodersNeverThrow() {
        val zone = ZoneId.of("UTC")
        each("H5600SleepDecoder.decode", frames(typical = 120, count = 300)) { b ->
            H5600SleepDecoder.decode(b.takeIf { rnd.nextBoolean() }, bytes(rnd.nextInt(0, 400)))
        }
        each("H5600BodyDecoder.decode", frames(typical = 80, count = 300)) { b ->
            H5600BodyDecoder.decode(b, bytes(rnd.nextInt(0, 200)), b.takeIf { rnd.nextBoolean() }, bytes(rnd.nextInt(0, 200)))
        }
        each("H5600HealthDecoder.decodeDailySummary", frames(typical = 30)) { b -> H5600HealthDecoder.decodeDailySummary(b) }
        each("StepCounterIOFunctional.parse", frames(code = 0x00, typical = 40)) { b -> StepCounterIOFunctional.parse(b) }

        // LifeLog reassembly: random notifications, random announced section lengths (some negative, huge, or zero).
        val tags = listOf(0, 0x65, 0x50, 0x51, 0x52, 0x53, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x5B, 0x5C, 0x63, 0x72, 0x99)
        repeat(400) { i ->
            // A third are real bulk notifications of a stream that starts with the LifeLog header and has any date in it.
            // Half of those have a real date and real sections (tag, length, body), so every section decoder is reached.
            val sections = LinkedHashMap<Int, Int>()
            val bulk = if (i % 3 == 0) {
                var stream = byteArrayOf(0x00, 0x0F, 0x00, 0x00, 0x00) + bytes(rnd.nextInt(0, 700))
                if (i % 2 == 0) {
                    val head = bytes(20).also { byteArrayOf(0x00, 0x0F, 0x00, 0x00, 0x00, 26, rnd.nextInt(1, 13).toByte(), rnd.nextInt(1, 29).toByte(), rnd.nextInt(0, 24).toByte(), rnd.nextInt(0, 60).toByte()).copyInto(it) }
                    sections[0] = 20
                    stream = head + tags.drop(1).shuffled(rnd).take(rnd.nextInt(1, tags.size)).fold(ByteArray(0)) { acc, tag ->
                        val body = bytes(rnd.nextInt(0, 400)) { if (rnd.nextInt(3) == 0) 0xFE else rnd.nextInt(256) }
                        val len = body.size + 5
                        sections[tag] = len
                        acc + byteArrayOf(tag.toByte(), len.toByte(), (len shr 8).toByte(), 0, 0) + body
                    }
                }
                stream.toList().chunked(512).flatMap { it + listOf<Byte>(0, 0) }.map { (it.toInt() xor 0xFF).toByte() }
                    .chunked(rnd.nextInt(1, 40)).map { byteArrayOf(0x05) + it.toByteArray() }
            } else {
                List(rnd.nextInt(0, 12)) { bytes(rnd.nextInt(0, 260)) }
            }
            val announced = if (sections.isNotEmpty() && rnd.nextInt(4) != 0) sections else tags.shuffled(rnd).take(rnd.nextInt(0, tags.size)).associateWith {
                when (rnd.nextInt(5)) { 0 -> -rnd.nextInt(1, 100); 1 -> 0; 2 -> rnd.nextInt(100_000, Int.MAX_VALUE); else -> rnd.nextInt(1, 600) }
            }
            val cond = bytes(rnd.nextInt(0, 14)).takeIf { rnd.nextBoolean() }
            val input = { "bulk=${bulk.map { it.size }} announced=$announced" }
            check("H5600HealthDecoder.decode", input) { H5600HealthDecoder.decode(bulk, announced, zone, rnd.nextInt(-5, 99999), cond) }
            check("H5600HealthDecoder.lifeLogStream", input) { H5600HealthDecoder.lifeLogStream(bulk, announced) }
            check("H5600HealthDecoder.locateFrames", input) { H5600HealthDecoder.locateFrames(bulk.fold(ByteArray(0)) { a, b -> a + b }, announced) }
        }

        // The LifeLog exchange never throws whatever comes in, on any channel.
        repeat(200) {
            val s = H5600LifeLogSync()
            s.start()
            val msgs = List(rnd.nextInt(1, 40)) { H5600Channel.entries.random(rnd) to bytes(rnd.nextInt(0, 30)) }
            check("H5600LifeLogSync.onMessage", { msgs.map { (c, b) -> "$c:${hex(b)}" }.toString().take(200) }) {
                msgs.forEach { (c, b) -> s.onMessage(c, b) }
            }
            msgs.forEach { (_, b) -> check("H5600LifeLogSync.channelOf", { hex(b) }) { H5600LifeLogSync.channelOf(b) } }
        }
        report()
    }

    @Test
    fun workoutDecodersNeverThrow() {
        // Every sport code, so every layout of laps and samples is fed.
        for (sport in 0..255) {
            val payloads = frames(typical = 64, count = 12)
            each("WorkoutRecords.laps", payloads) { b -> WorkoutRecords.laps(sport, b) }
            each("WorkoutRecords.samples", payloads) { b -> WorkoutRecords.samples(sport, b) }
            each("WorkoutSummaryDecoder.decode", frames(typical = 256, count = 6)) { b -> WorkoutSummaryDecoder.decode(b, sport, rnd.nextInt(-1, 6)) }
        }
        each("WorkoutRecords.blockPayload", frames(typical = 512)) { b -> WorkoutRecords.blockPayload(b) }
        each("WorkoutRecords.nextAddress", frames(typical = 512)) { b -> WorkoutRecords.nextAddress(b) }
        each("WorkoutRecords.join", frames(typical = 512, count = 60)) { b -> WorkoutRecords.join(listOf(b, bytes(rnd.nextInt(0, 600)), b)) }
        each("WorkoutGroup.usedSlots", frames(typical = 256)) { b -> WorkoutGroup.usedSlots(b) }
        each("WorkoutGroup.transferredSlots", frames(typical = 256)) { b -> WorkoutGroup.transferredSlots(b) }
        each("WorkoutGroup.pendingSlots", frames(typical = 256)) { b -> WorkoutGroup.pendingSlots(b) }
        each("WorkoutReadTrigger.flagOf", frames(code = 0x28, typical = 12)) { b -> WorkoutReadTrigger.flagOf(b) }
        each("WorkoutChunkAssembler.decode", frames(typical = 600, count = 80)) { b ->
            WorkoutChunkAssembler.decode(b, rnd.nextInt(0, 2000), listOf(256, 512, 1, 7).random(rnd))
        }
        // A header of any content, then a session from random data and lap blocks.
        each("WorkoutSessionBuilder.build", frames(typical = 256, count = 400).map { if (it.size < 256) it + bytes(256 - it.size) else it }) { b ->
            val sport = listOf(rnd.nextInt(256), 1, 2, 6, 14).random(rnd)
            b[0] = sport.toByte()
            val h = WorkoutHeader.decode(b) ?: return@each null
            WorkoutSessionBuilder.build(rnd.nextInt(0, 10), h, List(rnd.nextInt(0, 4)) { bytes(rnd.nextInt(0, 1100)) }, List(rnd.nextInt(0, 3)) { bytes(rnd.nextInt(0, 600)) })
        }
        report()
    }

    @Test
    fun tideCodecsNeverThrow() {
        each("TideFraming.parseStart", frames(typical = 12)) { b -> TideFraming.parseStart(b) }
        each("TideFraming.parseChunkSize", frames(typical = 12)) { b -> TideFraming.parseChunkSize(b) }
        each("TideStationParser.parse", frames(typical = 200, count = 200)) { b -> TideStationParser.parse(String(b, Charsets.ISO_8859_1)) }
        repeat(150) {
            val text = { s: String -> s }
            val t = listOf("", "12:00", "25:61", "aa", "-1:-1", "12:3").random(rnd)
            val la = listOf("", "91", "-90", "x", "45.5", "1e400").random(rnd)
            val lo = listOf("", "181", "-180", "x", "4.3", "NaN").random(rnd)
            val off = listOf("", "14", "-12.75", "x", "Infinity").random(rnd)
            check("TidePointText.parse", { text("$t $la $lo $off") }) { TidePointText.parse(t, la, lo, off, rnd.nextBoolean()) }
        }
        val names = TideConstituents.ORDER
        repeat(200) {
            val consts = names.shuffled(rnd).take(rnd.nextInt(0, names.size + 1)).associateWith {
                TideConstant(listOf(rnd.nextDouble(-50.0, 500.0), Double.NaN, Double.POSITIVE_INFINITY, 0.0, 1e9).random(rnd), rnd.nextDouble(-720.0, 720.0))
            }
            val s = TideStation(String(bytes(rnd.nextInt(0, 40)), Charsets.ISO_8859_1), rnd.nextDouble(-200.0, 200.0), rnd.nextDouble(-400.0, 400.0), rnd.nextInt(-200, 200), consts, listOf(null, Double.NaN, -5.0, 1e9, 100.0).random(rnd))
            check("TideRecords.build4", { s.toString().take(200) }) { TideRecords.build4(s) }
            check("TideRecords.build60", { s.toString().take(200) }) { TideRecords.build60(s) }
            check("TideRecords.graphPattern", { s.toString().take(200) }) { TideRecords.graphPattern(s) }
        }
        report()
    }

    @Test
    fun hexFrameDecodersNeverThrow() {
        // The older decoders take the frame as hex text; they must give a failed Result or a fallback, never throw.
        val info = WatchInfo.infoFor("CASIO GBX-H5600")
        val generic = WatchInfo.infoFor("CASIO GW-B5600")
        val decoders: List<Pair<String, (ByteArray) -> Any?>> = listOf(
            "SettingsIOFunctional.decode" to { b -> SettingsIOFunctional.decode(hex(b), info); SettingsIOFunctional.decode(hex(b), generic) },
            "TimerIOFunctional.decode" to { b -> TimerIOFunctional.decode(hex(b)) },
            "TimeAdjustmentIOFunctional.decode" to { b -> TimeAdjustmentIOFunctional.decode(hex(b)) },
            "AlarmsIOFunctional.parseReceivedAlarms" to { b -> AlarmsIOFunctional.parseReceivedAlarms(hex(b), info) },
            "WatchConditionIOFunctional.decode" to { b -> WatchConditionIOFunctional.decode(hex(b), 9, 19) },
            "ButtonPressedIOFunctional.parseButtonPress" to { b -> ButtonPressedIOFunctional.parseButtonPress(hex(b)) },
            "ButtonPressedIOFunctional.parseConnectionKind" to { b -> ButtonPressedIOFunctional.parseConnectionKind(hex(b)) },
            "WatchNameIOFunctional.parseWatchName" to { b -> WatchNameIOFunctional.parseWatchName(hex(b)) },
            "AppInfoIOFunctional.readUserData" to { b -> AppInfoIOFunctional.readUserData(b, rnd.nextInt(-3, 30), rnd.nextInt(-3, 30)) },
            "ExerciseIO.decode" to { b -> ExerciseIO.decode(b) },
        )
        for ((name, f) in decoders) each(name, frames(typical = 18, count = 200), f)
        report()
    }
}
