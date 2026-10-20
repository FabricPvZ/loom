package cn.enaium.fabricpvz.loom

/**
 * The game the toolchain supplies, and nothing else: the version of the release asset the
 * {@code pvz} configuration defaults to, and where that asset lives.
 *
 * <p>A mod build that applies {@code cn.enaium.fabricpvz.loom} never repeats the game's coordinate:
 * the toolchain supplies the game distribution, and through it the game itself and the libraries
 * the version package's JSON next to the game jar names (the real Fabric Loader, Mixin, ASM, the
 * SLF4J/Log4j stack and LWJGL with its natives). Everything else a mod uses - the Kotlin plugin and
 * stdlib, JUnit, {@code fabric-language-kotlin}, the workspace's API - is the mod's own declaration
 * in its build file, exactly like a dependency of any other project. See {@code PvzModPlugin} and
 * {@code loom/README.md}.
 */
class PvzToolchain private constructor() {

    companion object {

        /** The Maven group of every artifact of this workspace. */
        const val GROUP = "cn.enaium.fabricpvz"

        /**
         * The game's version, i.e. the default of the toolchain's {@code pvz} dependency and what a
         * release of the game tags and names the asset after.
         */
        const val GAME_VERSION = "1.2.5"

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

        /** The Java release the game and every mod of the workspace is built with. */
        const val JAVA = 25
    }
}
