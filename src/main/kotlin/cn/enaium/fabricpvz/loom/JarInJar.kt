package cn.enaium.fabricpvz.loom

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.bundling.Jar

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Fabric Loom's {@code include(...)}: the jars a mod carries <em>inside</em> itself, and how Fabric
 * Loader finds them.
 *
 * <p>A mod that needs another mod to work - the API of this workspace is the case at hand - has two
 * ways of shipping it. It can ask the player to install both jars, or it can nest the other one in
 * its own jar: Fabric Loader reads the {@code jars} array of a mod's own {@code fabric.mod.json} and
 * loads every jar it names as a mod of its own, with the id, the version and the metadata that
 * <em>that</em> jar carries. That is how Fabric API ships: one
 * {@code fabric-api-<version>.jar} whose nested jars are its modules, so an installation has one
 * file to drop into {@code mods/} instead of one per module, and mods depend on one id instead of
 * eleven. The nested jars are ordinary jars: this mechanism copies them as they are under
 * {@link #NESTED_DIRECTORY} of the mod jar, so every module keeps its own id, version, entry points
 * and mixin configurations - Fabric Loader and not this plugin is what reads them.
 *
 * <p>Two ways in, one mechanism:
 * <ul>
 *   <li>a project that applies {@code cn.enaium.fabricpvz.loom} just writes
 *       {@code dependencies { add("include", …) }}: the plugin creates the configuration and wires
 *       the {@code jar} task with it ({@link PvzModPlugin});</li>
 *   <li>a project that is not a mod project of the toolchain but still ships a mod jar - the
 *       {@code fabric-api} aggregator, which has no game code and no {@code pvz} distribution, so it
 *       applies nothing - calls {@link #configuration(Project)} and {@link #include(Jar,
 *       FileCollection)} itself, which is the same code the plugin runs.</li>
 * </ul>
 *
 * <p>What the wiring does, and what it deliberately does not:
 * <ul>
 *   <li>the included jars go into the archive under {@link #NESTED_DIRECTORY}/{@code <their own file
 *       name>}: nothing is renamed, merged or remapped, and no nested jar's metadata is touched, so
 *       the id and the version a nested mod is loaded with stay its own. A name two included jars
 *       share is refused with both file paths named: a nested jar is addressed by its name inside
 *       the mod jar, so one of the two would be lost;</li>
 *   <li>the packaged {@code fabric.mod.json} gets the {@code jars} array listing exactly the entries
 *       the inclusion put in {@link #NESTED_DIRECTORY}, one entry each (the order is the resolved
 *       collection's, which is also the order the archive carries them in). The array is written
 *       <em>into the archive that was just built</em> (the {@code jar} task's own action has finished
 *       at that point), which is what keeps it from ever disagreeing with the archive: it lists what
 *       is in there, and an array that has been there before is replaced rather than appended to, so
 *       re-running the {@code jar} task can neither list a jar twice nor nest it twice - the task
 *       writes a fresh archive and the array is set, not added. An entry the metadata already listed
 *       for a file this inclusion provides is dropped in favour of the inclusion's own entry;</li>
 *   <li>a mod jar with nothing included is not touched at all: the wiring is only done when
 *       something is declared in the {@code include} configuration, so a project that includes
 *       nothing builds exactly the jar it built before this mechanism existed. A mod jar that has
 *       nested jars but no {@code fabric.mod.json} to list them in is a build failure with that
 *       message: Fabric Loader would never read them, so silently copying them in would only produce
 *       a jar that looks right and does nothing;</li>
 *   <li>everything else about the jar - its classes, its resources, its other metadata, its
 *       timestamped entries and their compression - is left as the {@code jar} task wrote it, and
 *       the artifact is still the one artifact that task produces: nothing is renamed, no
 *       {@code -dev} variant is published, and the publication this toolchain registers covers it as
 *       it covers any other mod jar. The file collection is resolved as a normal dependency set
 *       (a project dependency contributes that project's jar and the task dependency that builds
 *       it), exactly like the game distribution's jars ({@code PvzModPlugin#configurePvz}).</li>
 * </ul>
 *
 * <p>The Kotlin version keeps the class's shape (a final class that cannot be instantiated, with
 * the entry points reachable on the class name): the members live in the companion object and are
 * {@code @JvmStatic}, so {@code JarInJar.configuration(...)} reads the same from a build script and
 * from Java.
 */
class JarInJar private constructor() {

    companion object {

        /** The directory of a mod jar the nested jars live in, and what Fabric Loader reads. */
        const val NESTED_DIRECTORY = "META-INF/jars"

        /**
         * The name of the configuration the included jars are declared in: Fabric Loom's own name for it,
         * so a build script that knows Loom knows this one. A mod writes
         * {@code dependencies { add("include", "group:artifact:version") }}.
         */
        const val CONFIGURATION = "include"

        /** The mod metadata whose {@code jars} array lists the nested jars. */
        private const val METADATA = "fabric.mod.json"

        /**
         * The {@code include} configuration of {@code project}: the jars a mod jar carries inside itself.
         * Created if the project does not have one (an existing configuration of that name is used as it
         * is), so both a build script and {@link PvzModPlugin} can call this.
         */
        @JvmStatic
        fun configuration(project: Project): Configuration {
            val existing = project.configurations.findByName(CONFIGURATION)
            if (existing != null) {
                return existing
            }

            return project.configurations.create(CONFIGURATION) { configuration ->
                configuration.description = "The mods this mod carries inside its own jar (Fabric Loom's include(...)):" +
                    " nested into the mod jar under " + NESTED_DIRECTORY + "/ and listed in the packaged " +
                    METADATA + "'s jars array, from where Fabric Loader loads them with their own ids and" +
                    " versions. Declare them as any dependency: include a project, or a module coordinate."
                configuration.isCanBeConsumed = false
                configuration.isCanBeResolved = true
            }
        }

        /**
         * Wires {@code jar} to nest the jars of {@code included} and to list them in its packaged
         * {@link #METADATA}: what the {@code include} configuration means, and the whole of the
         * mechanism (see the class comment for what it does and does not touch).
         *
         * <p>The wiring is lazy in the included jars - they are resolved, and thus built, when the
         * {@code jar} task runs - and it is not conditional on anything being in the collection: a
         * collection that turns out to be empty leaves the archive exactly as it was, because both the
         * copy and the metadata step then do nothing.
         */
        @JvmStatic
        fun include(jar: Jar, included: FileCollection) {
            jar.from(included) { nested ->
                nested.into(NESTED_DIRECTORY)
                // A nested jar is addressed by its file name inside the mod jar, so a second jar of the
                // same name must not end up as a duplicate entry of the archive; listNestedJars() below
                // reports the two source files, which is the message a build needs.
                nested.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            }
            jar.doLast { listNestedJars(jar, included) }
        }

        /**
         * Lists the included jars in the archive's own {@code fabric.mod.json}, once the {@code jar}
         * task has written it. Nothing is written when nothing was included, so a jar without nested jars
         * is the jar the project would have built without this mechanism.
         */
        private fun listNestedJars(jar: Jar, included: FileCollection) {
            val byName = LinkedHashMap<String, File>()
            for (file in included.files) {
                val previous = byName.put(file.name, file)
                if (previous != null) {
                    throw GradleException(
                        "Two jars included in ${jar.path} would both be " +
                            "$NESTED_DIRECTORY/${file.name}: $previous and $file. A nested" +
                            " jar is addressed by its name inside the mod jar, so one of the two would be lost:" +
                            " rename one of them, or include only one."
                    )
                }
            }
            if (byName.isEmpty()) {
                return
            }

            val nested = ArrayList<String>()
            for (name in byName.keys) {
                nested.add("$NESTED_DIRECTORY/$name")
            }

            val archive = jar.archiveFile.get().asFile.toPath()
            if (!Files.isRegularFile(archive)) {
                throw GradleException(
                    "The ${jar.path} task did not write $archive, so the " +
                        "${nested.size} included jar(s) cannot be listed in its $METADATA."
                )
            }
            rewrite(archive, nested, jar.path)
        }

        /**
         * Rewrites {@code archive} with its {@link #METADATA} carrying the {@code jars} array of
         * {@code nested}: every other entry is copied through unchanged (bytes, order, timestamp and
         * compression method), the metadata entry is replaced by its re-serialized self plus the array,
         * and the result replaces the archive in a single move so an interrupted build never leaves a
         * half-written mod jar behind.
         */
        private fun rewrite(archive: Path, nested: List<String>, task: String) {
            val temporary = archive.resolveSibling(archive.fileName.toString() + ".included")
            var listed = false
            try {
                ZipFile(archive.toFile()).use { source ->
                    ZipOutputStream(BufferedOutputStream(Files.newOutputStream(temporary))).use { target ->
                        val entries = source.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.isDirectory) {
                                target.putNextEntry(copyOf(entry))
                                target.closeEntry()
                                continue
                            }

                            var content = source.getInputStream(entry).use { input -> input.readAllBytes() }
                            if (METADATA == entry.name) {
                                content = withJars(content, nested, task)
                                listed = true
                                // The rewritten metadata is written as a fresh entry: its size and CRC are new,
                                // so the entry's own compression is left to the stream.
                                target.putNextEntry(ZipEntry(entry.name))
                            } else {
                                target.putNextEntry(copyOf(entry))
                            }
                            target.write(content)
                            target.closeEntry()
                        }
                    }
                }
            } catch (exception: IOException) {
                delete(temporary)
                throw GradleException(
                    "Cannot rewrite the mod jar $archive of $task with its " +
                        "${nested.size} nested jar(s): ${exception.message}", exception
                )
            } catch (exception: GradleException) {
                delete(temporary)
                throw exception
            }

            if (!listed) {
                delete(temporary)
                throw GradleException(
                    "The mod jar $archive that $task built has no $METADATA" +
                        " in its root, so the ${nested.size} included jar(s) it carries in $NESTED_DIRECTORY" +
                        "/ would never be read: Fabric Loader loads a nested jar only when the mod's own $METADATA" +
                        " lists it in its jars array. A project that includes jars is a mod and needs its own " +
                        "$METADATA (mods written with this toolchain keep theirs in src/main/resources)."
                )
            }

            try {
                Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING)
            } catch (exception: IOException) {
                delete(temporary)
                throw GradleException(
                    "Cannot replace the mod jar $archive with its ${nested.size}" +
                        " nested jar(s): ${exception.message}", exception
                )
            }
        }

        /** A copy of a zip entry that keeps what the archive recorded about it: timestamp and compression. */
        private fun copyOf(entry: ZipEntry): ZipEntry {
            val copy = ZipEntry(entry.name)
            copy.time = entry.time
            if (entry.method == ZipEntry.STORED) {
                copy.method = ZipEntry.STORED
                copy.size = entry.size
                copy.compressedSize = entry.size
                copy.crc = entry.crc
            }
            return copy
        }

        /**
         * The metadata {@code metadata} with its {@code jars} array set to {@code nested}: the entries
         * the metadata already listed for a file of {@code nested} are dropped (the nested jars own that
         * answer now), and everything else it declared - including an entry shape this plugin does not
         * know - is kept as it was. The document is re-serialized, so the metadata in the mod jar is
         * written by this mechanism and not copied from its source.
         */
        private fun withJars(metadata: ByteArray, nested: List<String>, task: String): ByteArray {
            val parsed: Any
            try {
                parsed = JsonSlurper().parseText(String(metadata, StandardCharsets.UTF_8))
            } catch (exception: RuntimeException) {
                throw GradleException(
                    "The $METADATA in the jar $task built is not readable JSON (" +
                        exception.message + "), so the ${nested.size} included jar(s) cannot be listed" +
                        " in it.", exception
                )
            }
            if (parsed !is Map<*, *>) {
                throw GradleException(
                    "The $METADATA in the jar $task built is not a JSON object, so the " +
                        "${nested.size} included jar(s) cannot be listed in it."
                )
            }

            @Suppress("UNCHECKED_CAST")
            val document = parsed as MutableMap<String, Any?>
            val files = LinkedHashSet(nested)
            val jars = ArrayList<Any?>()
            val declared = document["jars"]
            if (declared is Collection<*>) {
                for (entry in declared) {
                    val file = if (entry is Map<*, *>) entry["file"] else null
                    if (file is String && files.contains(file)) {
                        continue
                    }
                    jars.add(entry)
                }
            }
            for (file in nested) {
                jars.add(mapOf("file" to file))
            }
            document["jars"] = jars

            return JsonOutput.prettyPrint(JsonOutput.toJson(document)).toByteArray(StandardCharsets.UTF_8)
        }

        /** Deletes a file, ignoring every failure: it is a best-effort cleanup of a temporary of this mechanism. */
        private fun delete(path: Path) {
            try {
                Files.deleteIfExists(path)
            } catch (ignored: IOException) {
                // The build is failing with a message about what went wrong; a leftover temporary of it is
                // overwritten by the next attempt.
            }
        }
    }
}
