package org.avmedia.gshockapi.driver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.io.SettingWriteResult
import org.avmedia.gshockapi.link.LinkState
import org.avmedia.gshockapi.protocols.AnalogueProtocol
import org.avmedia.gshockapi.protocols.MipProtocol
import org.avmedia.gshockapi.protocols.OceanusProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The other protocol families as drivers: each takes the exact model names of the table whose protocol it speaks, its
 * capabilities are the table's answers, and every key is today's call on the link (refused without it, nothing sent).
 */
class ProtocolDriversTest {
    private class NoLink(override val name: String) : WatchConnection {
        override val state: StateFlow<LinkState> = MutableStateFlow(LinkState.READY)
        var asked = 0
        override suspend fun readFrame(code: Int): ByteArray? = null.also { asked++ }
        override suspend fun modify(
            code: Int, touched: Set<Int>, expectedBack: (ByteArray, ByteArray) -> ByteArray, matches: (ByteArray, ByteArray) -> Boolean, change: (ByteArray) -> ByteArray?,
        ): SettingWriteResult = SettingWriteResult.Failed("no").also { asked++ }
    }

    private val names = listOf(
        "CASIO GW-B5600", "CASIO GMW-B5000", "CASIO MRG-B5000", "CASIO GA-B2100", "CASIO ECB-30", "CASIO ABL-100WE",
        "CASIO GW-BX5600", "CASIO MTG-B1000", "CASIO MTG-B3000", "CASIO MTG-B3100", "CASIO OCW-S400", "CASIO OCW-S7000",
        "CASIO GBX-H5600", "CASIO DW-H5600", "CASIO GBD-H2000",
    )

    @Test fun eachKnownModelGoesToTheDriverOfItsProtocol() {
        for (name in names) {
            val protocol = WatchInfo.resolvedFor(name).second
            val expected = when (protocol) {
                OceanusProtocol -> "oceanus"
                MipProtocol -> "mip"
                AnalogueProtocol -> "analogue"
                org.avmedia.gshockapi.protocols.H5600Protocol -> "h5600"
                else -> "standard"
            }
            assertEquals(name, expected, Drivers.driverFor(name)?.id)
        }
        // At least one model of each family is in the list.
        assertEquals(setOf("oceanus", "mip", "analogue", "standard", "h5600"), names.mapNotNull { Drivers.driverFor(it)?.id }.toSet())
        // Only exact names of the table: an unknown or partial name has no driver.
        for (name in listOf("CASIO GW-B5600X", "GW-B56", "CASIO ", "", "Pixel Watch")) assertNull(name, Drivers.driverFor(name))
        // A protocol driver is no data file: no frame settings, nothing for the data-file questions.
        assertTrue(Drivers.driverFor("CASIO GW-B5600") !is DataDriver)
        assertEquals(emptySet<String>(), Drivers.capabilities("CASIO GW-B5600"))
        assertNull(Drivers.alarmCount("CASIO GW-B5600"))
    }

    @Test fun theCapabilitiesAreTheModelTablesAnswers() {
        for (name in names.filter { Drivers.driverFor(it) is ProtocolDriver }) {
            val info = WatchInfo.infoFor(name)
            val bound = Drivers.bind(NoLink(name))
            assertEquals(name, info.alarmCount > 0, bound.has(Keys.ALARMS))
            assertEquals(name, info.hasHomeTime, bound.has(Keys.HOME_TIME) && bound.has(Keys.dstWatchState(0)))
            assertEquals(name, info.worldCities, bound.has(Keys.worldCity(1)))
            assertEquals(name, info.hasBatteryLevel, bound.has(Keys.BATTERY))
            assertEquals(name, info.hasStepCounter, bound.has(Keys.STEPS_TODAY))
            assertEquals(name, info.hasAppNotifications, bound.has(Keys.NOTIFICATION))
            assertTrue(name, bound.has(Keys.TIME) && bound.has(Keys.BUTTON))
            assertTrue(name, !bound.has(Keys.AUTO_LIGHT) && !bound.has(Keys.LIFELOG) && !bound.has(Keys.CONDITION))
        }
    }

    @Test fun everyKeyIsRefusedWithoutTheLinkAndNothingIsSent() = runTest {
        val watch = NoLink("CASIO GW-B5600")
        val bound = Drivers.bind(watch)
        for (r in listOf(bound.get(Keys.ALARMS), bound.set(Keys.ALARMS, listOf(AlarmSlot(7, 0, true))), bound.set(Keys.TIME, TimeSync("Europe/Brussels")),
            bound.get(Keys.BATTERY), bound.get(Keys.HOME_TIME), bound.get(Keys.worldCity(1)), bound.get(Keys.BUTTON))) {
            assertTrue(r.toString(), r is DriverResult.Unsupported && r.reason.contains("link engine"))
        }
        assertTrue(bound.get(Keys.AUTO_LIGHT) is DriverResult.Unsupported)
        assertTrue(bound.set(Keys.BUTTON, "LOWER_RIGHT") is DriverResult.Unsupported)
        assertTrue(bound.get(Keys.TIME) is DriverResult.Unsupported)
        assertEquals(0, watch.asked)
    }
}
