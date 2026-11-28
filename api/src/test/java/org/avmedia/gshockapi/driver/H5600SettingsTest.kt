package org.avmedia.gshockapi.driver

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.avmedia.gshockapi.io.WatchDataTap
import org.avmedia.gshockapi.io.ActivityListCodec
import org.avmedia.gshockapi.io.BasicHealthFieldsCodec
import org.avmedia.gshockapi.io.HeartRateSettingCodec
import org.avmedia.gshockapi.io.LengthUnit
import org.avmedia.gshockapi.io.LightSettingsCodec
import org.avmedia.gshockapi.io.NotificationManagerCodec
import org.avmedia.gshockapi.io.NotificationSoundCodec
import org.avmedia.gshockapi.io.PhysicalSettingsCodec
import org.avmedia.gshockapi.io.ProfileCodec
import org.avmedia.gshockapi.io.SafeSettingWrite
import org.avmedia.gshockapi.io.SettingWriteResult
import org.avmedia.gshockapi.io.SmartAlarmCodec
import org.avmedia.gshockapi.io.StepGoalCodec
import org.avmedia.gshockapi.io.WatchFaceCodec
import org.avmedia.gshockapi.link.LinkState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.avmedia.gshockapi.io.AlarmsIO
import org.avmedia.gshockapi.model.Alarm
import org.json.JSONArray
import org.json.JSONObject
import com.google.gson.Gson

/**
 * Every H5600 setting of the driver against today's codec, used as the oracle: the same value from each frame, the same
 * bytes for each write (or both refused), the same bytes touched. The frames are the ones of the codec tests
 * (WatchSettingCodecsTest, NotificationCodecsTest, ActivityItemCodecsTest), plus frames that break a frame's layout.
 */
class H5600SettingsTest {
    private val file = DataFile.load(File("src/main/resources/" + Drivers.H5600_FILE).readText()).getOrThrow()
    private val driver = H5600Driver(file)

    private fun frame(code: Int, vararg values: Int) = byteArrayOf(code.toByte()) + values.map { it.toByte() }.toByteArray()
    private fun basic(flags: Int = 0b0010_0100, light: Int = 0x01, nightStart: Int = 22, nightEnd: Int = 6) =
        frame(0x13, light, 0x02, 0, 1, 0, 0, 0, 0, 0, 3, 0, flags, nightStart, 30, nightEnd, 15, 0)

    private fun t(p: Pair<Int, Int>?) = p?.let { TimeOfDay(it.first, it.second) }

    /** One setting: its old read and write (in the driver's values), the frames and values to try. */
    private class Oracle(
        val key: Key<*>,
        val touched: Set<Int>,
        val read: (ByteArray) -> Any?,
        val write: (ByteArray, Any) -> ByteArray?,
        val frames: List<ByteArray>,
        val values: List<Any>,
    )

    private val face = listOf(frame(0x51, 2, 0, 10, 12, 19, 0x77), frame(0x51, 2, 0, 10, 12, 19, 0x77, 0x88), frame(0x51, 1, 2, 3), frame(0x51, 0, 0, 0, 0, 0, 0), frame(0x51, 5, 0, 0, 0, 25, 0))
    private val basics = listOf(basic(), basic(0b0000_0010), basic(0b0000_0001, light = 0x04), basic(0b0010_0111, light = 0xF1), basic(light = 0x05),
        basic(nightStart = 24), basic(nightEnd = 99), frame(0x13, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), frame(0x13, 1), frame(0x13, 0x00, 0x03))
    private val hr = listOf(frame(0x41, 0b0001_0110, 2, 120, 130, 0x55), frame(0x41, 0, 0, 120, 130), frame(0x41, 3, 0, 120, 130), frame(0x41, 1, 0, 120))
    private val physical = listOf(frame(0x4F, 3, 23, 15, 6, 45, 1, 190, 60, 45), frame(0x4F, 3, 23, 15, 6, 45, 0b001, 190, 60, 45, 0x77),
        frame(0x4F, 3, 23, 15, 6, 45, 0, 0, 0, 0), frame(0x4F, 9, 23, 15, 6, 45, 0, 190, 60, 45), frame(0x4F, 3, 99, 15, 6, 45), frame(0x4F, 3, 23, 15, 6, 61, 7, 250, 20, 95))
    private val steps = listOf(frame(0x43, 0x10, 0x27, 0x00, 9, 9, 7, 7, 7, 1, 1, 1, 1, 1, 0, 5, 1), frame(0x43, 0x10, 0x27, 0x00, 9), frame(0x43, 0x10, 0, 0, 9), frame(0x43, 1, 2))
    private val smart = listOf(frame(0x50, 6, 30, 0b11, 30), frame(0x50, 6, 30, 0b01, 30, 1, 2, 3, 4), frame(0x50, 7, 0, 0b10, 15), frame(0x50, 6, 30, 0b00, 30),
        frame(0x50, 6, 30, 0b11, 30, 9), frame(0x50, 25, 0, 0b01, 30), frame(0x50, 6, 30, 0b01, 20), frame(0x50, 6, 30))
    private val sounds = listOf(frame(0x42, 0b1001, 0x05, 0x01, 0x02, 0x40, 2, 0x77), frame(0x42, 0b1111_1001, 0, 0, 0, 0, 1), frame(0x42, 0x0A, 0xFF, 0x7F, 0xFF, 0x7F),
        frame(0x42, 3, 0, 0, 0, 0, 1), frame(0x42, 0, 0, 0, 0, 0, 4), frame(0x42, 0, 0, 0, 0), frame(0x42, 0x05, 0x3D, 0x11, 0x22, 0x33))
    private fun manager(cmd: Int = 0x30, lang: Int = 0x01, night: Int = 0b0000_0100, popUp: Int = 1) =
        frame(0x3D, cmd, lang, 0xA5, 0, 0, 0, night, 0, 0, 0, 0, 0, 0, 0x5A, 0, 0, popUp)
    private val managers = listOf(manager(), manager(lang = 0xFE, night = 0xFF), manager(lang = 3, night = 0), frame(0x3D, 0x30, 1, 2, 3), frame(0x3D, 0x30, 1, 2, 3, 4, 5, 6))
    private fun profile(vararg v: Int) = ProfileCodec.flip(frame(0x45, *v))
    private val profiles = listOf(
        frame(0x45, 0x00, 0x97, 0xFE, 0x9B, 0xFF, 0x6F, 0xE6, 0xF8, 0xF6, 0xAA), frame(0x45, 0x01, 0x97, 0xFE, 0x9B, 0xFF, 0x6F, 0xE6, 0xF8, 0xF6, 0xAA),
        frame(0x45, 0x08, 0x97, 0xFE, 0x9B, 0xFF, 0x6F, 0xE6, 0xF8, 0xF6, 0xAA), profile(0x00, 0x70, 0x01, 0x70, 0x00, 0x90, 0x19, 0x01, 0x01),
        profile(0x00, 0x01, 0x70, 0x00, 0x70, 0x19, 0x90, 0x01, 0x01), profile(0x04, 0x67, 0x00, 0x54, 0x01, 0x85, 0x19, 0x12, 0x31),
        profile(0x0C, 0x70, 0x00, 0x60, 0x01, 0x00, 0x00, 0x00, 0x00), profile(0x00, 0x99, 0x99, 0x99, 0x99, 0x99, 0x99, 0x99, 0x99), frame(0x45, 1, 2, 3),
    )
    private val lists = listOf(frame(0x53, 1, 2, 3, 6, 7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), frame(0x53, 1, 2, 0, 9, 0, 0), frame(0x53, 14, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5, 5, 5),
        frame(0x53, 1, 2), frame(0x53), frame(0x53, 1, 99, 0))

    private fun time(v: Any) = (v as TimeOfDay).let { it.hour to it.minute }
    private val times = listOf<Any>(TimeOfDay(22, 30), TimeOfDay(7, 0), TimeOfDay(0, 0), TimeOfDay(23, 59), TimeOfDay(24, 0), TimeOfDay(5, 60))
    private val heartModes = listOf("off", "activities", "always")
    private val soundModes = listOf("off", "custom_per_event", "on")
    private val patterns = listOf("mute", "pattern_a", "pattern_b", "pattern_c")
    private val backgrounds = PhysicalSettingsCodec.TRAINING_BACKGROUNDS.map(Keys::word)
    private val items = WatchFaceCodec.ITEM_NAMES.map(Keys::word)

    private val oracles: List<Oracle> = listOf(
        Oracle(Keys.AUTO_LIGHT, LightSettingsCodec.AUTO_LIGHT_BYTES, LightSettingsCodec::autoLight, { f, v -> LightSettingsCodec.withAutoLight(f, v as Boolean) }, basics, listOf(true, false)),
        Oracle(Keys.LIGHT_DURATION, LightSettingsCodec.DURATION_BYTES, LightSettingsCodec::duration, { f, v -> LightSettingsCodec.withDuration(f, v as String) }, basics, listOf("short", "long", "medium")),
        Oracle(Keys.LENGTH_UNIT, BasicHealthFieldsCodec.FLAGS_BYTES, { f -> BasicHealthFieldsCodec.parse(f)?.unit?.let { if (it == LengthUnit.MILES) "miles" else "km" } },
            { f, v -> BasicHealthFieldsCodec.withUnit(f, if (v == "miles") LengthUnit.MILES else LengthUnit.KM) }, basics, listOf("km", "miles")),
        Oracle(Keys.STEP_REMINDER, BasicHealthFieldsCodec.FLAGS_BYTES, { f -> BasicHealthFieldsCodec.parse(f)?.stepReminder }, { f, v -> BasicHealthFieldsCodec.withStepReminder(f, v as Boolean) }, basics, listOf(true, false)),
        Oracle(Keys.NIGHT_START, BasicHealthFieldsCodec.NIGHT_BYTES, { f -> t(BasicHealthFieldsCodec.parse(f)?.nightStart) },
            { f, v -> BasicHealthFieldsCodec.parse(f)?.let { BasicHealthFieldsCodec.withNightWindow(f, time(v), it.nightEnd) } }, basics, times),
        Oracle(Keys.NIGHT_END, BasicHealthFieldsCodec.NIGHT_BYTES, { f -> t(BasicHealthFieldsCodec.parse(f)?.nightEnd) },
            { f, v -> BasicHealthFieldsCodec.parse(f)?.let { BasicHealthFieldsCodec.withNightWindow(f, it.nightStart, time(v)) } }, basics, times),
        Oracle(Keys.HR_MEASUREMENT, HeartRateSettingCodec.MODE_BYTES, { f -> HeartRateSettingCodec.parse(f)?.mode?.let(heartModes::get) },
            { f, v -> HeartRateSettingCodec.withMode(f, heartModes.indexOf(v)) }, hr, heartModes + "sometimes"),
        Oracle(Keys.BEDTIME, PhysicalSettingsCodec.BEDTIME_BYTES, { f -> t(PhysicalSettingsCodec.parse(f)?.bedtime) }, { f, v -> time(v).let { (h, m) -> PhysicalSettingsCodec.withBedtime(f, h, m) } }, physical, times),
        Oracle(Keys.WAKE_UP, PhysicalSettingsCodec.WAKE_BYTES, { f -> t(PhysicalSettingsCodec.parse(f)?.wakeUp) }, { f, v -> time(v).let { (h, m) -> PhysicalSettingsCodec.withWakeUp(f, h, m) } }, physical, times),
        Oracle(Keys.TRAINING_BACKGROUND, PhysicalSettingsCodec.BACKGROUND_BYTES, { f -> PhysicalSettingsCodec.parseBody(f)?.trainingBackground?.let(backgrounds::get) },
            { f, v -> PhysicalSettingsCodec.withTrainingBackground(f, backgrounds.indexOf(v)) }, physical, backgrounds + "olympic"),
        Oracle(Keys.HR_MAX, PhysicalSettingsCodec.MAX_HR_BYTES, { f -> PhysicalSettingsCodec.parseBody(f)?.maxHr }, { f, v -> PhysicalSettingsCodec.withMaxHeartRate(f, v as Int) }, physical, listOf(99, 100, 190, 230, 231)),
        Oracle(Keys.HR_RESTING, PhysicalSettingsCodec.RESTING_HR_BYTES, { f -> PhysicalSettingsCodec.parseBody(f)?.restingHr }, { f, v -> PhysicalSettingsCodec.withRestingHeartRate(f, v as Int) }, physical, listOf(29, 30, 60, 120, 121)),
        Oracle(Keys.VO2MAX, PhysicalSettingsCodec.VO2MAX_BYTES, { f -> PhysicalSettingsCodec.parseBody(f)?.vo2max }, { f, v -> PhysicalSettingsCodec.withVo2max(f, v as Int) }, physical, listOf(9, 10, 45, 90, 91)),
        Oracle(Keys.STEP_GOAL, StepGoalCodec.BYTES, StepGoalCodec::parse, { f, v -> StepGoalCodec.withSteps(f, v as Int) }, steps, listOf(99, 100, 10_000, 12_345, 200_000, 200_001)),
        Oracle(Keys.SMART_ALARM_ON, SmartAlarmCodec.FLAG_BYTES, { f -> SmartAlarmCodec.parse(f)?.on }, { f, v -> SmartAlarmCodec.withOn(f, v as Boolean) }, smart, listOf(true, false)),
        Oracle(Keys.SMART_ALARM_SNOOZE, SmartAlarmCodec.FLAG_BYTES, { f -> SmartAlarmCodec.parse(f)?.snooze }, { f, v -> SmartAlarmCodec.withSnooze(f, v as Boolean) }, smart, listOf(true, false)),
        Oracle(Keys.SMART_ALARM_TIME, SmartAlarmCodec.TIME_BYTES, { f -> SmartAlarmCodec.parse(f)?.let { TimeOfDay(it.hour, it.minute) } },
            { f, v -> time(v).let { (h, m) -> SmartAlarmCodec.withTime(f, h, m) } }, smart, times),
        Oracle(Keys.SMART_ALARM_WINDOW, SmartAlarmCodec.WINDOW_BYTES, { f -> SmartAlarmCodec.parse(f)?.windowMinutes }, { f, v -> SmartAlarmCodec.withWindow(f, v as Int) }, smart, listOf(15, 20, 30, 45, 60, 90)),
        Oracle(Keys.FACE, WatchFaceCodec.FACE_BYTES, { f -> WatchFaceCodec.parse(f)?.face }, { f, v -> WatchFaceCodec.withFace(f, v as Int) }, face, listOf(0, 1, 2, 3, 4, 5)),
        Oracle(Keys.FACE_CUSTOM_ITEM, WatchFaceCodec.TOP_RIGHT_BYTES, { f -> WatchFaceCodec.parse(f)?.items?.get(WatchFaceCodec.TOP_RIGHT_SLOT - 1)?.let { items.getOrNull(it) } },
            { f, v -> WatchFaceCodec.withTopRight(f, items.indexOf(v)) }, face, items + "nothing"),
        Oracle(Keys.NOTIFICATION_SOUND, NotificationSoundCodec.MODE_BYTES, { f -> NotificationSoundCodec.parse(f)?.soundMode?.let(soundModes::getOrNull) },
            { f, v -> NotificationSoundCodec.withSoundMode(f, soundModes.indexOf(v)) }, sounds, soundModes + "loud"),
        Oracle(Keys.NOTIFICATION_VIBRATION, NotificationSoundCodec.MODE_BYTES, { f -> NotificationSoundCodec.parse(f)?.vibrationMode?.let(soundModes::getOrNull) },
            { f, v -> NotificationSoundCodec.withVibrationMode(f, soundModes.indexOf(v)) }, sounds, soundModes),
        Oracle(Keys.NOTIFICATION_PATTERN, NotificationSoundCodec.PATTERN_BYTES, { f -> NotificationSoundCodec.parse(f)?.pattern?.let(patterns::getOrNull) },
            { f, v -> NotificationSoundCodec.withPattern(f, patterns.indexOf(v)) }, sounds, patterns + "pattern_d"),
        Oracle(Keys.NOTIFICATION_LANGUAGE, NotificationManagerCodec.LANGUAGE_BYTES, { f -> NotificationManagerCodec.parse(f)?.language },
            { f, v -> NotificationManagerCodec.withLanguage(f, v as Int) }, managers, listOf(0, 1, 2, 3, 4)),
        Oracle(Keys.NOTIFICATION_OFF_AT_NIGHT, NotificationManagerCodec.NIGHT_BYTES, { f -> NotificationManagerCodec.parse(f)?.offAtNight },
            { f, v -> NotificationManagerCodec.withOffAtNight(f, v as Int) }, managers, listOf(0, 1, 2, 3, 4)),
        Oracle(Keys.HEIGHT_CM, ProfileCodec.BYTES, { f -> ProfileCodec.parse(f)?.heightCm },
            { f, v -> ProfileCodec.parse(f)?.let { p -> ProfileCodec.withHeightAndWeight(f, if (p.imperialLength) Math.round((v as Int) / 2.54).toInt() else v as Int, p.weight) } },
            profiles, listOf(99, 100, 170, 183, 250, 251)),
        Oracle(Keys.WEIGHT_KG, ProfileCodec.BYTES, { f -> ProfileCodec.parse(f)?.weightKg },
            { f, v -> ProfileCodec.parse(f)?.let { p -> ProfileCodec.withHeightAndWeight(f, p.height, if (p.weightNotKg) Math.round((v as Int) * 2.2046226).toInt() else v as Int) } },
            profiles, listOf(19, 20, 70, 300, 301)),
        Oracle(Keys.BIRTH_DATE, ProfileCodec.BIRTH_BYTES,
            { f -> ProfileCodec.parse(f)?.let { p -> if (p.birthYear != null && p.birthMonth != null && p.birthDay != null) "%04d-%02d-%02d".format(p.birthYear, p.birthMonth, p.birthDay) else null } },
            { f, v -> (v as String).split("-").map(String::toInt).let { (y, m, d) -> ProfileCodec.withBirthDate(f, y, m, d) } }, profiles, listOf("1990-04-21", "2000-02-29", "2001-02-29", "1899-12-31")),
        Oracle(Keys.SEX, ProfileCodec.SEX_BYTES, { f -> ProfileCodec.parse(f)?.let { if (it.female) "female" else "male" } }, { f, v -> ProfileCodec.withFemale(f, v == "female") }, profiles, listOf("male", "female")),
    ) + listOf(1, 2, 6, 14).map { code ->
        val name = mapOf(1 to "running", 2 to "walking", 6 to "gym_workout", 14 to "interval_timer").getValue(code)
        Oracle(Key.of<Boolean>("activities.$name"), (1..ActivityListCodec.MAX).toSet(),
            { f -> if (ActivityListCodec.layoutOk(f)) ActivityListCodec.parse(f)?.let { code in it } else null },
            { f, v -> ActivityListCodec.parse(f)?.let { ActivityListCodec.encode(f, ActivityListCodec.toggle(it, code, v as Boolean)) } }, lists, listOf(true, false))
    }

    private fun hex(b: ByteArray?) = b?.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    @Test fun everySettingGivesTheOldCodecsValuesAndBytes() {
        for (o in oracles) {
            val s = assertNotNull("no setting for ${o.key}", driver.frameSetting(o.key.id)).let { driver.frameSetting(o.key.id)!! }
            if (o.key == Keys.NIGHT_START || o.key == Keys.NIGHT_END) {
                // The old write sends the whole window; the data file changes only its own half (the other half is written as it was).
                assertTrue("${o.key}", o.touched.containsAll(s.touched))
            } else {
                assertEquals("${o.key} touched", o.touched, s.touched)
            }
            for (f in o.frames) {
                val wanted = runCatching { o.read(f) }.getOrNull()
                assertEquals("${o.key} read of ${hex(f)}", wanted, s.decode(f))
                for (v in o.values) {
                    val old = runCatching { o.write(f, v) }.getOrNull()
                    assertEquals("${o.key} = $v on ${hex(f)}", hex(old), hex(s.encode(f, v)))
                }
            }
        }
    }

    @Test fun everyDataFileSettingAndCodeKeyIsTested() {
        val tested = oracles.map { it.key.id }.toSet()
        val frameKeys = file.settings.values.filter { it.type != "records" && it.key != "sound.mode" }.map { it.key } +
            file.capabilities.filter { it.startsWith("profile.") || it.startsWith("activities.") }
        assertEquals(frameKeys.toSet() - tested, emptySet<String>())
    }

    @Test fun theNotificationManagerIsReadBackWithItsReadCommand() {
        val s = driver.frameSetting(Keys.NOTIFICATION_LANGUAGE.id)!!
        val before = manager()
        val written = s.encode(before, 2)!!
        assertEquals(NotificationManagerCodec.LANGUAGE_CHANGED, written[1].toInt())
        assertArrayEquals(NotificationManagerCodec.expectedBack(before, written), s.expectedBack(before, written))
        assertArrayEquals(before, s.encode(before, 1)) // the same value: nothing to write
        val night = driver.frameSetting(Keys.NOTIFICATION_OFF_AT_NIGHT.id)!!
        assertEquals(NotificationManagerCodec.NIGHT_CHANGED, night.encode(before, 3)!![1].toInt())
    }

    @Test fun theActivityListIsComparedOnItsSlotsOnly() {
        val s = driver.frameSetting("activities.running")!!
        val a = frame(0x53, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        val b = frame(0x53, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9)
        assertEquals(ActivityListCodec.sameSlots(a, b), s.matches(a, b))
        assertTrue(!s.matches(a, frame(0x53, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
    }

    @Test fun aBadFileIsRefusedWithItsReason() {
        val text = File("src/main/resources/" + Drivers.H5600_FILE).readText()
        fun refused(from: String, to: String): String? = DataFile.load(text.replaceFirst(from, to)).exceptionOrNull()?.message
        assertTrue(refused(""""key": "steps.goal", "type": "uint"""", """"key": "steps.goal", "type": "uint", "write_over_unknown": true""")!!.contains("write_over_unknown"))
        assertTrue(refused(""""valid_with": ["sleep.wake_up"]""", """"valid_with": ["smart_alarm.time"]""")!!.contains("same frame"))
        assertTrue(refused(""""on_write_set": {"6": "0x01"}""", """"on_write_set": {"0": "0x01"}""")!!.contains("code byte"))
        assertTrue(refused(""""settable": ["steps"""", """"settable": ["nothing"""")!!.contains("settable"))
    }

    /** A watch in memory, written through the library's real checked writer. */
    private class FakeConnection(override val name: String, val frames: MutableMap<Int, ByteArray>, val answer: (ByteArray) -> ByteArray = { it }) : WatchConnection {
        override val state: StateFlow<LinkState> = MutableStateFlow(LinkState.READY)
        var writes = 0
        override suspend fun readFrame(code: Int): ByteArray? = frames[code]?.copyOf()
        override suspend fun modify(
            code: Int, touched: Set<Int>, expectedBack: (ByteArray, ByteArray) -> ByteArray, matches: (ByteArray, ByteArray) -> Boolean, change: (ByteArray) -> ByteArray?,
        ): SettingWriteResult = SafeSettingWrite(::readFrame, { f -> writes++; frames[f[0].toInt() and 0xFF] = answer(f) }).update(code, touched, expectedBack, matches, change)
    }

    @Test fun getAndSetGoThroughTheCheckedWriteForEveryKind() = runTest {
        val watch = FakeConnection("CASIO DW-H5600", mutableMapOf(0x13 to basic(), 0x4F to frame(0x4F, 3, 23, 15, 6, 45, 0, 0, 60, 45), 0x45 to profiles[0], 0x53 to lists[0], 0x3D to manager()),
            answer = { f -> if (f[0].toInt() == 0x3D) f.copyOf().also { it[1] = 0x30 } else f })
        val bound = DriverEngine(DriverRegistry(listOf(driver), discovered = { emptyList() })).bind(watch)
        assertEquals(DriverResult.Ok(TimeOfDay(22, 30)), bound.get(Keys.NIGHT_START))
        assertEquals(DriverResult.Ok(Unit), bound.set(Keys.NIGHT_START, TimeOfDay(23, 0)))
        assertEquals(DriverResult.Ok(TimeOfDay(6, 15)), bound.get(Keys.NIGHT_END))
        assertEquals(DriverResult.Ok(0), bound.get(Keys.HR_MAX)) // read whatever it is, as the old codec
        assertEquals(DriverResult.Ok(Unit), bound.set(Keys.HR_MAX, 185))
        assertArrayEquals(frame(0x4F, 3, 23, 15, 6, 45, 1, 185, 60, 45), watch.frames[0x4F])
        assertTrue(bound.set(Keys.HR_MAX, 250) is DriverResult.Failed)
        assertEquals(DriverResult.Ok(Unit), bound.set(Keys.HEIGHT_CM, 180))
        assertEquals(DriverResult.Ok(180), bound.get(Keys.HEIGHT_CM))
        assertEquals(DriverResult.Ok(Unit), bound.set(Keys.activity("Interval timer"), true))
        assertEquals(DriverResult.Ok(true), bound.get(Keys.activity("Interval timer")))
        assertEquals(DriverResult.Ok(Unit), bound.set(Keys.NOTIFICATION_LANGUAGE, 2)) // read back with 30, compared as the watch answers
        assertEquals(DriverResult.Ok(2), bound.get(Keys.NOTIFICATION_LANGUAGE))
        val before = watch.writes
        assertEquals(DriverResult.Ok(Unit), bound.set(Keys.NOTIFICATION_LANGUAGE, 2))
        assertEquals(before, watch.writes) // unchanged: nothing written
        assertTrue(bound.set(Keys.FACE, 4) is DriverResult.Unsupported) // a DW-H5600 has 3 faces
    }

    /** The alarm slots: each frame from its own read, only the slots' fields and fixed byte change, as the old writer where nothing else is set. */
    @Test fun alarmsAreWrittenPerFrameFromAFreshRead() = runTest {
        val unknown = frame(0x16, 0x48, 0x40, 8, 0, 0x01, 0x40, 9, 15, 0x00, 0x40, 10, 30)
        val watch = FakeConnection("CASIO DW-H5600", mutableMapOf(0x15 to frame(0x15, 0x00, 0x40, 6, 0), 0x16 to unknown))
        val bound = DriverEngine(DriverRegistry(listOf(driver), discovered = { emptyList() })).bind(watch)
        assertEquals(DriverResult.Ok(listOf(AlarmSlot(6, 0, false), AlarmSlot(8, 0, true), AlarmSlot(9, 15, false), AlarmSlot(10, 30, false))), bound.get(Keys.ALARMS))
        val slots = listOf(AlarmSlot(7, 30, true, oneTime = true), AlarmSlot(8, 0, true, oneTime = true), AlarmSlot(9, 15, false, oneTime = true), AlarmSlot(10, 45, true, hourlyChime = true))
        assertEquals(DriverResult.Ok(Unit), bound.set(Keys.ALARMS, slots))
        // As the old writer: no "one time" on the first alarm or on one that is off.
        val old = listOf(Alarm(7, 30, true), Alarm(8, 0, true, oneTime = true), Alarm(9, 15, false), Alarm(10, 45, true, hasHourlyChime = true))
        assertArrayEquals(AlarmsIO.fromJsonAlarmFirstAlarm(JSONObject(Gson().toJson(old[0]))), watch.frames[0x15])
        val oldRest = AlarmsIO.fromJsonAlarmSecondaryAlarms(JSONArray().also { a -> old.forEach { a.put(JSONObject(Gson().toJson(it))) } })
        // The bits the old writer does not know (0x08 of alarm 2, 0x01 of alarm 3) stay as the watch had them.
        assertArrayEquals(oldRest.copyOf().also { it[1] = (it[1].toInt() or 0x08).toByte(); it[5] = (it[5].toInt() or 0x01).toByte() }, watch.frames[0x16])
        assertEquals(2, watch.writes)
        assertEquals(DriverResult.Ok(HourMinute.slots(old)), bound.get(Keys.ALARMS))
        assertEquals(DriverResult.Ok(Unit), bound.set(Keys.ALARMS, slots))
        assertEquals(2, watch.writes) // the same slots again: nothing written
        assertTrue(bound.set(Keys.ALARMS, listOf(AlarmSlot(24, 0, true))) is DriverResult.Failed)
        assertEquals(2, watch.writes)
    }

    private object HourMinute {
        fun slots(alarms: List<Alarm>) = alarms.map { AlarmSlot(it.hour, it.minute, it.enabled, it.oneTime, it.hasHourlyChime) }
    }

    /** The time is code on the link: a connection without it is refused before anything is sent; the keys with an index share one capability. */
    @Test fun timeKeysNeedTheLinkAndShareTheirCapability() = runTest {
        val watch = FakeConnection("CASIO GBX-H5600", mutableMapOf())
        val bound = DriverEngine(DriverRegistry(listOf(driver), discovered = { emptyList() })).bind(watch)
        assertTrue(bound.has(Keys.TIME) && bound.has(Keys.HOME_TIME) && bound.has(Keys.worldCity(1)) && bound.has(Keys.dstWatchState(0)))
        for (r in listOf(bound.set(Keys.TIME, TimeSync("Europe/Brussels", offsetMs = 250)), bound.get(Keys.HOME_TIME), bound.get(Keys.worldCity(0)), bound.get(Keys.dstWatchState(0)))) {
            assertTrue(r.toString(), r is DriverResult.Unsupported && r.reason.contains("link engine"))
        }
        assertTrue(bound.get(Keys.dstWatchState(3)) is DriverResult.Unsupported)
        assertTrue(bound.get(Keys.TIME) is DriverResult.Unsupported)
        assertEquals(0, watch.writes)
        assertEquals("time.world_cities", Keys.capabilityOf(Keys.worldCity(5).id))
        assertEquals("time.sync", Keys.capabilityOf(Keys.TIME.id))
    }

    /** Health data: the transfers are code on the link (refused here, nothing sent); 0x28 is a plain read; each watch read's copy is an event of its watch. */
    @Test fun healthKeysAndTheWatchDataCopies() = runTest {
        val condition = frame(0x28, 6, 21, 0, 0, 0, 0, 1, 0, 0)
        val watch = FakeConnection("CASIO DW-H5600", mutableMapOf(0x28 to condition))
        val bound = DriverEngine(DriverRegistry(listOf(driver), discovered = { emptyList() })).bind(watch)
        assertArrayEquals(condition, (bound.get(Keys.CONDITION) as DriverResult.Ok).value)
        for (k in listOf(Keys.LIFELOG, Keys.LIFELOG_PEEK, Keys.SLEEP, Keys.WORKOUTS)) {
            val r = bound.get(k)
            assertTrue("$k $r", bound.has(k) && r is DriverResult.Unsupported && r.reason.contains("link engine"))
        }
        assertEquals(0, watch.writes)
        val got = mutableListOf<WatchEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { bound.events.collect { got += it } }
        testScheduler.runCurrent()
        WatchDataTap.emit(WatchDataTap.Read("setting", byteArrayOf(0x13), null, code = 0x13, model = "CASIO DW-H5600"))
        WatchDataTap.emit(WatchDataTap.Read("setting", byteArrayOf(0x13), null, code = 0x13, model = "CASIO GW-B5600"))
        WatchDataTap.emit(WatchDataTap.Read("workout", byteArrayOf(1), null))
        testScheduler.runCurrent()
        job.cancel()
        val data = got.filterIsInstance<WatchEvent.Data>()
        assertEquals(listOf("setting", "workout"), data.map { (it.value as WatchDataTap.Read).kind })
        assertTrue(data.all { it.kind == Keys.WATCH_DATA })
    }

    /** The notification text and the connection's button are code on the link: refused before anything is sent without it. */
    @Test fun notificationsAndTheButtonNeedTheLink() = runTest {
        val watch = FakeConnection("CASIO GBX-H5600", mutableMapOf())
        val bound = DriverEngine(DriverRegistry(listOf(driver), discovered = { emptyList() })).bind(watch)
        val n = org.avmedia.gshockapi.AppNotification(org.avmedia.gshockapi.NotificationType.GENERIC, "20261128T080000", "App", "Title", "Text")
        for (r in listOf(bound.set(Keys.NOTIFICATION, n), bound.get(Keys.BUTTON))) {
            assertTrue(r.toString(), r is DriverResult.Unsupported && r.reason.contains("link engine"))
        }
        assertTrue(bound.set(Keys.BUTTON, "LOWER_RIGHT") is DriverResult.Unsupported)
        assertEquals(0, watch.writes)
    }

    /** What an app asks the driver instead of the model table gives the same answer for every model of the file. */
    @Test fun theDriversCapabilitiesAgreeWithTheModelTable() {
        for (m in file.models) {
            val name = "CASIO ${m.name}"
            val info = org.avmedia.gshockapi.WatchInfo.infoFor(name)
            val caps = Drivers.capabilities(name)
            fun has(k: Key<*>) = Keys.capabilityOf(k.id) in caps
            assertEquals(name, info.hasAutoLight, has(Keys.AUTO_LIGHT))
            assertEquals(name, info.hasLight, has(Keys.LIGHT_DURATION))
            assertEquals(name, info.hasBatteryLevel, has(Keys.BATTERY))
            assertEquals(name, info.worldCities, has(Keys.worldCity(0)))
            assertEquals(name, info.hasHomeTime, has(Keys.HOME_TIME))
            assertEquals(name, info.hasStepCounter, has(Keys.STEPS_TODAY))
            assertEquals(name, info.alarmCount > 0, has(Keys.ALARMS))
            assertEquals(name, info.alarmCount, Drivers.alarmCount(name))
            assertTrue(name, has(Keys.LIFELOG) && has(Keys.LIFELOG_PEEK))
        }
        assertNull(Drivers.alarmCount("CASIO GW-B5600"))
    }

    @Test fun theLibraryFindsItsOwnFile() {
        assertTrue(Drivers.driverFor("CASIO GBX-H5600") is H5600Driver)
        assertTrue(Drivers.driverFor("CASIO GW-B5600") is ProtocolDriver) // its protocol family, no data file
        assertNull(Drivers.driverFor(""))
        assertEquals(4, Drivers.faceCount("CASIO GBX-H5600"))
        assertEquals(3, Drivers.faceCount("CASIO DW-H5600"))
        assertTrue("face.tide" in Drivers.capabilities("CASIO GBX-H5600") && "face.tide" !in Drivers.capabilities("CASIO GM-H5600"))
        assertEquals(emptySet<String>(), Drivers.capabilities("CASIO GW-B5600"))
    }
}
