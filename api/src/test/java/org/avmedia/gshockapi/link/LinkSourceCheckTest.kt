package org.avmedia.gshockapi.link

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        val transfers = setOf("H5600HealthIO.kt", "H5600WorkoutIO.kt", "H5600WorkoutTransfer.kt", "TideTransferIO.kt", "TideTransfer.kt", "JobWorkoutLink.kt")
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
            setOf(
                "GShockAPI.kt", "IGShockManager.kt", "H5600Protocol.kt", "NotificationSubscriptions.kt",
                "RawSettingIO.kt", "H5600WorkoutIO.kt", "H5600HealthIO.kt",
            ),
        )

    private fun closing(body: String): Int {
        var depth = 1
        body.forEachIndexed { i, c ->
            if (c == '{') depth++
            if (c == '}' && --depth == 0) return i
        }
        return body.length
    }
}
