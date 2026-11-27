package org.avmedia.gshockapi.io

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchDataTapTest {
    private val got = ArrayList<WatchDataTap.Read>()

    @After fun off() { WatchDataTap.sink = null }

    @Test fun nothingWithoutASinkAndASinkThatThrowsNeverReachesTheRead() {
        WatchDataTap.setting("DW-H5600", intArrayOf(0x43, 0x10))
        WatchDataTap.sink = { throw IllegalStateException("x") }
        WatchDataTap.setting("DW-H5600", intArrayOf(0x43, 0x10))
    }

    @Test fun aSettingReadIsHandedOnWithItsCode() {
        WatchDataTap.sink = { got += it }
        WatchDataTap.setting("DW-H5600", intArrayOf(0x43, 0x10, 0x27))
        assertEquals(1, got.size)
        assertEquals("setting", got[0].kind)
        assertEquals(0x43, got[0].code)
        assertEquals(listOf(0x43, 0x10, 0x27), got[0].raw.map { it.toInt() and 0xFF })
        assertEquals("DW-H5600", got[0].model)
    }

    @Test fun theConditionFlags() {
        val f = WatchDataTap.conditionFlags(byteArrayOf(0x28, 5, 21, 0, 0, 0, 0, 1, 1, 0))
        assertEquals(1, f["workoutFlag"])
        assertEquals(true, f["activityLogPending"])
        assertEquals(false, f["lifeLogPending"])
        assertTrue(WatchDataTap.conditionFlags(byteArrayOf(0x28)).values.all { it == null })
    }
}
