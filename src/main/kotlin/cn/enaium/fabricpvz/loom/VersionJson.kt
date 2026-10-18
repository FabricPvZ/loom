package cn.enaium.fabricpvz.loom

import groovy.json.JsonException
import groovy.json.JsonSlurper

import org.gradle.api.GradleException

/**
 * The version manifest an online package carries ({@code PvZ-Portable-<version>.json}, next to the
 * game jar): the same shape a Minecraft version json has, written by the game's own
 * {@code mcVersionJson} task.
 *
 * <p>It is what lets a mod build import its dependencies the way any other build does. The package's
 * jar is the game and nothing else on the class path is a file: reading this manifest turns the
 * {@code libraries[]} it lists into Maven coordinates, so the loader, Mixin, ASM, the Log4j stack and
 * LWJGL are resolved, cached and conflict-managed by Gradle instead of being copied around as jars.
 * Only the game - the one artifact no repository holds, because the launcher's real entry point is
 * published inside it - is a file the toolchain hands to the class path ({@code PvzModPlugin}).
 *
 * <p>Nothing here touches Gradle beyond the exception type: parsing and the native-library rules are
 * pure functions, so they are readable (and testable) in one place.
 *
 * <p>A library's {@code name} is a Maven coordinate with the natives as an ordinary classifier -
 * {@code org.lwjgl:lwjgl:3.4.3:natives-macos-arm64} - because that is exactly what the entry means:
 * a jar of shared libraries for one platform. The manifest lists every platform a release supports,
 * so a build picks the ones of the machine it runs on ({@link #nativeLibraries}), and only for a
 * runtime class path: a native jar carries no class a mod would compile against.
 */
internal data class VersionJson(
    /** The version id, e.g. {@code PvZ-Portable-1.1.0}: the name of the package's jar and manifest. */
    val id: String,

    /** The launcher entry point, {@code net.fabricmc.loader.impl.launch.knot.KnotClient}. */
    val mainClass: String,

    /** Every library of the manifest, in the order it lists them. */
    val libraries: List<Library>,
) {

    /**
     * One {@code libraries[]} entry: a Maven coordinate, with the classifier a natives entry carries.
     *
     * <p>{@code group}, {@code artifact} and {@code version} are what a dependency notation is made
     * of ({@link #coordinate}); {@code classifier} is {@code null} for the ordinary libraries (what a
     * mod's compile class path is made of) and {@code natives-<os>[-<arch>]} for the native bundles
     * ({@link #isNative}).
     */
    data class Library(
        val group: String,
        val artifact: String,
        val version: String,
        val classifier: String?,
    ) {

        /** The coordinate as a dependency notation, {@code group:artifact:version}, without a classifier. */
        val coordinate: String
            get() = "$group:$artifact:$version"

        /** Whether this entry is a bundle of native libraries rather than a jar of classes. */
        val isNative: Boolean
            get() = classifier != null && classifier.startsWith(NATIVES_PREFIX)

        /**
         * Whether this natives entry is the one for a platform: {@code natives-macos} is macOS on
         * x86-64 (the classifier LWJGL uses for it), {@code natives-macos-arm64} is macOS on arm64,
         * and the same pattern holds for the other systems.
         */
        fun matches(platform: Platform): Boolean {
            if (!isNative) {
                return false
            }
            val suffix = classifier!!.substring(NATIVES_PREFIX.length)
            return if (suffix.contains('-')) {
                suffix == platform.os + "-" + platform.arch
            } else {
                suffix == platform.os && platform.arch == X86_64
            }
        }
    }

    /**
     * A machine as the native classifiers name it: the operating system ({@code windows},
     * {@code macos} or {@code linux}) and the architecture ({@code x86_64} or {@code arm64}).
     */
    data class Platform(val os: String, val arch: String) {

        companion object {

            /** How the classifiers spell the architecture of this process, from {@code os.arch}. */
            fun archOf(architecture: String): String {
                return when (architecture.lowercase()) {
                    "aarch64", "arm64" -> ARM64
                    else -> X86_64
                }
            }

            /** How the classifiers spell the system of this process, from {@code os.name}. */
            fun osOf(name: String): String {
                val lower = name.lowercase()
                return when {
                    lower.contains("win") -> "windows"
                    lower.contains("mac") || lower.contains("darwin") -> "macos"
                    else -> "linux"
                }
            }

            /** The platform of the JVM that runs this build: what a run downloads natives for. */
            fun current(): Platform {
                return Platform(
                    osOf(System.getProperty("os.name", "")),
                    archOf(System.getProperty("os.arch", "")),
                )
            }
        }
    }

    /** Every library that is not a native bundle: what a mod compiles, tests and launches against. */
    fun javaLibraries(): List<Library> = libraries.filter { !it.isNative }

    /** The native bundles of {@code platform}: what a run of that machine needs to load LWJGL. */
    fun nativeLibraries(platform: Platform): List<Library> = libraries.filter { it.matches(platform) }

    companion object {

        /** The classifier prefix of a native bundle: {@code natives-macos-arm64}. */
        const val NATIVES_PREFIX = "natives-"

        /** The two architectures the classifiers spell. */
        const val X86_64 = "x86_64"
        const val ARM64 = "arm64"

        /** Parses a version manifest, failing with the shape it should have when it is not one. */
        fun parse(text: String, what: String): VersionJson {
            val root = try {
                JsonSlurper().parseText(text)
            } catch (exception: JsonException) {
                throw GradleException("$what is not readable JSON: ${exception.message}", exception)
            }
            if (root !is Map<*, *>) {
                throw GradleException("$what is not a version manifest: it is not a JSON object.")
            }
            val id = string(root, "id", what)
            val mainClass = string(root, "mainClass", what)
            val entries = root["libraries"]
            if (entries !is List<*>) {
                throw GradleException("$what has no libraries list: it is not a version manifest.")
            }
            val libraries = entries.mapIndexed { index, entry ->
                if (entry !is Map<*, *>) {
                    throw GradleException("$what lists library #$index as something that is not an object.")
                }
                library(entry, index, what)
            }
            return VersionJson(id, mainClass, libraries)
        }

        /** One entry of the libraries list, from the Maven coordinate its {@code name} is. */
        private fun library(entry: Map<*, *>, index: Int, what: String): Library {
            val name = string(entry, "name", what)
            val parts = name.split(':')
            if (parts.size != 3 && parts.size != 4) {
                throw GradleException("$what lists library #$index as \"$name\": it should be a Maven" +
                    " coordinate, group:artifact:version[:classifier].")
            }
            return Library(parts[0], parts[1], parts[2], if (parts.size == 4) parts[3] else null)
        }

        /** A required string field of an object, with a message that names what was being read. */
        private fun string(entry: Map<*, *>, field: String, what: String): String {
            val value = entry[field]
            if (value !is String || value.isBlank()) {
                throw GradleException("$what states no $field: it is not a version manifest.")
            }
            return value
        }
    }
}
