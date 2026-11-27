package org.avmedia.gshockapi.driver

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.avmedia.gshockapi.io.SettingWriteResult
import org.avmedia.gshockapi.link.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Family and model files merge in their order; bad files are refused with a reason. "GBX-H5600X" is made up. */
class ModelFilesTest {
    private val family = File("src/main/resources/" + Drivers.H5600_FILE).readText()
    private fun files() = ModelFiles(ModelFiles.families(listOf(H5600Family()))).also { it.register(family, fromLibrary = true).getOrThrow() }

    private val variant = """
        { "schema": 1, "kind": "watch-model", "id": "gbx-h5600x", "extends": "h5600",
          "models": [ { "name": "GBX-H5600X", "faces": 4 } ],
          "capabilities": [ "face.tide", { "id": "hr.measurement", "supported": false } ],
          "settings": [ { "key": "light.duration", "byte": 3 } ] }
    """

    private fun layer(id: String, models: String, setting: String) =
        """{ "schema": 1, "kind": "watch-model", "id": "$id", "extends": "h5600", $models "settings": [ $setting ] }"""

    @Test fun aModelFileAddsAModelAndChangesOnlyWhatItStates() {
        val files = files()
        assertTrue(files.register(variant).isSuccess)
        val x = files.fileFor("CASIO GBX-H5600X")!!
        assertEquals("h5600", x.family)
        assertTrue("hr.measurement" !in x.capabilities && "hr.measurement" !in x.settings)
        assertTrue("face.tide" in x.capabilities)
        val duration = x.settings.getValue("light.duration")
        assertEquals(3, duration.byte)
        assertEquals(0x0F, duration.mask) // inherited
        assertEquals(mapOf("short" to 0, "long" to 1), duration.values)
        assertEquals(files.fileFor("CASIO GBX-H5600")!!.settings.getValue("sound.mode"), x.settings.getValue("sound.mode"))
        // The family's own models are not touched by a model layer.
        val gbx = files.fileFor("CASIO GBX-H5600")!!
        assertEquals(2, gbx.settings.getValue("light.duration").byte)
        assertTrue("hr.measurement" in gbx.capabilities)
    }

    @Test fun layersMergeInTheirOrderAndLaterWins() {
        val files = files()
        val forGbx = """ "models": [ { "name": "GBX-H5600", "faces": 4 } ], """
        // Registered out of order on purpose: the order is by kind, not by arrival.
        files.register(layer("app-model", forGbx, """{ "key": "light.duration", "byte": 4 }""")).getOrThrow()
        files.register(layer("app-family", "", """{ "key": "light.duration", "mask": "0x07", "byte": 9 }""")).getOrThrow()
        files.register(layer("lib-model", forGbx, """{ "key": "light.duration", "byte": 3, "min_length": 8 }"""), fromLibrary = true).getOrThrow()
        val d = files.fileFor("CASIO GBX-H5600")!!.settings.getValue("light.duration")
        assertEquals(4, d.byte) // app model over app family over library model
        assertEquals(0x07, d.mask) // app family
        assertEquals(8, d.minLength) // library model
        assertEquals(9, files.fileFor("CASIO DW-H5600")!!.settings.getValue("light.duration").byte) // only the family layer
    }

    @Test fun badFilesAreRefusedWithAReason() {
        val files = files()
        assertTrue(files.register(variant.replace("\"schema\": 1", "\"schema\": 2")).exceptionOrNull()!!.message!!.contains("schema 2"))
        assertTrue(files.register(variant.replace("\"extends\": \"h5600\"", "\"extends\": \"b2100\"")).exceptionOrNull()!!.message!!.contains("unknown family"))
        assertTrue(files.register(layer("bad", "", """{ "key": "light.duration", "type": "weird" }""")).isFailure)
        assertTrue(files.register(family.replace("\"family\": \"h5600\"", "\"family\": \"b2100\"")).exceptionOrNull()!!.message!!.contains("no code driver"))
        assertTrue(files.register("not json").isFailure)
        assertNull(files.fileFor("CASIO GBX-H5600X")) // nothing refused was kept
    }

    @Test fun filesAreFoundOnTheClasspathByTheirIndex() {
        val dir = Files.createTempDirectory("watches").toFile()
        File(dir, "META-INF/gshockapi/watches").mkdirs()
        File(dir, "META-INF/gshockapi/watches/h5600.json").writeText(family)
        File(dir, "META-INF/gshockapi/watches/gbx-h5600x.json").writeText(variant)
        File(dir, ModelFiles.INDEX).writeText("# families first\nMETA-INF/gshockapi/watches/h5600.json\nMETA-INF/gshockapi/watches/gbx-h5600x.json\n")
        val files = ModelFiles(ModelFiles.families(listOf(H5600Family())))
        val results = files.registerClasspath(URLClassLoader(arrayOf(dir.toURI().toURL()), null))
        assertEquals(listOf("h5600", "gbx-h5600x"), results.map { it.getOrThrow() })
        assertTrue(files.driverFor("CASIO GBX-H5600X") is H5600Driver)
        dir.deleteRecursively()
    }

    private class NoWatch(override val name: String) : WatchConnection {
        override val state: StateFlow<LinkState> = MutableStateFlow(LinkState.READY)
        override suspend fun readFrame(code: Int): ByteArray? = null
        override suspend fun modify(
            code: Int, touched: Set<Int>, expectedBack: (ByteArray, ByteArray) -> ByteArray, matches: (ByteArray, ByteArray) -> Boolean, change: (ByteArray) -> ByteArray?,
        ) = SettingWriteResult.Failed("no watch")
    }

    @Test fun aRemovedCapabilityIsUnsupportedOnThatModelOnly() = runTest {
        val files = files().also { it.register(variant).getOrThrow() }
        val x = BoundWatch(NoWatch("CASIO GBX-H5600X"), files.driverFor("CASIO GBX-H5600X"))
        assertTrue(x.get(Keys.HR_MEASUREMENT) is DriverResult.Unsupported)
        val gbx = BoundWatch(NoWatch("CASIO GBX-H5600"), files.driverFor("CASIO GBX-H5600"))
        assertTrue(gbx.get(Keys.HR_MEASUREMENT) is DriverResult.Failed) // supported, but no watch answers
    }
}
