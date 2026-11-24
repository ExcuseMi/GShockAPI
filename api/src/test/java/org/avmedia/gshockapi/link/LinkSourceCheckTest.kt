package org.avmedia.gshockapi.link

import java.io.File
import org.junit.Assert.assertEquals
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
    }
}
