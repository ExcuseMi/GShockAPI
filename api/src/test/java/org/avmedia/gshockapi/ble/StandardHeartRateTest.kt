package org.avmedia.gshockapi.ble

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardHeartRateTest {
    private fun u(s: String) = UUID.fromString(s)

    private val h5600 = listOf(
        StandardHeartRate.Service(u("00001800-0000-1000-8000-00805f9b34fb"), listOf(u("00002a00-0000-1000-8000-00805f9b34fb") to listOf("READ"))),
        StandardHeartRate.Service(u("26eb000d-b012-49a8-b1f8-394fb2032b0f"), listOf(u("26eb0023-b012-49a8-b1f8-394fb2032b0f") to listOf("WRITE", "NOTIFY"))),
        StandardHeartRate.Service(u("00001801-0000-1000-8000-00805f9b34fb"), emptyList()),
    )

    @Test fun tableLineNamesEveryCharacteristicAndTheHeartRateService() {
        assertEquals("1800: 2a00 READ; 26eb000d: 26eb0023 WRITE|NOTIFY; 1801: -; heart rate service 180d: no", StandardHeartRate.tableLine(h5600))
        StandardHeartRate.onDiscovery(h5600)
        assertFalse(StandardHeartRate.present)
        val withHrs = h5600 + StandardHeartRate.Service(StandardHeartRate.SERVICE, listOf(StandardHeartRate.MEASUREMENT to listOf("NOTIFY")))
        assertTrue(StandardHeartRate.tableLine(withHrs).endsWith("180d: 2a37 NOTIFY; heart rate service 180d: yes"))
        StandardHeartRate.onDiscovery(withHrs)
        assertTrue(StandardHeartRate.present)
        StandardHeartRate.onDiscovery(emptyList())
    }

    @Test fun measurementInEightAndSixteenBits() {
        assertEquals(72, StandardHeartRate.bpm(byteArrayOf(0x00, 72)))
        assertEquals(300, StandardHeartRate.bpm(byteArrayOf(0x01, 0x2C, 0x01)))
        assertNull(StandardHeartRate.bpm(byteArrayOf(0x01, 0x2C)))
        assertNull(StandardHeartRate.bpm(byteArrayOf(0x00, 0x00)))
        assertNull(StandardHeartRate.bpm(byteArrayOf(0x00)))
    }
}
