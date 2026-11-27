package org.avmedia.gshockapi.driver

import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.io.AlarmsIO
import org.avmedia.gshockapi.io.AlarmsIOFunctional
import org.avmedia.gshockapi.io.BasicHealthFieldsCodec
import org.avmedia.gshockapi.io.HeartRateSettingCodec
import org.avmedia.gshockapi.io.LengthUnit
import org.avmedia.gshockapi.io.LightSettingsCodec
import org.avmedia.gshockapi.io.PhysicalSettingsCodec
import org.avmedia.gshockapi.io.SafeSettingWrite
import org.avmedia.gshockapi.io.SettingWriteResult
import org.avmedia.gshockapi.io.SmartAlarmCodec
import org.avmedia.gshockapi.io.StepGoalCodec
import org.avmedia.gshockapi.io.WatchFaceCodec
import org.avmedia.gshockapi.link.LinkState
import org.avmedia.gshockapi.model.Alarm
import org.avmedia.gshockapi.model.SoundMode
import org.avmedia.gshockapi.protocols.H5600Protocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The H5600 data file and the generic codec give the same values and the same bytes as today's H5600 codecs, on the
 * frames of the existing codec tests (WatchSettingCodecsTest, LinkIOTest).
 */
class H5600DataFileTest {
    private val file = DataFile.parse(File("src/main/resources/" + Drivers.H5600_FILE).readText())
    private fun spec(key: Key<*>) = file.settings.getValue(key.id)
    private fun frame(code: Int, vararg values: Int) = byteArrayOf(code.toByte()) + values.map { it.toByte() }.toByteArray()
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private fun basic(flags: Int = 0b0010_0100, light: Int = 0x01) =
        frame(0x13, light, 0x02, 0, 1, 0, 0, 0, 0, 0, 3, 0, flags, 22, 30, 6, 15, 0)

    private fun dec(key: Key<*>, f: ByteArray) = DataSettingCodec.decode(spec(key), f)
    private fun enc(key: Key<*>, f: ByteArray, v: Any, others: Map<String, Any?> = emptyMap()) = DataSettingCodec.encode(spec(key), f, v, others)

    @Test fun theModelsAreExactlyTheH5600ProtocolNames() {
        for (m in file.models) assertEquals(m.name, H5600Protocol, WatchInfo.resolvedFor("CASIO ${m.name}").second)
        assertEquals(4, file.model("CASIO GBX-H5600")!!.faces)
        assertEquals(WatchInfo.faceCountFor("CASIO DW-H5600"), file.model("CASIO DW-H5600")!!.faces)
        val driver = DataDriver(file)
        assertTrue(driver.matches("CASIO GBX-H5600"))
        assertTrue(!driver.matches("CASIO GW-B5600") && !driver.matches("CASIO GBX-H5600X"))
        assertEquals(WatchInfo.infoFor("CASIO DW-H5600").alarmCount, file.settings.getValue("alarms").frames.sumOf { it.count })
    }

    @Test fun everySettingIsAKnownCapability() {
        assertTrue((file.settings.keys - file.capabilities).isEmpty())
    }

    @Test fun autoLightAndDurationMatchTheLightCodec() {
        for (light in listOf(0x00, 0x04, 0x05, 0xF1)) {
            val f = basic(light = light)
            assertEquals(LightSettingsCodec.autoLight(f), dec(Keys.AUTO_LIGHT, f))
            for (on in listOf(true, false)) assertArrayEquals(LightSettingsCodec.withAutoLight(f, on), enc(Keys.AUTO_LIGHT, f, on))
        }
        val f = basic()
        assertEquals(LightSettingsCodec.duration(f), dec(Keys.LIGHT_DURATION, f))
        assertArrayEquals(LightSettingsCodec.withDuration(f, "long"), enc(Keys.LIGHT_DURATION, f, "long"))
        assertArrayEquals(LightSettingsCodec.withDuration(f, "short"), enc(Keys.LIGHT_DURATION, f, "short"))
        assertEquals(LightSettingsCodec.AUTO_LIGHT_BYTES, DataSettingCodec.touched(spec(Keys.AUTO_LIGHT)))
        assertEquals(LightSettingsCodec.DURATION_BYTES, DataSettingCodec.touched(spec(Keys.LIGHT_DURATION)))
        assertNull(dec(Keys.AUTO_LIGHT, frame(0x13, 1)))
    }

    @Test fun unitAndStepReminderMatchTheBasicFieldsCodec() {
        for (flags in listOf(0b0010_0100, 0b0000_0010, 0b0000_0001, 0b0010_0111)) {
            val f = basic(flags)
            val fields = BasicHealthFieldsCodec.parse(f)!!
            assertEquals(fields.stepReminder, dec(Keys.STEP_REMINDER, f))
            assertEquals(if (fields.unit == LengthUnit.MILES) "miles" else "km", dec(Keys.LENGTH_UNIT, f))
            assertArrayEquals(BasicHealthFieldsCodec.withStepReminder(f, true), enc(Keys.STEP_REMINDER, f, true))
            assertArrayEquals(BasicHealthFieldsCodec.withStepReminder(f, false), enc(Keys.STEP_REMINDER, f, false))
            assertArrayEquals(BasicHealthFieldsCodec.withUnit(f, LengthUnit.MILES), enc(Keys.LENGTH_UNIT, f, "miles"))
            assertArrayEquals(BasicHealthFieldsCodec.withUnit(f, LengthUnit.KM), enc(Keys.LENGTH_UNIT, f, "km"))
        }
        assertEquals(BasicHealthFieldsCodec.FLAGS_BYTES, DataSettingCodec.touched(spec(Keys.STEP_REMINDER)))
        assertNull(dec(Keys.STEP_REMINDER, frame(0x13, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)))
    }

    @Test fun heartRateMeasurementMatchesItsCodec() {
        val before = frame(0x41, 0b0001_0110, 2, 120, 130, 0x55)
        assertEquals("always", dec(Keys.HR_MEASUREMENT, before))
        assertEquals(HeartRateSettingCodec.parse(before)!!.mode, spec(Keys.HR_MEASUREMENT).values.getValue("always"))
        for ((name, mode) in spec(Keys.HR_MEASUREMENT).values) assertArrayEquals(HeartRateSettingCodec.withMode(before, mode), enc(Keys.HR_MEASUREMENT, before, name))
        assertEquals(HeartRateSettingCodec.MODE_BYTES, DataSettingCodec.touched(spec(Keys.HR_MEASUREMENT)))
        assertNull(dec(Keys.HR_MEASUREMENT, frame(0x41, 3, 0, 120, 130)))
        assertNull(enc(Keys.HR_MEASUREMENT, before, "sometimes"))
    }

    @Test fun soundModeValuesAreTheSoundModeCodes() {
        assertEquals(SoundMode.entries.associate { it.name.lowercase() to it.code }, spec(Keys.SOUND_MODE).values)
        val f = frame(0x42, 0x05, 0x3D, 0x11, 0x22, 0x33)
        assertEquals("custom", dec(Keys.SOUND_MODE, f))
        // The existing write sets byte 1 to the mode code and nothing else.
        val expected = f.copyOf().also { it[1] = SoundMode.VIBE_ONLY.code.toByte() }
        assertArrayEquals(expected, enc(Keys.SOUND_MODE, f, "vibe_only"))
    }

    @Test fun faceMatchesTheFaceCodec() {
        val before = frame(0x51, 2, 0, 10, 12, 19, 0x77)
        assertEquals(WatchFaceCodec.parse(before)!!.face, dec(Keys.FACE, before))
        for (face in WatchFaceCodec.FACES) assertArrayEquals(WatchFaceCodec.withFace(before, face), enc(Keys.FACE, before, face))
        assertEquals(WatchFaceCodec.FACE_BYTES, DataSettingCodec.touched(spec(Keys.FACE)))
        assertNull(enc(Keys.FACE, before, 0))
    }

    @Test fun stepGoalMatchesItsCodec() {
        val before = frame(0x43, 0x10, 0x27, 0x00, 9, 9, 7, 7, 7, 1, 1, 1, 1, 1, 0, 5, 1)
        assertEquals(StepGoalCodec.parse(before), dec(Keys.STEP_GOAL, before))
        for (steps in listOf(100, 12_345, 200_000)) assertArrayEquals(StepGoalCodec.withSteps(before, steps), enc(Keys.STEP_GOAL, before, steps))
        assertNull(enc(Keys.STEP_GOAL, before, 99))
        assertEquals(StepGoalCodec.BYTES, DataSettingCodec.touched(spec(Keys.STEP_GOAL)))
    }

    @Test fun bedtimeAndWakeUpMatchThePhysicalCodec() {
        val before = frame(0x4F, 3, 23, 15, 6, 45, 1, 190, 60, 45)
        val sleep = PhysicalSettingsCodec.parse(before)!!
        assertEquals(TimeOfDay(sleep.bedtime.first, sleep.bedtime.second), dec(Keys.BEDTIME, before))
        assertEquals(TimeOfDay(sleep.wakeUp.first, sleep.wakeUp.second), dec(Keys.WAKE_UP, before))
        assertArrayEquals(PhysicalSettingsCodec.withBedtime(before, 22, 30), enc(Keys.BEDTIME, before, TimeOfDay(22, 30)))
        assertArrayEquals(PhysicalSettingsCodec.withWakeUp(before, 7, 0), enc(Keys.WAKE_UP, before, TimeOfDay(7, 0)))
        assertEquals(PhysicalSettingsCodec.BEDTIME_BYTES, DataSettingCodec.touched(spec(Keys.BEDTIME)))
        assertEquals(PhysicalSettingsCodec.WAKE_BYTES, DataSettingCodec.touched(spec(Keys.WAKE_UP)))
        assertNull(enc(Keys.BEDTIME, before, TimeOfDay(24, 0)))
    }

    @Test fun smartAlarmMatchesItsCodec() {
        for (f in listOf(frame(0x50, 6, 30, 0b11, 30), frame(0x50, 6, 30, 0b01, 30, 1, 2, 3, 4), frame(0x50, 7, 0, 0b10, 15), frame(0x50, 6, 30, 0b00, 30))) {
            val s = SmartAlarmCodec.parse(f)!!
            assertEquals(s.on, dec(Keys.SMART_ALARM_ON, f))
            assertEquals(s.snooze, dec(Keys.SMART_ALARM_SNOOZE, f))
            assertEquals(TimeOfDay(s.hour, s.minute), dec(Keys.SMART_ALARM_TIME, f))
            assertEquals(s.windowMinutes, dec(Keys.SMART_ALARM_WINDOW, f))
            for (on in listOf(true, false)) assertArrayEquals(SmartAlarmCodec.withOn(f, on), enc(Keys.SMART_ALARM_ON, f, on))
            val others = mapOf("smart_alarm.on" to s.on)
            for (snooze in listOf(true, false)) {
                val expected = SmartAlarmCodec.withSnooze(f, snooze)
                val actual = enc(Keys.SMART_ALARM_SNOOZE, f, snooze, others)
                if (expected == null) assertNull(actual) else assertArrayEquals(expected, actual)
            }
            assertArrayEquals(SmartAlarmCodec.withTime(f, 5, 45), enc(Keys.SMART_ALARM_TIME, f, TimeOfDay(5, 45)))
            for (w in SmartAlarmCodec.WINDOWS) assertArrayEquals(SmartAlarmCodec.withWindow(f, w), enc(Keys.SMART_ALARM_WINDOW, f, w))
            assertNull(enc(Keys.SMART_ALARM_WINDOW, f, 20))
        }
        assertEquals(SmartAlarmCodec.FLAG_BYTES, DataSettingCodec.touched(spec(Keys.SMART_ALARM_ON)))
        assertEquals(SmartAlarmCodec.TIME_BYTES, DataSettingCodec.touched(spec(Keys.SMART_ALARM_TIME)))
        assertEquals(SmartAlarmCodec.WINDOW_BYTES, DataSettingCodec.touched(spec(Keys.SMART_ALARM_WINDOW)))
        assertNull(dec(Keys.SMART_ALARM_ON, frame(0x50, 6, 30)))
    }

    private fun alarmJson(alarm: Alarm) = JSONObject(Gson().toJson(alarm))

    @Test fun alarmsDecodeAsTheAlarmDecoder() {
        val info = WatchInfo.infoFor("CASIO GBX-H5600")
        for (wire in listOf("15 40 40 07 1E", "15 40 40 01 00", "15 C0 40 17 3B", "15 00 40 06 00")) {
            val old = AlarmsIOFunctional.parseReceivedAlarms(wire, info).single()
            val new = DataDriver.slot(DataSettingCodec.decodeRecords(file.settings.getValue("alarms"), hex(wire))!!.single())
            assertEquals(wire, AlarmSlot(old.hour, old.minute, old.enabled, old.oneTime, old.hasHourlyChime), new)
        }
        val second = "16 40 40 08 00 60 40 09 15 00 40 0A 1E"
        val old = AlarmsIOFunctional.parseReceivedAlarms(second, info)
        val new = DataSettingCodec.decodeRecords(file.settings.getValue("alarms"), hex(second))!!.map(DataDriver::slot)
        assertEquals(old.map { AlarmSlot(it.hour, it.minute, it.enabled, it.oneTime, it.hasHourlyChime) }, new)
    }

    @Test fun alarmsEncodeAsTheAlarmWriter() {
        val spec = file.settings.getValue("alarms")
        val alarms = listOf(Alarm(7, 30, true), Alarm(8, 0, true, oneTime = true), Alarm(9, 15, false), Alarm(10, 30, true, hasHourlyChime = true))
        val first = DataSettingCodec.encodeRecords(spec, ByteArray(5).also { it[0] = 0x15 }, listOf(DataDriver.fields(slotOf(alarms[0]))))
        assertArrayEquals(AlarmsIO.fromJsonAlarmFirstAlarm(alarmJson(alarms[0])), first)
        val rest = DataSettingCodec.encodeRecords(spec, ByteArray(13).also { it[0] = 0x16 }, alarms.drop(1).map { DataDriver.fields(slotOf(it)) })
        val json = JSONArray().also { a -> alarms.forEach { a.put(alarmJson(it)) } }
        assertArrayEquals(AlarmsIO.fromJsonAlarmSecondaryAlarms(json), rest)
        assertNull(DataSettingCodec.encodeRecords(spec, ByteArray(5).also { it[0] = 0x15 }, listOf(DataDriver.fields(AlarmSlot(24, 0, true)))))
    }

    private fun slotOf(a: Alarm) = AlarmSlot(a.hour, a.minute, a.enabled, a.oneTime, a.hasHourlyChime)

    /** A watch in memory: its frames, written through the library's real checked writer. */
    private class FakeConnection(override val name: String, val frames: MutableMap<Int, ByteArray>) : WatchConnection {
        override val state: StateFlow<LinkState> = MutableStateFlow(LinkState.READY)
        override suspend fun readFrame(code: Int): ByteArray? = frames[code]?.copyOf()
        override suspend fun modify(
            code: Int, touched: Set<Int>, expectedBack: (ByteArray, ByteArray) -> ByteArray, matches: (ByteArray, ByteArray) -> Boolean, change: (ByteArray) -> ByteArray?,
        ): SettingWriteResult =
            SafeSettingWrite(::readFrame, { f -> frames[f[0].toInt() and 0xFF] = f }).update(code, touched, expectedBack, matches, change)
    }

    @Test fun theDataDriverReadsAndWritesThroughTheCheckedWriter() = runTest {
        val conn = FakeConnection("CASIO GBX-H5600", mutableMapOf(0x13 to basic(light = 0x05), 0x50 to frame(0x50, 6, 30, 0b11, 30)))
        val watch = DriverEngine(DriverRegistry(listOf(H5600Driver(file)), discovered = { emptyList() })).bind(conn)
        assertEquals(DriverResult.Ok(false), watch.get(Keys.AUTO_LIGHT))
        assertEquals(DriverResult.Ok(Unit), watch.set(Keys.AUTO_LIGHT, true))
        assertArrayEquals(LightSettingsCodec.withAutoLight(basic(light = 0x05), true), conn.frames[0x13])
        assertEquals(DriverResult.Ok(Unit), watch.set(Keys.SMART_ALARM_ON, false))
        assertArrayEquals(frame(0x50, 6, 30, 0, 30), conn.frames[0x50])
        assertTrue(watch.set(Keys.SMART_ALARM_SNOOZE, true) is DriverResult.Failed)
        assertTrue(watch.get(Keys.HR_MEASUREMENT) is DriverResult.Failed) // 0x41 never answered
        assertTrue("face.tide" in watch.capabilities)
    }

    @Test fun whatIsMissingIsUnsupportedNeverACrash() = runTest {
        val engine = DriverEngine(DriverRegistry(listOf(H5600Driver(file)), discovered = { emptyList() }))
        val dw = engine.bind(FakeConnection("CASIO DW-H5600", mutableMapOf(0x51 to frame(0x51, 1, 0, 10, 12, 19))))
        assertTrue(dw.set(Keys.FACE, 4) is DriverResult.Unsupported)
        assertTrue("face.tide" !in dw.capabilities)
        assertTrue(dw.get(Key.of<Boolean>("compass.calibrate")) is DriverResult.Unsupported)
        assertTrue(dw.get(Keys.STEPS_TODAY) is DriverResult.Unsupported) // needs the link engine
        val other = engine.bind(FakeConnection("CASIO GW-B5600", mutableMapOf()))
        assertEquals(null, other.driver)
        assertTrue(other.get(Keys.AUTO_LIGHT) is DriverResult.Unsupported)
    }

    @Test fun aBuiltInDriverWinsOverADiscoveredOne() {
        val outside = object : DataDriver(file) { override val id = "outside" }
        val registry = DriverRegistry(listOf(H5600Driver(file)), discovered = { listOf(outside) })
        assertEquals("h5600", registry.driverFor("CASIO GBX-H5600")!!.id)
        assertEquals(null, registry.driverFor("CASIO B2100"))
    }
}
