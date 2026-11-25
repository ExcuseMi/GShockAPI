package org.avmedia.gshockapi.link

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Nothing outside a link's job queue reaches the GATT read and write path, and nothing in the link layer is global:
 * each rule names the only files that may contain the call.
 */
class LinkSourceCheckTest {
    private fun sources(): List<File> =
        listOf("src/main/java", "../app/src/main/java").flatMap { root ->
            File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }

    private fun filesWith(pattern: Regex): Set<String> =
        sources().filter { pattern.containsMatchIn(it.readText()) }.map { it.name }.toSet()

    private fun only(what: String, pattern: Regex, allowed: Set<String>) {
        val bad = filesWith(pattern) - allowed
        assertTrue("$what outside $allowed: $bad", bad.isEmpty())
    }

    @Test fun sourcesAreFound() {
        assertTrue(sources().size > 50)
    }

    @Test fun onlyTheJobQueueHandsFramesToTheSequencer() =
        only("a frame put in a WatchQueue", Regex("""\.enqueue\(\s*[^)\s]"""), setOf("WatchJobQueue.kt"))

    @Test fun onlyTheSequencerWritesToATransport() =
        only("a transport write", Regex("""\btransport\.write\("""), setOf("WatchQueue.kt", "SingleWatchLink.kt"))

    @Test fun onlyTheLinkWritesToItsGatt() =
        only("a GATT write or subscription", Regex("""\bgatt\.(write|subscribe)\(|\bg\.subscribe\("""), setOf("WatchLink.kt"))

    @Test fun onlyTheLinksGattReachesTheManager() =
        only("a manager write or subscription", Regex("""\b(manager|bleManager)\??\.(writeChecked|writeAndWait|write|subscribe)\("""), setOf("Connection.kt"))

    /** The LifeLog, workout and tide transfers write only from their own job (its outbox and post), never as loose commands. */
    @Test fun theTransfersWriteOnlyFromTheirJob() {
        val transfers = setOf("H5600HealthIO.kt", "H5600WorkoutIO.kt", "H5600LifeLogReader.kt", "H5600WorkoutReader.kt", "H5600WorkoutTransfer.kt", "TideTransferIO.kt", "TideTransfer.kt", "JobWorkoutLink.kt", "H5600TideSender.kt")
        val bad = filesWith(Regex("""\bIO\.(writeCmd|writeChecked|request)\(|\bWatchLink\.write(Checked)?\(""")) intersect transfers
        assertTrue("a transfer writes outside its job: $bad", bad.isEmpty())
        assertEquals("the transfer files are found", transfers, sources().map { it.name }.toSet() intersect transfers)
    }

    @Test fun sequencersAndJobQueuesAreMadeOnlyByALink() {
        only("a WatchQueue made", Regex("""(?<!class )\bWatchQueue\("""), setOf("WatchLink.kt"))
        only("a WatchJobQueue made", Regex("""(?<!class )\bWatchJobQueue\("""), setOf("WatchLink.kt"))
    }

    @Test fun theSingleWatchLinkIsUsedOnlyByTheAdapterAndTheOneManager() {
        only("the single-watch link", Regex("""WatchLink\.link\b"""), setOf("SingleWatchLink.kt", "IGShockManager.kt"))
        only("a link made outside the registry", Regex("""(?<!class )\bWatchLink\(\s*"""), setOf("WatchLink.kt", "SingleWatchLink.kt"))
    }

    @Test fun theLinkLayerHasNoGlobalState() {
        val objects = File("src/main/java/org/avmedia/gshockapi/link").listFiles()!!.flatMap { f ->
            Regex("""^\s*(?:internal |private )?object (\w+)""", RegexOption.MULTILINE).findAll(f.readText()).map { it.groupValues[1] }.toList()
        }
        assertEquals("objects in the link layer", listOf("NoGatt"), objects)
        val topLevelVars = File("src/main/java/org/avmedia/gshockapi/link").listFiles()!!.flatMap { f ->
            Regex("""^(?:internal |private )?(?:@Volatile )?(?:lateinit )?var (\w+)""", RegexOption.MULTILINE).findAll(f.readText()).map { "${f.name}: ${it.groupValues[1]}" }.toList()
        }
        assertEquals("top-level vars in the link layer", emptyList<String>(), topLevelVars)
    }

    /** The connection setup and keep-alive are one per link: no object holds them and nobody else makes one. */
    @Test fun theConnectionSetupAndKeepAliveAreOnePerLink() {
        only("a global setup or keep-alive", Regex("""^\s*(?:internal |private )?object (ConnectionSetup|ConnectionKeepAlive)\b""", RegexOption.MULTILINE), emptySet())
        only("a connection context made", Regex("""(?<!class )\bConnectionContext\("""), setOf("WatchLink.kt"))
        only("a setup or keep-alive made", Regex("""(?<!class )\b(ConnectionSetup|ConnectionKeepAlive)\("""), setOf("ConnectionKeepAlive.kt"))
        only("a link writer made", Regex("""(?<!class )\bLinkWriter\("""), setOf("WatchLink.kt"))
    }

    /** Only the stateless parts (constants, the log, a pure helper) are reached by the class name; the rest through a link. */
    @Test fun theSetupAndKeepAliveHoldNoStateReachableByName() {
        val allowed = mapOf(
            "ConnectionSetup" to setOf("WATCHED_CODES", "SETUP_WAIT_MS", "writeBackValue"),
            "ConnectionKeepAlive" to setOf("CHECK", "SETUP", "log"),
        )
        val bad = sources().flatMap { f ->
            Regex("""(?<![\w.\[])(ConnectionSetup|ConnectionKeepAlive)(?:\.|::)(\w+)""").findAll(f.readText())
                .filter { it.groupValues[2] !in allowed.getValue(it.groupValues[1]) && it.groupValues[2] != "kt" }
                .map { "${f.name}: ${it.value}" }.toList()
        }
        assertTrue("reached by name: $bad", bad.isEmpty())
        val companions = listOf("ConnectionSetup.kt", "ConnectionKeepAlive.kt").map { name ->
            val text = sources().single { it.name == name }.readText()
            text.substringAfter("companion object {").let { body -> body.substring(0, closing(body)) }
        }
        companions.forEach { assertFalse("a var in a companion: $it", Regex("""\bvar\b""").containsMatchIn(it)) }
    }

    /**
     * The single-watch route to the one link's setup and keep-alive (the deprecated adapter): only the code that is not
     * per link yet. The list only gets shorter (step 4 moves these to their link).
     */
    @Test fun theSingleWatchContextIsUsedOnlyByTheCodeNotPerLinkYet() =
        only(
            "the single-watch connection context",
            Regex("""\bWatchLink\.connection\b"""),
            setOf("ConnectionSetup.kt"),
        )

    /** The per-link transfer and notification code reaches nothing of the single-watch adapter: only its own link. */
    @Test fun thePerLinkTransfersAndTextsReachOnlyTheirLink() {
        val perLink = setOf("H5600LifeLogReader.kt", "H5600WorkoutReader.kt", "H5600TideSender.kt", "LinkTransfers.kt", "LinkNotifications.kt")
        val single = Regex("""\bcasio\.WatchLink\b|import org\.avmedia\.gshockapi\.casio\.WatchLink\b|(?<![\w.])WatchLink\.(?!kt)\w+|\b(H5600HealthIO|H5600WorkoutIO|TideTransferIO|NotificationGate|WatchInfo|CachedIO|ButtonPressedIO|RawSettingIO)\.|\bNotificationSender\.(?!Outcome\b)""")
        val found = sources().filter { it.name in perLink }
        assertEquals("the per-link files are found", perLink, found.map { it.name }.toSet())
        val bad = found.filter { single.containsMatchIn(it.readText()) }.map { "${it.name}: ${single.find(it.readText())!!.value}" }
        assertTrue("the single-watch adapter reached from per-link code: $bad", bad.isEmpty())
        found.forEach { f ->
            Regex("""^\s*(?:internal |private )?object (\w+)""", RegexOption.MULTILINE).find(f.readText())?.let { fail("${f.name}: object ${it.groupValues[1]}") }
            f.readText().split("companion object {").drop(1).forEach { body ->
                assertFalse("${f.name}: a var in a companion", Regex("""\bvar\b""").containsMatchIn(body.substring(0, closing(body))))
            }
        }
        only("per-link transfers or texts made", Regex("""(?<!class )\b(LinkTransfers|LinkNotifications)\("""), setOf("WatchLink.kt"))
        only("a per-link reader or sender made", Regex("""(?<!class )\b(H5600LifeLogReader|H5600WorkoutReader|H5600TideSender|LinkNotificationSender|LinkNotificationGate)\("""), setOf("LinkTransfers.kt", "LinkNotifications.kt"))
    }

    /** The single-watch route to the one link's transfers and texts: only their deprecated adapters (step 5 removes them). */
    @Test fun theSingleWatchTransfersAndTextsAreUsedOnlyByTheirAdapters() =
        only(
            "the single-watch transfers or texts",
            Regex("""\bWatchLink\.(transfers|notifications)\b"""),
            setOf("H5600HealthIO.kt", "H5600WorkoutIO.kt", "TideTransferIO.kt", "NotificationSender.kt"),
        )

    /**
     * The per-link watch info, cache, button info and settings reads reach nothing of the single-watch adapters (only the
     * stateless model tables of [org.avmedia.gshockapi.WatchInfo]), hold no object or companion var, and are made only by
     * their link.
     */
    @Test fun thePerLinkWatchStateReachesOnlyItsLink() {
        val perLink = setOf("LinkWatchInfo.kt", "LinkCache.kt", "LinkButtonInfo.kt", "LinkRawSettings.kt", "LinkTime.kt")
        val single = Regex("""\bcasio\.WatchLink\b|import org\.avmedia\.gshockapi\.casio\.WatchLink\b|(?<![\w.])WatchLink\.(?!kt)\w+|\b(CachedIO|ButtonPressedIO|RawSettingIO|NotificationGate|TimeIO|DstWatchStateIO|DstForWorldCitiesIO|WorldCitiesIO|HomeTimeIO)\.|\bIO\.(?!(WatchButton|DstState)\b)\w+|\bWatchInfo\.(?!(State|ModelInfo|WatchModel|buildState)\b)\w+""")
        val found = sources().filter { it.name in perLink }
        assertEquals("the per-link files are found", perLink, found.map { it.name }.toSet())
        val bad = found.filter { single.containsMatchIn(it.readText()) }.map { "${it.name}: ${single.find(it.readText())!!.value}" }
        assertTrue("the single-watch adapter reached from per-link code: $bad", bad.isEmpty())
        found.forEach { f ->
            Regex("""^\s*(?:internal |private )?object (\w+)""", RegexOption.MULTILINE).find(f.readText())?.let { fail("${f.name}: object ${it.groupValues[1]}") }
            f.readText().split("companion object {").drop(1).forEach { body ->
                assertFalse("${f.name}: a var in a companion", Regex("""\bvar\b""").containsMatchIn(body.substring(0, closing(body))))
            }
        }
        only("per-link watch state made", Regex("""(?<!class )\b(LinkWatchInfo|LinkCache|LinkButtonInfo|LinkRawSettings|LinkTime)\("""), setOf("WatchLink.kt"))
        only("a link's setting wire made", Regex("""(?<!class )\bLinkSettingWire\("""), setOf("LinkRawSettings.kt"))
        only("a link's connection routes made", Regex("""(?<!class )\bConnectionRoutes\("""), setOf("ConnectionKeepAlive.kt"))
    }

    /** The single-watch route to the one link's watch state: only its deprecated adapters (step 5 removes them). */
    @Test fun theSingleWatchStateIsUsedOnlyByItsAdapters() =
        only(
            "the single-watch watch info, cache, button info, settings reads or routes",
            Regex("""\bWatchLink\.(watchInfo|cache|button|rawSettings|connectionRoutes|time)\b"""),
            setOf(
                "WatchInfo.kt", "CachedIO.kt", "ButtonPressedIO.kt", "RawSettingIO.kt",
                "TimeIO.kt", "DstWatchStateIO.kt", "DstForWorldCitiesIO.kt", "WorldCitiesIO.kt", "HomeTimeIO.kt",
            ),
        )

    /**
     * A link's frames go through its own dispatcher to its own objects: the dispatcher reaches nothing of the single-watch
     * adapters and is made only by its link; the single-watch [org.avmedia.gshockapi.casio.MessageDispatcher] only hands
     * the frame to the one link's dispatcher; the H5600 routing reaches the transfers, the gate and the setup of the link
     * it is given, never the single-watch ones.
     */
    @Test fun aLinksFramesGoThroughItsOwnDispatcher() {
        val single = Regex("""\bcasio\.WatchLink\b|(?<![\w.])WatchLink\.(?!kt)\w+|\b(H5600HealthIO|H5600WorkoutIO|TideTransferIO|NotificationGate|CachedIO|ButtonPressedIO|RawSettingIO|TimeIO|WatchInfo)\.""")
        val dispatcher = sources().single { it.name == "LinkDispatcher.kt" }.readText()
        assertFalse("the link dispatcher reaches the single-watch adapter: ${single.find(dispatcher)?.value}", single.containsMatchIn(dispatcher))
        only("a link dispatcher made", Regex("""(?<!class )\bLinkDispatcher\("""), setOf("WatchLink.kt"))
        only("the single-watch dispatcher", Regex("""\bWatchLink\.dispatcher\b"""), setOf("MessageDispatcher.kt"))
        val message = sources().single { it.name == "MessageDispatcher.kt" }.readText()
        Regex("""\.offer\(|dataReceivedHandlers|extractKey|linkHandlers""").find(message)?.let { fail("MessageDispatcher routes by itself: ${it.value}") }
        val h5600 = sources().single { it.name == "H5600Protocol.kt" }.readText()
        val h5600Single = Regex("""\bcasio\.WatchLink\b|(?<![\w.])WatchLink\.(?!kt)\w+|\b(H5600WorkoutIO|TideTransferIO|NotificationGate)[.:]|\bH5600HealthIO(\.(?!request\b)|::)""")
        h5600Single.find(h5600)?.let { fail("H5600Protocol reaches the single-watch transfers, gate or setup: ${it.value}") }
    }

    /** The single-watch time code holds nothing of a watch: no answer it waits for, no time zone; the link's [org.avmedia.gshockapi.io.LinkTime] does. */
    @Test fun theSingleWatchTimeHoldsNoState() {
        val adapters = setOf("TimeIO.kt", "DstWatchStateIO.kt", "DstForWorldCitiesIO.kt", "WorldCitiesIO.kt", "HomeTimeIO.kt")
        val found = sources().filter { it.name in adapters }
        assertEquals("the time adapters are found", adapters, found.map { it.name }.toSet())
        val state = Regex("""CompletableDeferred|\bvar\b|\bCachedIO\.|\bIO\.(request|writeCmd)\(|Connection\.sendMessage""")
        val bad = found.filter { state.containsMatchIn(it.readText()) }.map { "${it.name}: ${state.find(it.readText())!!.value}" }
        assertTrue("state or a write in the single-watch time code: $bad", bad.isEmpty())
    }

    /**
     * The per-link settings, alarms, timer, reminders, watch status, app information and activity record reach nothing
     * of the single-watch adapters (only their stateless codecs and model tables), hold no object or companion var and
     * are made only by their link's [org.avmedia.gshockapi.io.LinkIO], which only the link makes.
     */
    @Test fun thePerLinkIOReachesOnlyItsLink() {
        val perLink = setOf(
            "LinkIO.kt", "LinkAnswer.kt", "LinkWatchStatus.kt", "LinkAppInfo.kt", "LinkBasicSettings.kt", "LinkTimeAdjustment.kt",
            "LinkH5600Settings.kt", "LinkAlarms.kt", "LinkTimer.kt", "LinkEvents.kt", "LinkStepCounter.kt",
        )
        val single = Regex(
            """\bcasio\.WatchLink\b|import org\.avmedia\.gshockapi\.casio\.WatchLink\b|(?<![\w.])WatchLink\.(?!kt)\w+|""" +
                """\b(CachedIO|RawSettingIO|ButtonPressedIO|TimeIO|SettingsIO|H5600SettingsIO|TimeAdjustmentIO|AlarmsIO|TimerIO|EventsIO|WatchNameIO|ErrorIO|AppInfoIO|StepCounterIO|MessageDispatcher|Connection)\.|""" +
                """\bWatchConditionIO\.(?!WatchConditionValue\b)|\bIO\.(?!(WatchButton|DstState)\b)\w+|\bWatchInfo\.(?!(State|ModelInfo|WatchModel|buildState)\b)\w+""",
        )
        val found = sources().filter { it.name in perLink }
        assertEquals("the per-link files are found", perLink, found.map { it.name }.toSet())
        val bad = found.filter { single.containsMatchIn(it.readText()) }.map { "${it.name}: ${single.find(it.readText())!!.value}" }
        assertTrue("the single-watch adapter reached from per-link code: $bad", bad.isEmpty())
        found.forEach { f ->
            Regex("""^\s*(?:internal |private )?object (\w+)""", RegexOption.MULTILINE).find(f.readText())?.let { fail("${f.name}: object ${it.groupValues[1]}") }
            f.readText().split("companion object {").drop(1).forEach { body ->
                assertFalse("${f.name}: a var in a companion", Regex("""\bvar\b""").containsMatchIn(body.substring(0, closing(body))))
            }
        }
        val topLevelVars = found.flatMap { f ->
            Regex("""^(?:@Volatile )?(?:internal |private )?(?:@Volatile )?(?:lateinit )?var (\w+)""", RegexOption.MULTILINE).findAll(f.readText()).map { "${f.name}: ${it.groupValues[1]}" }.toList()
        }
        assertEquals("top-level vars in the per-link IO", emptyList<String>(), topLevelVars)
        only("a link's IO made", Regex("""(?<!class )\bLinkIO\("""), setOf("WatchLink.kt"))
        only(
            "a per-link IO object made",
            Regex("""(?<!class )\b(LinkWatchStatus|LinkAppInfo|LinkBasicSettings|LinkTimeAdjustment|LinkH5600Settings|LinkAlarms|LinkTimer|LinkEvents|LinkStepCounter)\("""),
            setOf("LinkIO.kt"),
        )
        only("an answer a read waits for made", Regex("""(?<!class )\bLinkAnswer(<[^>]*>)?\("""), perLink + "LinkTime.kt")
    }

    /** The single-watch IO objects hold nothing of a watch: no answer they wait for, no write of their own; the link's [org.avmedia.gshockapi.io.LinkIO] does. */
    @Test fun theSingleWatchIOHoldsNoState() {
        val adapters = setOf(
            "SettingsIO.kt", "H5600SettingsIO.kt", "TimeAdjustmentIO.kt", "AlarmsIO.kt", "TimerIO.kt", "EventsIO.kt",
            "WatchNameIO.kt", "WatchConditionIO.kt", "ErrorIO.kt", "AppInfoIO.kt", "StepCounterIO.kt",
        )
        val found = sources().filter { it.name in adapters }
        assertEquals("the IO adapters are found", adapters, found.map { it.name }.toSet())
        val state = Regex(
            """CompletableDeferred|LinkAnswer|@Volatile|private var|\b(CachedIO|RawSettingIO)\.|\bIO\.(request|writeCmd|writeChecked)\(|""" +
                """Connection\.sendMessage|\bWatchInfo\.(?!ModelInfo\b)\w+""",
        )
        val bad = found.filter { state.containsMatchIn(it.readText()) }.map { "${it.name}: ${state.find(it.readText())!!.value}" }
        assertTrue("state, a write or the global model in the single-watch IO code: $bad", bad.isEmpty())
        only("the single-watch IO", Regex("""\bWatchLink\.io\b"""), adapters)
    }

    /**
     * Outbound messages and notifications go to a link's own dispatcher: the single-watch dispatcher has no table of the
     * per-link objects (only the actions not per link yet), and nothing picks a link's protocol from the global model.
     */
    @Test fun aLinksMessagesAndProtocolAreItsOwn() {
        val message = sources().single { it.name == "MessageDispatcher.kt" }.readText()
        Regex("""\b(AlarmsIO|EventsIO|SettingsIO|TimeAdjustmentIO|TimerIO|TimeIO)\b""").find(message)?.let { fail("MessageDispatcher sends by itself: ${it.value}") }
        only("the global model's protocol picked for a frame", Regex("""\bWatchInfo\.protocol\b"""), setOf("CommandSpec.kt"))
        val dispatcher = sources().single { it.name == "LinkDispatcher.kt" }.readText()
        assertTrue("a link's frames use its own model's protocol", dispatcher.contains("protocol: WatchProtocol = link.watchInfo.info.protocol"))
    }

    private fun closing(body: String): Int {
        var depth = 1
        body.forEachIndexed { i, c ->
            if (c == '{') depth++
            if (c == '}' && --depth == 0) return i
        }
        return body.length
    }
}
