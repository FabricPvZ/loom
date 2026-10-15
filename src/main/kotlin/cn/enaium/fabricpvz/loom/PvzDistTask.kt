package cn.enaium.fabricpvz.loom

import java.nio.file.Files
import java.nio.file.Path

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Obtains the game distribution this mod is built against, in the one shape every other part of the
 * toolchain consumes: the released layout's {@code lib/} (the launcher class path: the game, the
 * provider with the real Fabric Loader/Mixin/ASM, the SLF4J/Log4j stack and LWJGL with the native
 * libraries of every platform a release supports) - and the archive's {@code mods/}, when it has one,
 * unpacked with it. The directory holding them is this task's output, so it is what
 * {@code compileOnly}, the test class path and {@code pvzRunClasspath} are made of (of its
 * {@code lib/} jars: a distribution ships no mods, see {@code PvzModPlugin#distJars}), and every task
 * that consumes them depends on this one.
 *
 * <p>Which of the three sources it materializes is decided by {@code PvzModPlugin} from the project's
 * {@code pvz} configuration:
 * <ul>
 *   <li>the <b>release asset</b> of a version: downloaded into the cache of the Gradle user home
 *       ({@code caches/pvz}) and unpacked into {@code build/pvz/dist} - the task output, so the
 *       extraction is watched like any other build output;</li>
 *   <li>a <b>local zip</b> (the archive {@code distZip} writes, or the asset of a release directory):
 *       unpacked the same way, without a download;</li>
 *   <li>an <b>unpacked distribution directory</b> on disk, used exactly where it is, nothing copied.
 *       Its {@code lib/} and {@code mods/} are then not this task's outputs (they belong to whoever
 *       wrote them - a release unzipped anywhere, say) but its input, so the task only checks and
 *       reports which distribution the build is using.</li>
 * </ul>
 *
 * <p>Both steps are cheap to re-check: an extraction is skipped when the target's marker records the
 * very same zip, and a download is skipped when the cached asset is already there - which is what
 * lets an offline build of a version that has been used before work.
 */
@DisableCachingByDefault(
    because = "the distribution is taken from a release asset or a local archive and unpacked " +
        "into the build directory; a build cache entry would only duplicate a directory that is reused across " +
        "builds anyway, and the marker that makes the unpacking idempotent lives outside the declared outputs"
)
abstract class PvzDistTask : DefaultTask() {

    /** The version whose release carries the distribution (only when the source is a release). */
    @get:Input
    @get:Optional
    abstract val version: Property<String>

    /** The release asset URLs of that version, in the order they are tried. */
    @get:Input
    @get:Optional
    abstract val releaseUrls: ListProperty<String>

    /** The distribution zip to unpack, when the source is a local archive instead of a release. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val zip: RegularFileProperty

    /** The unpacked distribution the {@code pvz} configuration points at, when the source is a directory. */
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localDistribution: DirectoryProperty

    /** The {@code lib/} of the distribution: the game and the jars the launcher starts it with. */
    @get:OutputDirectory
    @get:Optional
    abstract val libDirectory: DirectoryProperty

    /**
     * The {@code mods/} of the distribution, when the archive has one: unpacked with the rest, but not
     * a class path entry of a mod build (see {@code PvzModPlugin#distJars}).
     */
    @get:OutputDirectory
    @get:Optional
    abstract val modsDirectory: DirectoryProperty

    /** Where a downloaded release asset is cached ({@code caches/pvz} of the Gradle user home). */
    @get:Internal
    abstract val cacheDirectory: DirectoryProperty

    /** Whether the build runs with {@code --offline}, in which case a missing asset is not downloaded. */
    @get:Internal
    abstract val offline: Property<Boolean>

    @TaskAction
    fun materialize() {
        if (localDistribution.isPresent) {
            val distribution = localDistribution.get().asFile.toPath()
            PvzDist.requireUsable(distribution, "The local distribution $distribution")
            report(distribution, "used where it is, nothing is copied")
            return
        }

        if (!libDirectory.isPresent) {
            throw GradleException(
                "The $path task has no distribution source: name one with the pvz" +
                    " configuration, pvz(\"${PvzToolchain.GAME}\") for a released version or" +
                    " pvz(files(\"<distribution>\")) for one on disk."
            )
        }
        val lib = libDirectory.get().asFile.toPath()
        val distribution: Path = lib.parent

        val source: String
        val zip: Path
        if (this.zip.isPresent) {
            zip = this.zip.get().asFile.toPath()
            if (!Files.isRegularFile(zip)) {
                throw GradleException("The local game distribution archive $zip does not exist.")
            }
            source = "local:$zip"
        } else {
            val version = this.version.get()
            zip = PvzDist.cachedAsset(cacheDirectory.get().asFile.toPath(), version)
            PvzDist.download(version, releaseUrls.get(), zip, offline.get(), logger)
            source = "release:$version"
        }

        val extracted = PvzDist.extract(zip, distribution, source, logger)
        PvzDist.requireUsable(distribution, "The distribution $distribution")
        report(
            distribution,
            if (extracted == 0) "already unpacked from ${zip.fileName}" else "unpacked from ${zip.fileName}"
        )
    }

    /** Says which distribution this build compiles and launches against: the answer to "where did the game come from". */
    private fun report(distribution: Path, how: String) {
        val jars = PvzDist.classpath(distribution)
        logger.info("pvz: {} jars from {} ({})", jars.size, distribution, how)
        logger.lifecycle(
            "pvz: compiling and launching against {} ({} jars) - {}",
            distribution, jars.size, how
        )
    }
}
