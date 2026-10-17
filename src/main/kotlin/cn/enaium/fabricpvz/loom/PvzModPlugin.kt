package cn.enaium.fabricpvz.loom

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.EnumSet
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.Locale

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * The mod toolchain of this workspace: applied as {@code cn.enaium.fabricpvz.loom}, one line, and
 * the project is a Fabric mod of the PvZ port.
 *
 * <p>The game side of a mod comes from a <b>distribution</b> and from nowhere else: the
 * {@code pvz} configuration names it ({@link #PVZ_CONFIGURATION}), {@link PvzDistTask} obtains it,
 * and its jars are what the mod compiles against and what the loader runs. A distribution is the
 * release asset {@code pvz-portable-<version>.zip} the game's release attaches (the
 * {@code application} plugin's layout: {@code lib/} with the game, the provider and through it the
 * real Fabric Loader, Mixin, ASM, the SLF4J/Log4j stack and LWJGL with the native libraries of
 * every platform it runs on) - or a distribution on disk for testing. The toolchain therefore does
 * not inject the game, the provider, the loader, Mixin or ASM as coordinates: naming them would be
 * a second version list to keep in sync with the jars the launcher actually loads, and the
 * distribution already <em>is</em> that list. See {@link PvzDist} and {@code loom/README.md}.
 *
 * <p>Its {@code mods/} is the one thing a distribution does not have: a game distribution is a
 * game, and a mod jar on a launch class path is inert outside a development run, so what the player
 * installs belongs in the installation's own {@code mods/} directory and nowhere else. The one mod
 * a mod build needs through the toolchain - the workspace's API, which every mod of the workspace
 * requires - is therefore a coordinate of the toolchain ({@link PvzToolchain#API}), put on the
 * class paths a build compiles, tests and runs against by {@link #configureApi}: the aggregate jar,
 * whose nested jars are the four feature modules ({@link JarInJar}), so one artifact supplies the
 * five mods of the API on every one of those class paths, exactly as one jar in {@code mods/} does
 * in an installation.
 *
 * <p>What it supplies besides that:
 * <ul>
 *   <li>{@code compileOnly}: the jars of the distribution ({@code lib/}) and the Kotlin stdlib -
 *       the mod compiles against them and bundles none of them - plus the API, with its modules
 *       ({@link #configureApi});</li>
 *   <li>the same jars for {@code testImplementation}, plus {@code fabric-language-kotlin} and JUnit
 *       for {@code testRuntimeOnly}, so a mod's end-to-end test can start the real Fabric Loader in
 *       a second JVM exactly like the launcher does;</li>
 *   <li>the {@code runClient} task: boots the game through the real loader - from the distribution's
 *       own jars - with this mod's own output (its compiled classes and processed resources) on the
 *       class path, mirroring {@code ./gradlew run} in {@code PvZ-Portable};</li>
 *   <li>{@code include(...)} ({@link JarInJar}): a mod may carry another mod inside its own jar -
 *       nested under {@code META-INF/jars/} and listed in its packaged {@code fabric.mod.json} -
 *       which is how the API aggregate carries its modules;</li>
 *   <li>Kotlin support ({@link #configureKotlin}): the mod may be written in Kotlin, compiled at the
 *       same target as the game, against the stdlib the language module provides - and
 *       {@code fabric-language-kotlin} is on the class paths a run uses, so the Kotlin runtime of a
 *       mod is the real Fabric language module's;</li>
 *   <li>the test conventions of this workspace (JUnit platform, the fork's {@code pvz.test.modJar} /
 *       {@code pvz.test.forkClasspath} system properties, headless SDL, first thread on macOS);</li>
 *   <li>the Maven publication of the mod jar ({@code mavenLocal} coordinates, MIT).</li>
 * </ul>
 *
 * <p>Repositories stay the project's own business (the settings file declares the Gradle Plugin Portal
 * - which is where this plugin itself comes from - plus {@code mavenLocal} and the Fabric maven, for
 * the artifacts of this workspace): a plugin that installs its own repository set would silently drop
 * them, which is exactly one of the reasons fabric-loom cannot be used here.
 *
 * <p>The Kotlin version of this class is that toolchain and nothing else: the same configurations,
 * tasks and defaults under the same names. Two things a translation cannot keep literally say so
 * where they are - {@code ForkClasspath} (now a private top-level class of this file, because a
 * Kotlin nested class cannot read the private members of the class it is nested in, and it needs no
 * outer instance) and the directory's constants, which are {@code companion object} constants here
 * rather than {@code static final} fields. The {@code &#42;} entity in {@link #distJars} is the third:
 * Kotlin nests block comments, so a literal slash-asterisk inside a comment would open a second one.
 * The class itself is {@code open}, as the Java class was (a Kotlin class is final by default).
 */
open class PvzModPlugin : Plugin<Project> {

    companion object {

        /** Task group of everything this plugin registers. */
        private const val TASK_GROUP = "fabric mod"

        /** What {@code runClient} launches besides this mod's own output: the game distribution and the language module. */
        private const val RUN_CLASSPATH = "pvzRunClasspath"

        /** The configuration that names the game distribution a mod is built against. */
        private const val PVZ_CONFIGURATION = "pvz"

        /** Obtains the distribution that configuration names and puts its jars where the builds need them. */
        private const val PVZ_DIST_TASK = "pvzDist"

        /** Where the downloaded release asset is cached, under the Gradle user home. */
        private const val PVZ_CACHE_DIRECTORY = "caches/pvz"

        /** Where an extracted distribution lands in a project's build directory. */
        private const val PVZ_DIST_DIRECTORY = "pvz/dist"

        /** The Gradle property that moves the release host (a mirror, or a server standing in for a release). */
        private const val PVZ_RELEASE_BASE_URL = "pvz.release.baseUrl"

        /** Launches the game with this mod loaded from the class path. */
        private const val RUN_CLIENT_TASK = "runClient"

        /** The launcher main class of the real Fabric Loader (client environment), from its own manifest. */
        private const val LAUNCHER_CLASS = "net.fabricmc.loader.impl.launch.knot.KnotClient"

        private const val LANGUAGE_KOTLIN = PvzToolchain.FABRIC_LANGUAGE_KOTLIN_COORDINATE
        private const val JUNIT_BOM = "org.junit:junit-bom:" + PvzToolchain.JUNIT
    }

    override fun apply(project: Project) {
        project.pluginManager.apply(JavaPlugin::class.java)
        project.pluginManager.apply("maven-publish")

        configureJava(project)
        configureKotlin(project)
        configurePvz(project)
        configureDependencies(project)
        configureJar(project)
        configureIncludes(project)
        configureTests(project)
        configureRunClient(project)
        // After configureRunClient: the API is an entry of the launcher class path too, so the
        // configuration it goes on (RUN_CLASSPATH) has to exist by then.
        configureApi(project)
        configurePublishing(project)
    }

    /** Java 25, UTF-8 sources: the game and every class a mod's mixin touches are Java 25. */
    private fun configureJava(project: Project) {
        val java = project.extensions.getByType(JavaPluginExtension::class.java)
        java.toolchain.languageVersion.set(JavaLanguageVersion.of(PvzToolchain.JAVA))
        project.tasks.withType(JavaCompile::class.java).configureEach { task ->
            task.options.encoding = "UTF-8"
            task.options.release.set(PvzToolchain.JAVA)
        }
    }

    /**
     * Kotlin support: a mod may be written in Kotlin (its mixins stay Java - a Mixin configuration is
     * a Java class annotated with {@code @Mixin} and Mixin's own processor reads it as such).
     *
     * <p>What the toolchain does, and why:
     * <ul>
     *   <li>applies {@code org.jetbrains.kotlin.jvm}: the plugin (and its version,
     *       {@link PvzToolchain#KOTLIN}) sits on this plugin's own class path, so a mod states
     *       neither;</li>
     *   <li>compiles Kotlin at the same target as the Java sources, {@link PvzToolchain#JAVA}: the
     *       mod's classes must be the class file version the game's are (and Kotlin 2.4 is the first
     *       release line that both accepts {@code jvmTarget 25} and can read Java 25 class files);</li>
     *   <li>declares {@code kotlin-stdlib} {@code compileOnly} (and for the tests, where the test
     *       JVM itself runs Kotlin-aware test code such as {@code kotlin.KotlinVersion}): a mod jar
     *       compiles against the stdlib, bundles none and publishes none
     *       ({@link #configureJar}), and the copy it resolves {@code kotlin.*} from at runtime is the
     *       one {@code fabric-language-kotlin} carries - that mod is on the launch class path
     *       ({@link #configureRunClient}) and the loader adds its nested {@code kotlin-stdlib} to the
     *       class path, so every Kotlin mod of an installation shares the one copy instead of each
     *       jar bundling its own. The Kotlin Gradle plugin's default dependency on the stdlib is
     *       switched off for that reason;</li>
     *   <li>puts {@code fabric-language-kotlin} ({@link PvzToolchain#FABRIC_LANGUAGE_KOTLIN}) on the
     *       launcher and test class paths: it is the language module a Kotlin mod declares in its
     *       {@code fabric.mod.json} and the owner of the runtime stdlib, and in a development run the
     *       loader resolves the mods that are on the class path (the same convention fabric-loom
     *       uses), so a mod's own {@code runClient} and end-to-end test have it without a jar being
     *       copied around. An installation (a real {@code mods/} directory) instead carries the jar,
     *       like any other mod.</li>
     * </ul>
     *
     * <p>Entry points: use a Kotlin {@code class} with a no-argument constructor, not an
     * {@code object}. Fabric Loader instantiates a {@code ModInitializer} entry point reflectively
     * through its no-argument constructor, and a Kotlin {@code object} has only a private one; an
     * {@code object} would need the {@code "kotlin"} language adapter of
     * {@code fabric-language-kotlin} - which this toolchain now ships, so a mod that wants an
     * {@code object} entry point may name it as
     * {@code {"adapter": "kotlin", "value": "..."}}. The mods of this workspace keep the plain class:
     * nothing about them needs the adapter.
     */
    private fun configureKotlin(project: Project) {
        // Read by the Kotlin Gradle plugin when it is applied (the next line), so it is set first.
        // Without it the stdlib lands in `implementation` and is published as a runtime dependency of
        // the mod, which is exactly what a mod must not declare (the launcher provides it).
        project.extensions.extraProperties.set("kotlin.stdlib.default.dependency", "false")
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")

        val kotlin = project.extensions.getByType(KotlinJvmProjectExtension::class.java)
        kotlin.compilerOptions.jvmTarget.set(JvmTarget.fromTarget(PvzToolchain.JAVA.toString()))

        // The stdlib, like the loader and the game: on the compile class path, never bundled, never
        // published. At runtime the mod links against the copy fabric-language-kotlin carries, which
        // is also what the tests get (they run inside the loader, and their own JVM needs the stdlib
        // for the test class itself).
        project.dependencies.add("compileOnly", PvzToolchain.KOTLIN_STDLIB)
        project.dependencies.add("testImplementation", PvzToolchain.KOTLIN_STDLIB)

        // The language module: a mod of this workspace requires it (its `fabric.mod.json` says so),
        // so the class paths the toolchain launches have to bring it. It is resolved without its own
        // POM dependencies: the Kotlin runtime it provides is the `kotlin-stdlib` (and the kotlinx
        // libraries) nested in its jar, which the loader adds to the class path when it loads the
        // mod - exactly like a real installation, where only this one jar is there. Letting Gradle
        // pull the POM tree in as well would put a second, plain copy of every one of those libraries
        // on the launch class path, which is the duplication the nested jars exist to avoid.
        val languageModule = project.dependencies.add("testRuntimeOnly", LANGUAGE_KOTLIN)
        (languageModule as ModuleDependency).isTransitive = false
    }

    /**
     * The game distribution a mod is built against, named by the {@code pvz} configuration: the whole
     * of the game side of the toolchain (see the class comment for why it is a distribution and not
     * a list of coordinates).
     *
     * <p>What a build script writes, and what it means:
     * <ul>
     *   <li>nothing - the default is {@code pvz("}{@link PvzToolchain#GAME}{@code ")}, the game version
     *       of this toolchain, exactly like the plugin used to inject that coordinate by hand (it is
     *       the same coordinate, resolved through the distribution instead of mavenLocal);</li>
     *   <li>{@code pvz("cn.enaium.fabricpvz:pvz-portable:<version>")} - that version's release: the
     *       asset {@code pvz-portable-<version>.zip} of its GitHub release
     *       ({@link PvzToolchain#RELEASE_BASE_URL}, movable with the {@code pvz.release.baseUrl}
     *       Gradle property), downloaded into {@code caches/pvz} of the Gradle user home once and
     *       unpacked into {@code build/pvz/dist};</li>
     *   <li>{@code pvz(files("<zip>"))} - a distribution archive on disk, e.g. the one
     *       {@code ./gradlew distZip} just wrote in the game project, unpacked the same way (this is
     *       how a mod is tested against a distribution built here, and how the release asset is
     *       reproduced before it exists);</li>
     *   <li>{@code pvz(files("<directory>"))} - a directory that <em>is</em> an unpacked distribution
     *       (it has {@code lib/}), used where it is, nothing copied; or a <em>release directory</em>
     *       (a directory holding {@code pvz-portable-<version>.zip} files, e.g. the game project's
     *       {@code build/distributions}), from which the declared version's asset is taken.</li>
     * </ul>
     *
     * <p>The jars of the distribution's launcher class path ({@code lib/}) then go on
     * {@code compileOnly} (a mod compiles against them, bundles none of them and declares none of
     * them), on the test class path (a mod's end-to-end test starts the real loader, on the jars of
     * the release) and on {@code pvzRunClasspath} (which is what {@code runClient} launches). A
     * distribution's {@code mods/} is <em>not</em> part of any of them (see {@link #distJars}).
     */
    private fun configurePvz(project: Project) {
        val pvz = project.configurations.create(PVZ_CONFIGURATION) { configuration ->
            configuration.description = "The game distribution this mod is built against: " +
                "pvz(\"" + PvzToolchain.GAME + "\") for the release of that version (the asset " +
                PvzDist.assetName("<version>") + " of its GitHub release), or pvz(files(<zip>)) / " +
                "pvz(files(<directory>)) for a distribution on disk. Read by the " + PVZ_DIST_TASK +
                " task; never resolved, because no repository holds a distribution."
            configuration.isCanBeConsumed = false
            configuration.isCanBeResolved = false
        }

        val dist = project.tasks.register(PVZ_DIST_TASK, PvzDistTask::class.java) { task ->
            task.group = TASK_GROUP
            task.description = "Provides the game distribution the pvz configuration names - downloads the release" +
                " asset of the version, or takes the local archive / unpacked distribution - and unpacks it" +
                " into " + PVZ_DIST_DIRECTORY + " (" + PvzDist.LIB + "/ is the class path a mod builds and" +
                " runs against)."
            task.cacheDirectory.set(File(project.gradle.gradleUserHomeDir, PVZ_CACHE_DIRECTORY))
            task.libDirectory.set(project.layout.buildDirectory.dir(PVZ_DIST_DIRECTORY + "/" + PvzDist.LIB))
            task.modsDirectory.set(project.layout.buildDirectory.dir(PVZ_DIST_DIRECTORY + "/" + PvzDist.MODS))
            task.offline.set(project.gradle.startParameter.isOffline)
        }

        // Which distribution it is comes from the build script (the pvz configuration), so the jars can
        // only be put on the class paths once it has run - and they have to be there before
        // compileClasspath, testRuntimeClasspath and pvzRunClasspath are resolved, which happens later.
        project.afterEvaluate { evaluated ->
            val distribution = resolvePvzDist(evaluated, pvz, dist)
            evaluated.dependencies.add("compileOnly", distribution)
            evaluated.dependencies.add("testImplementation", distribution)
            evaluated.dependencies.add(RUN_CLASSPATH, distribution)
        }
    }

    /**
     * Reads the {@code pvz} configuration and turns it into the class path entries of the game
     * distribution: the {@code pvzDist} task's {@code lib/} when the distribution has to be obtained
     * (under {@code build/pvz/dist}), or the {@code lib/} of a local unpacked distribution, in place.
     *
     * <p>Everything that can be wrong about the notation is reported here, while the build is still
     * being configured: a coordinate that is not the game, a version it does not state, a local path
     * that is not there, or an asset a release directory does not hold.
     */
    private fun resolvePvzDist(project: Project, pvz: Configuration, dist: TaskProvider<PvzDistTask>): FileCollection {
        val local = LinkedHashSet<File>()
        val versions = ArrayList<String>()
        for (dependency in pvz.dependencies) {
            if (dependency is ModuleDependency) {
                val group = dependency.group
                val name = dependency.name
                val version = dependency.version
                if (PvzToolchain.GROUP != group || PvzToolchain.GAME_ARTIFACT != name) {
                    throw GradleException("pvz(" + group + ":" + name + ":" +
                        (if (version == null) "" else version) + ") is not the game: the pvz configuration takes the" +
                        " game distribution, pvz(\"" + PvzToolchain.GAME + "\") for the default version.")
                }
                if (version == null || version.isBlank()) {
                    throw GradleException("pvz(\"" + group + ":" + name + "\") states no version: the release to" +
                        " take the distribution from is the version, e.g. pvz(\"" + PvzToolchain.GAME + "\").")
                }
                versions.add(version)
            } else if (dependency is FileCollectionDependency) {
                local.addAll(dependency.files.files)
            } else {
                throw GradleException("pvz(" + dependency + ") is neither a coordinate nor a file: the pvz" +
                    " configuration takes pvz(\"" + PvzToolchain.GAME + "\") for a released distribution, or" +
                    " pvz(files(<zip>)) / pvz(files(<directory>)) for one on disk.")
            }
        }
        if (versions.size + local.size > 1) {
            throw GradleException("pvz takes one game distribution, not " + (versions.size + local.size) +
                ": " + versions + " " + local + ". Point it at one version, or at one local distribution.")
        }

        val version = if (versions.isEmpty()) PvzToolchain.GAME_VERSION else versions[0]
        if (local.isEmpty()) {
            val baseUrl = project.providers.gradleProperty(PVZ_RELEASE_BASE_URL)
                .getOrElse(PvzToolchain.RELEASE_BASE_URL)
            val urls = PvzDist.releaseUrls(version, baseUrl)
            dist.configure { task ->
                task.version.set(version)
                task.releaseUrls.set(urls)
            }
            return distJars(project, dist, buildDistDirectory(project))
        }

        val source = local.iterator().next().toPath()
        if (Files.isDirectory(source)) {
            if (PvzDist.isDistribution(source)) {
                // A distribution that already is one: used where it is (its jars are the class path
                // entries), and pvzDist stays in the graph only to check it and say which one it is.
                PvzDist.requireUsable(source, "The local distribution " + source)
                dist.configure { task -> task.localDistribution.set(source.toFile()) }
                return distJars(project, dist, source)
            }
            // A release directory: the distribution of the version this build is against, by name.
            val asset = source.resolve(PvzDist.assetName(version))
            if (!Files.isRegularFile(asset)) {
                throw GradleException("The release directory " + source + " holds no " + PvzDist.assetName(version) +
                    " (" + version + " is the version this build is against): build the game first" +
                    " (./gradlew distZip in PvZ-Portable writes that file), point pvz at another version, or" +
                    " point it at a distribution zip / unpacked distribution directly.")
            }
            return fromArchive(project, dist, asset, "the distribution of the release directory " + source)
        }
        if (!Files.isRegularFile(source)) {
            throw GradleException("The local game distribution " + source + " does not exist: point pvz at the" +
                " distribution archive a build wrote (the game's distZip output), at an unpacked distribution" +
                " (a directory with " + PvzDist.LIB + "/), or at a directory of release archives.")
        }
        return fromArchive(project, dist, source, "the local distribution archive " + source)
    }

    /** Wires the {@code pvzDist} task to unpack one archive, and returns the class path entries of the result. */
    private fun fromArchive(project: Project, dist: TaskProvider<PvzDistTask>, zip: Path, what: String): FileCollection {
        dist.configure { task -> task.zip.set(zip.toFile()) }
        project.logger.info("pvz: {} is {}", what, zip)
        return distJars(project, dist, buildDistDirectory(project))
    }

    /**
     * The class path of a distribution, as the entries it is: its {@code lib/&#42;.jar} - the launcher
     * class path (the game, the provider with the real Fabric Loader, Mixin, ASM, the SLF4J/Log4j
     * stack and LWJGL). A {@code mods/} directory of the distribution is not one of them, even when
     * the archive has one: a mod is not something a game distribution ships, and a mod jar taken
     * from there would be a second answer to "where does this mod come from" next to the ones the
     * toolchain gives - the API arrives as its own coordinate ({@link #configureApi}) and a mod of
     * this workspace arrives as its own output on the launch class path ({@link #configureRunClient}),
     * or as the {@code mods/} jar a player installs. It is also the difference
     * that makes a distribution of any vintage work: the released asset of a version is cached per
     * version and never re-downloaded, so a build may well be pointed at a distribution whose
     * {@code mods/} still holds an older API.
     *
     * <p>They are the <em>files</em> and not the directory: a class path directory entry is a package
     * tree of {@code .class} files, so a directory holding jars contributes nothing (a real
     * installation puts the same list of jars on the command line, {@code java -cp "lib/&#42;"}).
     *
     * <p>When the distribution is unpacked from an archive, the jars do not exist while the build is
     * being configured, so the collection is resolved when it is used - after {@code pvzDist} has run,
     * which is what this collection says it is built by.
     */
    private fun distJars(project: Project, dist: TaskProvider<PvzDistTask>, distribution: Path): FileCollection {
        val jars: ConfigurableFileCollection = project.files(
            project.fileTree(distribution.toFile()) { tree -> tree.include(PvzDist.LIB + "/*.jar") }
        )
        jars.builtBy(dist)
        return jars
    }

    /** Where an unpacked distribution lands: {@code build/pvz/dist}, the {@code pvzDist} task's own output. */
    private fun buildDistDirectory(project: Project): Path {
        return project.layout.buildDirectory.dir(PVZ_DIST_DIRECTORY).get().asFile.toPath()
    }

    /**
     * The workspace's API on the class paths a mod compiles, tests and runs against: the aggregate
     * jar of {@link PvzToolchain#API}, whose nested jars are the four feature modules
     * ({@link JarInJar}) - one artifact where a distribution used to carry five jars in its
     * {@code mods/} directory, which is now a game only.
     *
     * <p>Two questions, four class paths:
     * <ul>
     *   <li>compilation - {@code compileOnly} and {@code testCompileOnly}, <em>with</em> the
     *       aggregate's own dependencies: a mod's sources and its tests use the API's types
     *       ({@code LawnEvents}, {@code PlantRegistry}, …), and those classes are the modules' - javac
     *       reads jar files, not jars nested inside a jar, so a compile class path has to carry the
     *       modules themselves. The aggregate declares them ({@code api} in its build and in its POM,
     *       exactly as Fabric API does), so this one coordinate brings both;</li>
     *   <li>runtime - {@code testImplementation} and the {@code runClient} class path
     *       ({@link #RUN_CLASSPATH}), <em>without</em> them: at runtime Fabric Loader reads the nested
     *       jars out of the aggregate, so a class path that also carried the modules separately would
     *       resolve every module twice - once from inside the aggregate and once as a class path mod.
     *       This is the shape of the {@code fabric-language-kotlin} declaration below (one jar, whose
     *       nested {@code kotlin-stdlib} is the runtime the loader provides) and the shape of an
     *       installation, where the aggregate's one jar in {@code mods/} is what the modules are
     *       loaded from. A development run discovers mods on the class path
     *       ({@code -Dfabric.development=true}, {@link #configureRunClient}) - and a mod's end-to-end
     *       test launches the same launcher with the same class path ({@link #configureTests}) - so
     *       the API is loaded, in both, from that one jar.</li>
     * </ul>
     *
     * <p>A mod states none of this: it declares {@code "fabric-api": ">=1.0.0"} in its
     * {@code fabric.mod.json} like any other mod dependency, and the toolchain is where the jar that
     * satisfies it comes from.
     */
    private fun configureApi(project: Project) {
        val dependencies = project.dependencies

        // Compilation - this project's and its tests', which compile against the same API: the
        // aggregate and, through its own POM, the modules it nests. `testCompileOnly` because a test
        // source set does not inherit `compileOnly` here, and never `testImplementation`: the modules
        // may be on a *compile* class path, they must not be on a runtime one (see below).
        for (configuration in listOf("compileOnly", "testCompileOnly")) {
            dependencies.add(configuration, PvzToolchain.API)
        }

        // Runtime - the test fork's launcher class path and runClient: the aggregate alone - its
        // nested jars are the modules, for Fabric Loader as much as for anything else reading this
        // class path. Not transitive, so no second copy of a module ends up on a class path the
        // loader walks.
        for (configuration in listOf("testImplementation", RUN_CLASSPATH)) {
            val api = dependencies.add(configuration, PvzToolchain.API)
            (api as ModuleDependency).isTransitive = false
        }
    }

    /**
     * Fabric Loom's {@code include(...)} for a mod of this workspace ({@link JarInJar}): the mods a
     * mod carries inside its own jar, declared as ordinary dependencies in the {@code include}
     * configuration, then nested into the built jar - under {@code META-INF/jars/} and listed in its
     * packaged {@code fabric.mod.json} - when the {@code jar} task runs:
     *
     * <pre>{@code
     * dependencies {
     *     include("com.example:some-library:1.0")
     * }
     * }</pre>
     *
     * <p>{@code include(...)} is the generated accessor of the configuration this creates;
     * {@code add("include", …)} is the same declaration without it.
     *
     * <p>Nothing is wired while the configuration is empty, which is every mod of this workspace
     * (the API aggregate, the one project here that nests jars, is not a mod project of the toolchain
     * and calls the mechanism itself, see {@code fabric-api/build.gradle.kts}): a mod that includes
     * nothing gets exactly the jar it got before, down to the bytes of its {@code fabric.mod.json}.
     */
    private fun configureIncludes(project: Project) {
        val include = JarInJar.configuration(project)
        val jar = project.tasks.named(JavaPlugin.JAR_TASK_NAME, Jar::class.java)
        // What is included comes from the build script's `dependencies` block, i.e. it is only known
        // once the script has run; the wiring itself only has to be in place before the jar executes.
        project.afterEvaluate {
            if (include.dependencies.isEmpty()) {
                return@afterEvaluate
            }
            JarInJar.include(jar.get(), include)
        }
    }

    /** The dependencies the toolchain itself provides. The game side - the game, the provider, the
     * real Fabric Loader, Mixin, ASM, SLF4J and Log4j - is not here: it is the game distribution the
     * {@code pvz} configuration names ({@link #configurePvz}), whose jars arrive on the same class
     * paths (and are also what {@code runClient} launches, which is what makes "compile against what
     * runs" true by construction). The API, the one mod every mod of this workspace requires, is the
     * toolchain's own coordinate and arrives through {@link #configureApi}.
     */
    private fun configureDependencies(project: Project) {
        val dependencies = project.dependencies

        // Testing a mod: the end-to-end harness starts the real loader in a second JVM, so the test
        // class path is a launcher class path - the distribution's jars (added by configurePvz),
        // fabric-language-kotlin and JUnit.
        dependencies.add("testImplementation", dependencies.platform(JUNIT_BOM))
        dependencies.add("testImplementation", "org.junit.jupiter:junit-jupiter")
        dependencies.add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    /** Mod jars are plain class jars: they bundle no dependencies, so a duplicate is a bug. */
    private fun configureJar(project: Project) {
        project.tasks.named(JavaPlugin.JAR_TASK_NAME, Jar::class.java) { jar ->
            jar.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }

    /**
     * The workspace's test conventions, so a mod's own end-to-end test only writes its assertions:
     * the loader is launched in a forked JVM whose main class path is this project's test runtime
     * class path without this project's own main output (the mod under test may only come from the
     * {@code mods/} directory the test assembles).
     */
    private fun configureTests(project: Project) {
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val jar = project.tasks.named(JavaPlugin.JAR_TASK_NAME, Jar::class.java)

        project.tasks.withType(Test::class.java).configureEach { test ->
            test.useJUnitPlatform()
            test.testLogging { logging ->
                logging.events = EnumSet.of(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
                logging.exceptionFormat = TestExceptionFormat.FULL
                logging.showStandardStreams = true
            }
            // The fork reads the mod from the jar it was handed, so the jar has to exist first.
            test.dependsOn(jar)
            test.systemProperty("pvz.test.modJar", jar.get().archiveFile.get().asFile.absolutePath)
            // The class path of the forked launcher, as an argument provider instead of a value: it
            // contains the jars of the game distribution, which do not exist until pvzDist has run -
            // reading it here, while the project is still being configured, would hand the fork a class
            // path without the game and without the loader.
            test.jvmArgumentProviders.add(ForkClasspath(sourceSets))
            // LWJGL uses JNI; JDK 25 warns unless native access is enabled explicitly.
            test.jvmArgs("--enable-native-access=ALL-UNNAMED")
            if (isMac()) {
                // SDL on macOS needs the first thread.
                test.jvmArgs("-XstartOnFirstThread")
            }
            // The dummy drivers keep the run headless; an explicit setting of the environment wins.
            test.environment("SDL_VIDEODRIVER", environmentOrDefault("SDL_VIDEODRIVER", "dummy"))
            test.environment("SDL_AUDIODRIVER", environmentOrDefault("SDL_AUDIODRIVER", "dummy"))
        }
    }

    /**
     * Boots the game the way the launcher does, and the way a Minecraft developer's run does: a
     * {@code KnotClient} whose class path is the game distribution's jars (the game, the provider
     * with the real Fabric Loader, the game's own mods), the language module a Kotlin mod needs, and
     * <b>this mod's own main output</b> - its compiled classes and its processed resources,
     * {@code fabric.mod.json} among them ({@code sourceSets.main.output}, filtered to the
     * directories that exist and passed as one {@code fabric.classPathGroups} entry). A development
     * run discovers the mods that are on the class path ({@code -Dfabric.development=true} below),
     * which is exactly how fabric-loom's {@code runClient} loads the mod under development and how
     * this toolchain already gets the API and the language module there
     * ({@link #configureApi}, {@link #configureKotlin}); so nothing has to be built, copied or
     * re-copied before a run reflects a change.
     *
     * <p>That the game is the distribution's own jars (the same ones a mod compiles against) and not
     * the application class path of {@code PvZ-Portable}'s {@code run} task is the one difference
     * between the two: a mod project has no application class path of its own, so the game's is
     * stated as a class path and the mod is added to it.
     *
     * <p>{@code run/mods/} is not written by the build and not needed for a run (it stays as the
     * directory a developer drops another mod into, exactly like an installation's). One thing to
     * know about it: a jar of <em>this</em> mod in there - what the older toolchain's {@code
     * pvzModsJar} left behind, or a copy of a release - is not an error and not a duplicate
     * either: the loader resolves the two candidates for the same id to one of them, and (measured
     * on fabric-loader 0.19.5) to the one in {@code run/mods}, without a warning. That jar would
     * then silently be the mod that runs, so it has to be deleted once.
     */
    private fun configureRunClient(project: Project) {
        val runClasspath = project.configurations.create(RUN_CLASSPATH) { configuration ->
            configuration.description = "The jars runClient launches besides this mod's own output: the game " +
                "distribution's jars (the game, the provider with the real Fabric Loader, the API) plus " +
                "fabric-language-kotlin. Not published: a mod never depends on its runtime."
            configuration.isCanBeConsumed = false
            configuration.isCanBeResolved = true
        }
        // The distribution's jars (the game, the provider with the real loader, and the game's own
        // mods, i.e. the API modules) are added by configurePvz.
        val dependencies = project.dependencies
        // The language module a Kotlin mod requires. A development run discovers the mods that are
        // on the class path (that is what -Dfabric.development=true below is for, and how the API
        // modules are found as well), so this is the Loom-style way of having it here, next to the
        // mod's own output on the same class path.
        //
        // Without its POM dependencies, exactly like the test one: the loader, Mixin, ASM and the
        // Log4j stack of this class path are the distribution's jars, and pulling the module tree in
        // as well would put a second Fabric Loader of the same version on the class path (the
        // language module depends on it), which the loader refuses: "duplicate fabric loader classes
        // found on classpath". The Kotlin runtime it provides is the stdlib nested in its own jar,
        // which the loader adds when it loads the module.
        val languageModule = dependencies.add(RUN_CLASSPATH, LANGUAGE_KOTLIN)
        (languageModule as ModuleDependency).isTransitive = false

        val runDirectory = File(project.projectDir, "run")
        val mainSourceSet = project.extensions.getByType(SourceSetContainer::class.java)
            .getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        // What this mod is in a development run: the main source set's own output - the compiled
        // classes (build/classes/java/main, build/classes/kotlin/main) and the processed resources
        // (build/resources/main, where fabric.mod.json and the mixin configurations are) - and, behind
        // it, whatever the mod declares as `implementation`. `classesDirs` and the resources directory
        // are the entries themselves (not the runtime class path) because they are also the group
        // below, and their own task dependencies (compileJava, compileKotlin, processResources) come
        // with them, so a run builds what it runs.
        //
        // Filtered to what exists *when the run is configured for execution*: the Kotlin Gradle plugin
        // adds build/classes/kotlin/main to the output directories even when the mod has no Kotlin
        // source at all, and Fabric Loader refuses nothing but does warn about a class path entry that
        // is not there ("Class path entries reference missing files ... the game may not load
        // properly!" - Knot checks java.class.path). After the compile and resource tasks of the run
        // have been up to date or have run, this is exactly the output the mod has.
        val mainOutput = mainSourceSet.output.filter { file -> file.exists() }
        // The mod's own output directories as ONE class path group. They are separate class path
        // entries (one per classes directory, one for the resources), and Fabric Loader looks for a
        // mod in each class path entry on its own: it finds the `fabric.mod.json` in the resources
        // directory, so *that* is the path it registers as the mod's code source, while the classes
        // directories stay plain parent-classpath entries. Classes from a directory the loader does
        // not own are defined by the parent class loader (KnotClassDelegate.isValidParentUrl: "reject
        // urls shadowed by this cl"), and that parent is a *parent* of the Knot loader - so such a
        // class cannot see the classes the loader itself carries, among them the modules nested inside
        // the API aggregate jar, and a run dies with a NoClassDefFoundError on the mod's own first API
        // call. `fabric.classPathGroups` is the loader's own answer: the paths of a group are one
        // candidate and all of them become the mod's code sources, exactly like the paths of a mod jar
        // in an installation (and the loader, not the parent, then defines the mod's classes, so Mixin
        // can transform them too). The loader's own message about a class it "hasn't been exposed to
        // the game" names this property for the same reason.

        project.tasks.register(RUN_CLIENT_TASK, JavaExec::class.java) { task ->
            task.group = TASK_GROUP
            task.description = "Runs the game through the real Fabric Loader with this mod's own output on the class path."
            task.mainClass.set(LAUNCHER_CLASS)
            task.classpath = project.files(runClasspath, mainOutput)
            task.workingDir = runDirectory
            task.doFirst { runDirectory.mkdirs() }
            // The game directory: Fabric Loader reads mods/ from it (as in PvZ-Portable's run task).
            task.jvmArgs("-Dpvz.gameDir=" + runDirectory.absolutePath)
            // This port is the only game provider that can be present, so skip the Minecraft
            // detection pass over the class path.
            task.jvmArgs("-Dfabric.skipMcProvider=true")
            // Development run: the game, the API and this mod are on the class path, so let the
            // loader discover the mods there (rather than only the jars in run/mods/).
            task.jvmArgs("-Dfabric.development=true")
            // This mod's output directories are one class path entry as far as the loader is concerned
            // (see above): without this it registers only the directory the fabric.mod.json is in and
            // leaves the mod's classes to the parent class loader. An argument provider, not a
            // `jvmArgs` line, because it reads the same lazily filtered collection the class path is
            // made of - at execution time, when those directories exist.
            task.jvmArgumentProviders.add(ClassPathGroup(mainOutput))
            task.jvmArgs("--enable-native-access=ALL-UNNAMED")
            if (isMac()) {
                task.jvmArgs("-XstartOnFirstThread")
            }
        }
    }

    /**
     * Publishing a mod: the jar (and its Gradle metadata) under mavenLocal coordinates, which is how
     * {@code PvZ-Portable}'s end-to-end test picks the example mod up.
     *
     * <p>The coordinates are deliberately not set here: the publication takes
     * {@code group}/{@code name}/{@code version} from the project when the POM is written, so a mod
     * build only states them once. (The publications container realizes what is registered in it
     * right away - it is ordered - so anything read here would still be the pre-script defaults.)
     */
    private fun configurePublishing(project: Project) {
        val publishing = project.extensions.getByType(PublishingExtension::class.java)
        publishing.publications.register("maven", MavenPublication::class.java) { publication ->
            publication.from(project.components.getByName("java"))
            publication.pom.name.set(project.name)
            // The description is set by the build script after the plugin is applied.
            publication.pom.description.set(project.providers.provider { project.description })
            publication.pom.licenses { licenses ->
                licenses.license { license ->
                    license.name.set("MIT")
                    license.url.set("https://opensource.org/licenses/MIT")
                }
            }
        }
    }
}

/**
 * The {@code -Dfabric.classPathGroups} of a development run: this mod's own output directories as
 * one group, so Fabric Loader treats them as the single class path entry a mod jar is
 * ({@code ClasspathModCandidateFinder} looks for {@code fabric.mod.json} in every class path entry
 * and without the group would register only the directory it found one in). See
 * {@code PvzModPlugin.configureRunClient}.
 *
 * <p>An argument provider rather than a {@code jvmArgs} line because it reads the same lazily
 * filtered collection the run's class path is made of, and that filter is only meaningful once the
 * classes and resources of the mod have been written - i.e. when the run is configured for
 * execution, not while the project is being configured.
 */
private class ClassPathGroup(private val outputDirectories: FileCollection) : CommandLineArgumentProvider {

    override fun asArguments(): Iterable<String> =
        listOf("-Dfabric.classPathGroups=" + outputDirectories.files.joinToString(File.pathSeparator))
}

/**
 * The class path of the loader-launching fork: the test runtime class path (the jars of the game
 * distribution, test classes, JUnit) without this project's own main output, so the mod under test
 * can only be the jar in the {@code mods/} directory. Source sets without resources contribute
 * directories that do not exist; the loader warns about those, so they are dropped.
 *
 * <p>It is an argument provider rather than a plain system property value because the class path
 * is only complete once {@code pvzDist} has unpacked the game distribution, i.e. long after the
 * project has been configured: read while configuring, it would be the class path of a build
 * without a game. The provider is what the {@code -Dpvz.test.forkClasspath} of the fork is made of.
 *
 * <p>In the Java original this is a record nested in {@code PvzModPlugin}; Kotlin nested classes
 * cannot read the outer class's private members, and it needs no outer instance, so it is a private
 * top-level class here - the same value holder, named the same in every message and stack trace
 * that matters.
 */
private class ForkClasspath(private val sourceSets: SourceSetContainer) : CommandLineArgumentProvider {

    override fun asArguments(): Iterable<String> =
        listOf("-Dpvz.test.forkClasspath=" + forkClasspath(sourceSets))
}

private fun forkClasspath(sourceSets: SourceSetContainer): String {
    val mainOutput = HashSet(sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).output.files)
    val entries = ArrayList<String>()
    for (file in sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME).runtimeClasspath) {
        if (file.exists() && !mainOutput.contains(file) && !isKotlinRuntime(file)) {
            entries.add(file.absolutePath)
        }
    }
    return entries.joinToString(File.pathSeparator)
}

/**
 * Whether a test class path entry is a Kotlin runtime rather than something the loader has to
 * see: a launch class path carries no {@code kotlin-stdlib} of its own, because the one a mod
 * resolves {@code kotlin.*} from is nested in {@code fabric-language-kotlin}'s jar and the loader
 * puts it on the class path when it loads that mod. The stdlib on this project's test class path
 * exists for the test JVM itself (its own assertions are written against the version the mod
 * compiles against) and is left out of the fork, so the end-to-end test runs the mod under
 * exactly the runtime an installation provides - and its "where does the stdlib come from" probe
 * has only one possible answer.
 */
private fun isKotlinRuntime(file: File): Boolean {
    val name = file.name
    return name.startsWith("kotlin-stdlib") && name.endsWith(".jar")
}

private fun environmentOrDefault(name: String, fallback: String): String {
    val value = System.getenv(name)
    return if (value == null || value.isBlank()) fallback else value
}

private fun isMac(): Boolean {
    return System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("mac")
}
