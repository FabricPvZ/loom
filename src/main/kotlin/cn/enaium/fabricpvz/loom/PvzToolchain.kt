package cn.enaium.fabricpvz.loom

/**
 * The one place the mod toolchain's versions live: a mod build that applies
 * {@code cn.enaium.fabricpvz.loom} never repeats a version or a coordinate of the loader, the
 * Mixin runtime, ASM, the API or the game.
 *
 * <p>Only what the toolchain <em>itself</em> provides lives here. The loader, Mixin, ASM, the
 * Log4j stack, LWJGL and the game are not named at all: they are the jars of the game distribution
 * ({@code lib/} of the release asset {@code pvz-portable-<version>.zip}), which is what the
 * launcher runs - so a mod compiles against exactly the jars it is loaded with, by construction
 * rather than by keeping two version lists in sync. The distribution carries no mods (a mod jar in
 * a launch class path is inert, and what a player installs belongs in {@code mods/}), so the one
 * mod of the workspace a mod build needs - the API aggregate - is a coordinate of this toolchain
 * ({@link #API}) instead: the API ships as one jar whose nested jars are its modules, and the
 * toolchain puts that one jar where the distribution used to carry the five. See
 * {@code PvzModPlugin} and {@code loom/README.md}.
 */
class PvzToolchain private constructor() {

    companion object {

        /** The Maven group of every artifact of this workspace. */
        const val GROUP = "cn.enaium.fabricpvz"

        /**
         * The game's version (1.0.0, the version every artifact of this workspace carries), i.e. the
         * default of the toolchain's {@code pvz} dependency and what a release of the game tags and
         * names the asset after.
         */
        const val GAME_VERSION = "1.2.2"

        /** The game's Maven coordinates, i.e. the notation the {@code pvz} configuration takes. */
        const val GAME = "$GROUP:pvz-portable:$GAME_VERSION"

        /** The artifact name of that coordinate: the only coordinate {@code pvz(...)} accepts. */
        const val GAME_ARTIFACT = "pvz-portable"

        /**
         * The repository whose GitHub releases carry the game distributions: the game's own repository
         * in the org every project of this workspace is published under, so a mod that names a version
         * resolves the asset the game's own release carries. It is a constant here and overridable per
         * build with the {@code pvz.release.baseUrl} Gradle property (see {@code PvzDist#releaseUrls}).
         */
        const val GAME_REPOSITORY = "FabricPvZ/pvz-portable"

        /** The base of the release asset URLs, i.e. what {@code pvz.release.baseUrl} overrides. */
        const val RELEASE_BASE_URL = "https://github.com/$GAME_REPOSITORY/releases/download"

        /**
         * The API's own version - the modules of this workspace version on their own, not with the game.
         * It is the newest published API, the one a mod compiled with this toolchain links against; a
         * mod's own {@code fabric.mod.json} is what states the range it requires, and the mods of the
         * workspace all require {@code fabric-api >= 1.0.0}.
         */
        const val API_VERSION = "1.2.0"

        /** The aggregate's Maven artifact id, i.e. the mod id a mod requires and the jar it is loaded from. */
        const val API_ARTIFACT = "fabric-api"

        /**
         * The workspace's API as one mod jar: the aggregate, whose <em>nested</em> jars are the feature
         * modules (see {@link JarInJar}), which is how Fabric API ships and why a mod depends on one id
         * instead of one per module.
         *
         * <p>The toolchain puts it on the class paths a mod builds and runs against (see
         * {@code PvzModPlugin#configureApi}): the game distribution is the game only - the API is a mod
         * like any other, and the development runs of this toolchain take it from the class path exactly
         * as an installation takes it from {@code mods/}.
         */
        const val API = "$GROUP:$API_ARTIFACT:$API_VERSION"

        /** JUnit, for a mod's own tests (the end-to-end harness included). */
        const val JUNIT = "6.1.3"

        /**
         * Kotlin, for a mod written in Kotlin: the version of the Kotlin Gradle plugin the toolchain
         * applies (declared in {@code loom/build.gradle.kts}, which has to state it to put the plugin on
         * its own class path) and of the {@code kotlin-stdlib} a mod compiles against.
         *
         * <p>Kotlin 2.4 is the first release line whose compiler accepts {@code jvmTarget = 25} and can
         * read the game's Java 25 class files (major version 69), so it is the floor here, not a
         * preference.
         */
        const val KOTLIN = "2.4.21"

        /**
         * Fabric Language Kotlin, the real language module of Fabric, as the Kotlin mods of this
         * workspace run on it: the toolchain puts this mod on a mod's launcher class path (see
         * {@code PvzModPlugin}), the mods declare it in their {@code fabric.mod.json}
         * ({@code "fabric-language-kotlin"}), and the Kotlin runtime the mods link against is the
         * {@code kotlin-stdlib} nested in <em>its</em> jar.
         *
         * <p>The version is the {@code +kotlin.} line that matches {@link #KOTLIN} exactly: a mod is
         * compiled against {@code kotlin-stdlib 2.4.21} here, so it must run against a language module
         * that carries that stdlib and not an older one. The artifact is
         * {@code net.fabricmc:fabric-language-kotlin:<version>} on {@code https://maven.fabricmc.net/}
         * (which every mod build already has as a repository, next to Maven Central), so the resolution
         * is one coordinate, not a jar copied by hand.
         */
        const val FABRIC_LANGUAGE_KOTLIN = "1.14.2+kotlin.2.4.21"

        /** The language module's coordinates, as a mod build resolves them. */
        const val FABRIC_LANGUAGE_KOTLIN_COORDINATE =
            "net.fabricmc:fabric-language-kotlin:$FABRIC_LANGUAGE_KOTLIN"

        /**
         * The Kotlin standard library a mod compiles against. It is {@code compileOnly} - a mod jar
         * bundles no dependency and its POM declares none - and the mod's <em>runtime</em> copy is
         * {@link #FABRIC_LANGUAGE_KOTLIN}'s nested one, so the workspace has exactly one owner of
         * {@code kotlin.*} on a launch class path.
         */
        const val KOTLIN_STDLIB = "org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN"

        /** The Java release the mods, the API and the game are all built with. */
        const val JAVA = 25
    }
}
