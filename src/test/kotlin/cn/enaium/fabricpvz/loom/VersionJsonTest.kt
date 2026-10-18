package cn.enaium.fabricpvz.loom

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

import org.junit.jupiter.api.io.TempDir

import org.gradle.api.GradleException
import org.gradle.api.logging.Logging

/**
 * The two pure rules of a version manifest: reading it, and which of its native libraries belong to
 * one platform. Both are what a mod build's class path is made of, so a mistake in either shows up
 * as a run that cannot load LWJGL - or, worse, as one that silently loads another machine's natives.
 */
class VersionJsonTest {

    private val manifest = """
        {
          "id": "PvZ-Portable-1.2.1",
          "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
          "libraries": [
            { "name": "org.ow2.asm:asm:9.10.1" },
            { "name": "net.fabricmc:fabric-loader:0.19.5" },
            { "name": "org.lwjgl:lwjgl:3.4.3" },
            { "name": "org.lwjgl:lwjgl:3.4.3:natives-windows" },
            { "name": "org.lwjgl:lwjgl:3.4.3:natives-windows-arm64" },
            { "name": "org.lwjgl:lwjgl:3.4.3:natives-linux" },
            { "name": "org.lwjgl:lwjgl:3.4.3:natives-linux-arm64" },
            { "name": "org.lwjgl:lwjgl:3.4.3:natives-macos" },
            { "name": "org.lwjgl:lwjgl:3.4.3:natives-macos-arm64" }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses the id, the entry point and every library`() {
        val json = VersionJson.parse(manifest, "the test manifest")
        assertEquals("PvZ-Portable-1.2.1", json.id)
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", json.mainClass)
        assertEquals(9, json.libraries.size)
    }

    @Test
    fun `classifies a library by its classifier`() {
        val json = VersionJson.parse(manifest, "the test manifest")
        val asm = json.libraries.first { it.artifact == "asm" }
        assertEquals(null, asm.classifier)
        assertEquals("org.ow2.asm:asm:9.10.1", asm.coordinate)
        assertTrue(!asm.isNative)

        val natives = json.libraries.first { it.classifier == "natives-macos-arm64" }
        assertTrue(natives.isNative)
        assertEquals("org.lwjgl:lwjgl:3.4.3", natives.coordinate)
    }

    @Test
    fun `picks the natives of a platform, and only those`() {
        val json = VersionJson.parse(manifest, "the test manifest")
        fun natives(os: String, arch: String) =
            json.nativeLibraries(VersionJson.Platform(os, arch)).mapNotNull { it.classifier }.sorted()

        // The unsuffixed classifier of a system is its x86-64 build - that is LWJGL's convention, and
        // the reason the arch of a machine has to be part of the match and not taken for granted.
        assertEquals(listOf("natives-macos"), natives("macos", VersionJson.X86_64))
        assertEquals(listOf("natives-macos-arm64"), natives("macos", VersionJson.ARM64))
        assertEquals(listOf("natives-linux"), natives("linux", VersionJson.X86_64))
        assertEquals(listOf("natives-linux-arm64"), natives("linux", VersionJson.ARM64))
        assertEquals(listOf("natives-windows"), natives("windows", VersionJson.X86_64))
        assertEquals(listOf("natives-windows-arm64"), natives("windows", VersionJson.ARM64))
    }

    @Test
    fun `java libraries are everything that is not a native bundle`() {
        val json = VersionJson.parse(manifest, "the test manifest")
        val java = json.javaLibraries().map { it.coordinate }
        assertEquals(listOf("org.ow2.asm:asm:9.10.1", "net.fabricmc:fabric-loader:0.19.5", "org.lwjgl:lwjgl:3.4.3"), java)
    }

    @Test
    fun `maps a jvm's properties onto the classifier spelling`() {
        assertEquals("macos", VersionJson.Platform.osOf("Mac OS X"))
        assertEquals("windows", VersionJson.Platform.osOf("Windows 11"))
        assertEquals("linux", VersionJson.Platform.osOf("Linux"))
        assertEquals(VersionJson.ARM64, VersionJson.Platform.archOf("aarch64"))
        assertEquals(VersionJson.ARM64, VersionJson.Platform.archOf("arm64"))
        assertEquals(VersionJson.X86_64, VersionJson.Platform.archOf("amd64"))
        assertEquals(VersionJson.X86_64, VersionJson.Platform.archOf("x86_64"))
    }

    @Test
    fun `rejects what is not a version manifest`() {
        assertFailsWith<GradleException> { VersionJson.parse("not json", "it") }
        assertFailsWith<GradleException> { VersionJson.parse("[]", "it") }
        assertFailsWith<GradleException> { VersionJson.parse("""{"id":"x","mainClass":"y"}""", "it") }
        assertFailsWith<GradleException> {
            VersionJson.parse("""{"id":"x","mainClass":"y","libraries":[{"name":"broken"}]}""", "it")
        }
    }
}

/**
 * The other half of reading a version package: unpacking an archive has to put its files where the
 * manifest says they are. A jar that the manifest names but the extraction renamed - or moved into
 * another directory - fails a build with a class path entry that does not exist, which is what these
 * two cover: the package layout and the application layout.
 */
class PvzDistTest {

    private val logger = Logging.getLogger("pvz-loom-test")

    private fun zip(directory: Path, name: String, entries: Map<String, ByteArray>): Path {
        val zip = directory.resolve(name)
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            for ((entryName, bytes) in entries) {
                out.putNextEntry(ZipEntry(entryName))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun `unpacking a version package keeps the file names the manifest states`(@TempDir directory: Path) {
        val manifest = """{"id":"PvZ-Portable-1.2.1","mainClass":"x","libraries":[]}"""
        val archive = zip(
            directory, "pvz-portable-1.2.1-online.zip",
            mapOf(
                "PvZ-Portable-1.2.1/PvZ-Portable-1.2.1.json" to manifest.toByteArray(),
                "PvZ-Portable-1.2.1/PvZ-Portable-1.2.1.jar" to ByteArray(8),
            ),
        )
        val dist = directory.resolve("dist")
        assertEquals(2, PvzDist.extract(archive, dist, "test:${archive}", logger))

        val packageContent = PvzDist.packageOfZip(archive)!!
        assertEquals("PvZ-Portable-1.2.1/PvZ-Portable-1.2.1.jar", packageContent.gameJar)
        assertTrue(Files.isRegularFile(dist.resolve(packageContent.gameJar)))
        assertEquals(VersionJson.parse(packageContent.manifest, "it").id, "PvZ-Portable-1.2.1")
        // The game jar of the unpacked distribution is the one the manifest names - the path a build
        // hands to the class path.
        assertEquals("PvZ-Portable-1.2.1.jar", PvzDist.gameJar(dist)!!.fileName.toString())

        // Unpacking again is a no-op: the marker records exactly what this archive produces.
        assertEquals(0, PvzDist.extract(archive, dist, "test:${archive}", logger))
    }

    @Test
    fun `unpacking an application-style archive keeps lib and mods`(@TempDir directory: Path) {
        val archive = zip(
            directory, "pvz-portable-1.2.1-offline.zip",
            mapOf(
                "pvz-portable-1.2.1/lib/game.jar" to ByteArray(4),
                "pvz-portable-1.2.1/lib/lwjgl.jar" to ByteArray(4),
                "pvz-portable-1.2.1/mods/something.jar" to ByteArray(4),
            ),
        )
        val dist = directory.resolve("dist")
        assertEquals(3, PvzDist.extract(archive, dist, "test:${archive}", logger))

        assertEquals(null, PvzDist.packageOfZip(archive))
        assertEquals(
            listOf("game.jar", "lwjgl.jar"),
            PvzDist.classpath(dist).map { it.fileName.toString() },
        )
        assertTrue(PvzDist.classpath(dist).none { it.parent.fileName.toString() == PvzDist.MODS })
        assertEquals(4L, Files.size(dist.resolve("lib/game.jar")))
    }

    @Test
    fun `an archive that is not a distribution fails with the layouts it could have`(@TempDir directory: Path) {
        val archive = zip(directory, "random.zip", mapOf("readme.txt" to "hello".toByteArray()))
        val failure = assertFailsWith<GradleException> {
            PvzDist.extract(archive, directory.resolve("dist"), "test:$archive", logger)
        }
        assertTrue(failure.message!!.contains("neither a version package"))
    }
}
