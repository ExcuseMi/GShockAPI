package org.avmedia.gshockapi.link

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every public call on a watch names its watch: it is a member of [org.avmedia.gshockapi.WatchApi] (reached by key and
 * made only by its link), takes a key, or is a deprecated single-watch call that only hands over to the one link's
 * WatchApi. Only the phone-level calls (scan, pairing, Bluetooth, the one BLE connection) are neither.
 */
class PublicApiKeyCheckTest {
    private val root = File("src/main/java/org/avmedia/gshockapi")

    private fun text(path: String): String = File(root, path).readText()

    /** The calls of the phone, not of one watch: scanning, pairing, Bluetooth and the one BLE connection. */
    private val phoneLevel = setOf(
        "waitForConnection", "isConnected", "isConnectedTo", "teardownConnection", "disconnect", "close", "isBluetoothEnabled",
        "validateBluetoothAddress", "preventReconnection", "associate", "disassociate", "getAssociationsWithNames",
        "getAssociations", "startObservingDevicePresence", "stopObservingDevicePresence", "scan", "stopScan", "startFallbackScan",
    )

    private class Fun(val name: String, val annotations: String, val params: String, val body: String)

    /** The non-private functions of [source]: name, the annotations right above, the parameters and what follows up to the next blank line. */
    private fun funs(source: String): List<Fun> =
        Regex("""^((?:[ \t]*@[^\n]*\n)*)[ \t]*(?!private )(?:override )?(?:suspend )?fun (\w+)\(([^)]*)\)([^\n]*(?:\n(?![ \t]*\n)[^\n]*)*)""", RegexOption.MULTILINE)
            .findAll(source).map { Fun(it.groupValues[2], it.groupValues[1], it.groupValues[3], it.groupValues[4]) }.toList()

    private fun unkeyed(file: String): List<String> =
        funs(text(file)).filter { f ->
            f.name !in phoneLevel && !f.annotations.contains("@Deprecated(SINGLE_WATCH)") && !Regex("""\bkey: String\b""").containsMatchIn(f.params)
        }.map { "$file: ${it.name}" }

    @Test fun theFunctionsAreFound() {
        assertTrue(funs(text("IGShockAPI.kt")).size > 50)
        assertTrue(funs(text("GShockAPI.kt")).size > 50)
    }

    @Test fun everyPublicCallOfTheApiNamesItsWatchOrIsDeprecated() {
        assertEquals("public calls without a watch key", emptyList<String>(), unkeyed("IGShockAPI.kt") + unkeyed("GShockAPI.kt"))
    }

    /** A deprecated call is a thin wrapper: it hands over to the one link's WatchApi and does nothing of its own. */
    @Test fun theDeprecatedCallsOnlyHandOverToTheOneLink() {
        val thick = funs(text("GShockAPI.kt")).filter { it.annotations.contains("@Deprecated(SINGLE_WATCH)") && it.name != "init" }
            .filter { f -> !Regex("""^[^{]*=\s*one\.${f.name}\(""").containsMatchIn(f.body) }.map { it.name }
        assertEquals("deprecated calls that do more than hand over", emptyList<String>(), thick)
        val init = funs(text("GShockAPI.kt")).single { it.name == "init" }.body
        assertTrue("init asks the one link's WatchApi", init.contains("one.initialize()"))
        assertTrue("one is the single-watch link's WatchApi", text("GShockAPI.kt").contains("private val one: WatchApi get() = org.avmedia.gshockapi.casio.WatchLink.api"))
    }

    /** The per-watch API is made only by its link and reaches only that link (no single-watch adapter, no global model). */
    @Test fun theWatchApiReachesOnlyItsLink() {
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val makers = sources.filter { Regex("""(?<!class )\bWatchApi\(""").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("a WatchApi made outside its link", listOf("WatchLink.kt"), makers)
        val api = text("WatchApi.kt")
        assertTrue("its constructor is internal", api.contains("class WatchApi internal constructor(private val link: WatchLink)"))
        val single = Regex(
            """\b(CachedIO|RawSettingIO|ButtonPressedIO|TimeIO|SettingsIO|H5600SettingsIO|TimeAdjustmentIO|AlarmsIO|TimerIO|EventsIO|""" +
                """WatchNameIO|ErrorIO|AppInfoIO|StepCounterIO|H5600HealthIO|H5600WorkoutIO|TideTransferIO|NotificationGate|""" +
                """WatchConditionIO|DstWatchStateIO|DstForWorldCitiesIO|WorldCitiesIO|HomeTimeIO|AppNotificationIO|Connection|WatchInfo)\.(?!(WatchConditionValue|ModelInfo)\b)|""" +
                """\bNotificationSender\.(?!Outcome\b)|\bWatchLink\.(?!isTheOne\b)\w+|\bIO\.(?!(WatchButton|DstState)\b)\w+""",
        )
        assertEquals("the single-watch adapters reached from WatchApi", emptyList<String>(), single.findAll(api).map { it.value }.toList())
        // The one exception: the single-watch link sends its messages through the single-watch dispatcher (actions not per link yet).
        assertEquals(1, Regex("""\bMessageDispatcher\.""").findAll(api).count())
        assertTrue(api.contains("if (org.avmedia.gshockapi.casio.WatchLink.isTheOne(link)) org.avmedia.gshockapi.casio.MessageDispatcher.sendToWatch(message)"))
    }

    /** The protocol calls run on the link they are given; a single-watch adapter only behind [org.avmedia.gshockapi.protocols.singleWatchOnly]. */
    @Test fun theProtocolCallsTakeTheirLink() {
        val codecs = setOf("extractKey", "unwrapPayload", "getWatchConditionRequest", "getTimerRequest", "getTimerSize")
        val protocol = text("protocols/WatchProtocol.kt")
        val body = protocol.substringAfter("interface WatchProtocol {")
        val bad = funs(body).filter { it.name !in codecs && !it.params.contains("link: WatchLink") }.map { it.name }
        assertEquals("protocol calls without their link", emptyList<String>(), bad)
        val allowed = setOf("ExerciseIO", "GwBx5600TimeIO", "MtgB1000TimeIO", "OceanusIO")
        File(root, "protocols").listFiles()!!.forEach { f ->
            val t = f.readText()
            val calls = Regex("""\b(\w+IO)\.(\w+)\(""").findAll(t).filter { it.groupValues[2] != "onReceived" }.map { it.groupValues[1] }.toSet()
            assertEquals("${f.name}: single-watch adapters", emptySet<String>(), calls - allowed)
            if ((calls intersect allowed).isNotEmpty()) assertTrue("${f.name}: a single-watch adapter without singleWatchOnly", t.contains("singleWatchOnly(link, "))
            assertTrue("${f.name}: the global model", !Regex("""\bWatchInfo\.""").containsMatchIn(t))
        }
    }
}
