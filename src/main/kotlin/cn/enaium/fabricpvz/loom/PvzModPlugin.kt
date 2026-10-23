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

/**
 * The mod toolchain of this workspace: applied as {@code cn.enaium.fabricpvz.loom}, one line, and
 * the project is a Fabric mod of the PvZ port.
 *
 * <p>The game side of a mod comes from a <b>distribution</b> and from nowhere else: the
 * {@code pvz} configuration names it ({@link #PVZ_CONFIGURATION}), {@link PvzDistTask} obtains it,
 * and its jars are what the mod compiles against and what the loader runs. A distribution is the
 * release asset {@code pvz-portable-<version>.zip} the game's release attaches - a version package,
 * the game jar with its JSON manifest next to it (the manifest names the game's libraries: the real
 * Fabric Loader, Mixin, ASM, the SLF4J/Log4j stack and LWJGL with the natives of this machine) - or
 * a distribution on disk for testing. The toolchain therefore does not inject the game, the loader,
 * Mixin or ASM as coordinates: the version package's JSON already <em>is</em> that list, at whatever
 * version the release carries. See {@link PvzDist} and {@code loom/README.md}.
 *
 * <p>Its {@code mods/} is not a class path of a mod build: a game distribution is a game, and a
 * mod jar on a launch class path is inert outside a development run, so what a player installs
 * belongs in the installation's own {@code mods/} directory and nowhere else.
 *
 * <p>That is the whole contract, and it is deliberately narrow: the toolchain supplies the game and
 * the libraries the version package's JSON next to the game jar names, and <em>nothing</em> else.
 * A mod's own dependencies - the Kotlin Gradle plugin and the stdlib, JUnit, the workspace's API,
 * {@code fabric-language-kotlin} - are declared by the mod, in its own build file, exactly like the
 * dependencies of any other project. The toolchain neither applies a language plugin nor picks a
 * test framework nor a version of the API for the mods.
 *
 * <p>What it supplies besides the game distribution:
 * <ul>
 *   <li>{@code compileOnly}: the jars the version package's manifest names - the game itself plus
 *       the loader, Mixin, ASM, the logging stack and LWJGL - so the mod compiles against exactly
 *       what the launcher loads (the manifest's native entries are runtime-only: a native jar
 *       carries no class a mod would compile against);</li>
 *   <li>the same jars for {@code testImplementation} and the natives for
 *       {@code testRuntimeOnly}, so a mod's end-to-end test can start the real Fabric Loader in a
 *       second JVM exactly like the launcher does;</li>
 *   <li>the {@code runClient} task: boots the game through the real loader - the distribution's own
 *       jars plus <em>this mod's own runtime class path</em> ({@code sourceSets.main.runtimeClasspath}:
 *       its output, its {@code implementation} dependencies) and the {@code include} configuration -
 *       mirroring {@code ./gradlew run} in {@code PvZ-Portable};</li>
 *   <li>{@code include(...)} ({@link JarInJar}): a mod may carry another mod inside its own jar -
 *       nested under {@code META-INF/jars/} and listed in its packaged {@code fabric.mod.json} -
 *       which is how the API aggregate carries its modules;</li>
 *   <li>the test conventions of this workspace (JUnit platform, the fork's {@code pvz.test.modJar} /
 *       {@code pvz.test.forkClasspath} system properties, headless SDL, first thread on macOS, and a
 *       launch class path with no Kotlin runtime of its own - the one a mod brings is what it
 *       runs on);</li>
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

        /** The configuration that names the game distribution a mod is built against. */
        private const val PVZ_CONFIGURATION = "pvz"

        /**
         * What {@code runClient} launches: the game distribution's jars and the mod's own runtime
         * dependencies, resolved as one graph so a dependency both sides name (the Fabric Loader a
         * language module depends on) arrives once.
         */
        private const val LAUNCHER_CLASSPATH = "pvzLauncher"

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
    }

    override fun apply(project: Project) {
        project.pluginManager.apply(JavaPlugin::class.java)
        project.pluginManager.apply("maven-publish")

        configureJava(project)
        configurePvz(project)
        configureJar(project)
        configureIncludes(project)
        configureTests(project)
        configureRunClient(project)
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
     * the release) and on the main runtime class path (which is what {@code runClient} launches,
     * together with the mod's own {@code implementation} dependencies). A distribution's
     * {@code mods/} is <em>not</em> part of any of them (see {@link #distJars}).
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
            task.description = "Provides the game distribution the pvz configuration names - downloads the" +
                " version package of the release, or takes the local archive / unpacked distribution - and" +
                " unpacks it into " + PVZ_DIST_DIRECTORY + " (its game jar is a file of the class paths, its" +
                " manifest's libraries are Maven coordinates)."
            task.cacheDirectory.set(File(project.gradle.gradleUserHomeDir, PVZ_CACHE_DIRECTORY))
            task.distDirectory.set(project.layout.buildDirectory.dir(PVZ_DIST_DIRECTORY))
            task.offline.set(project.gradle.startParameter.isOffline)
        }

        // What runClient launches: the same jars a mod's own runtime class path is resolved against,
        // so conflict resolution sees one dependency graph (a mod's `implementation` of the language
        // module names the same Fabric Loader the distribution does, and must not put a second copy
        // of it on the class path). It extends the two declarable configurations of a mod's runtime -
        // a resolvable configuration cannot be declared into itself - and the plugin adds the
        // distribution's jars to it below.
        project.configurations.create(LAUNCHER_CLASSPATH) { configuration ->
            configuration.description = "What runClient launches besides this mod's own output: the game " +
                "distribution's jars (the game and the libraries the version package's JSON names) and " +
                "this mod's own runtime dependencies (its `implementation` and `runtimeOnly` ones). " +
                "Not published: a mod never depends on its runtime."
            configuration.isCanBeConsumed = false
            configuration.isCanBeResolved = true
            configuration.extendsFrom(
                project.configurations.getByName(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME),
                project.configurations.getByName(JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME),
            )
        }

        // Which distribution it is comes from the build script (the pvz configuration), so the entries can
        // only be put on the class paths once it has run - and they have to be there before
        // compileClasspath, testRuntimeClasspath and the launcher class path are resolved, which happens
        // later.
        project.afterEvaluate { evaluated ->
            val distribution = resolvePvzDist(evaluated, pvz, dist)
            // The game itself: one file, because no repository holds it (its launcher entry point is
            // published inside it). Everything else the version manifest lists is a Maven coordinate, so
            // Gradle - and not this toolchain - resolves, caches and conflict-manages it.
            evaluated.dependencies.add("compileOnly", distribution.game)
            evaluated.dependencies.add("testImplementation", distribution.game)
            evaluated.dependencies.add(LAUNCHER_CLASSPATH, distribution.game)
            for (library in distribution.libraries) {
                evaluated.dependencies.add("compileOnly", library)
                evaluated.dependencies.add("testImplementation", library)
                evaluated.dependencies.add(LAUNCHER_CLASSPATH, library)
            }
            // The natives of the machine this build runs on: a runtime class path only, because a native
            // jar carries no class a mod would compile against.
            for (native in distribution.natives) {
                evaluated.dependencies.add("testRuntimeOnly", native)
                evaluated.dependencies.add(LAUNCHER_CLASSPATH, native)
            }
        }
    }

    /**
     * What a mod's class paths are made of, from the distribution the {@code pvz} configuration names:
     * the game as files (the one artifact no repository holds), and the version manifest's libraries as
     * Maven coordinates - {@link VersionJson} - of which the natives of this machine are a runtime class
     * path only. An application-style distribution has no manifest: everything it ships is a file.
     */
    private class DistSpec(
        val game: FileCollection,
        val libraries: List<String>,
        val natives: List<String>,
    )

    /**
     * Reads the {@code pvz} configuration and turns it into what a mod compiles, tests and launches
     * against. A version package (the release asset, or a local archive / directory holding one) is read
     * through its manifest; an application-style distribution ({@code lib/} of jars) is read as the class
     * path it is, which is how an archive of a release cut before the package naming still builds.
     *
     * <p>Everything that can be wrong about the notation is reported here, while the build is still
     * being configured: a coordinate that is not the game, a version it does not state, a local path
     * that is not there, or an asset a release directory does not hold.
     */
    private fun resolvePvzDist(project: Project, pvz: Configuration, dist: TaskProvider<PvzDistTask>): DistSpec {
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
            // The manifest has to be read while the build is still being configured, because the
            // coordinates it names have to be on the configurations before those are resolved - so the
            // asset is obtained here instead of by the task. It is the same download either way: one
            // cached copy per version (`caches/pvz`), and the task finds it there and only unpacks it.
            val cache = File(project.gradle.gradleUserHomeDir, PVZ_CACHE_DIRECTORY).toPath()
            val zip = PvzDist.cachedAsset(cache, version)
            PvzDist.download(version, urls, zip, project.gradle.startParameter.isOffline, project.logger)
            return specOf(project, dist, zip, buildDistDirectory(project), "the release of $version")
        }

        val source = local.iterator().next().toPath()
        if (Files.isDirectory(source)) {
            if (PvzDist.packageOfDirectory(source) != null) {
                // A version package that already is unpacked: used where it is, nothing copied.
                PvzDist.requireUsable(source, "The local distribution " + source)
                dist.configure { task -> task.localDistribution.set(source.toFile()) }
                return specOf(project, dist, source, source, "the local distribution " + source)
            }
            if (PvzDist.isDistribution(source)) {
                // An application-style distribution that already is unpacked: the same, read as the class
                // path it is (its lib/ jars), because there is no manifest to read coordinates from.
                PvzDist.requireUsable(source, "The local distribution " + source)
                dist.configure { task -> task.localDistribution.set(source.toFile()) }
                return DistSpec(offlineJars(project, source, dist), emptyList(), emptyList())
            }
            // A release directory: the version package of the version this build is against, by name.
            val asset = source.resolve(PvzDist.assetName(version))
            if (!Files.isRegularFile(asset)) {
                throw GradleException("The release directory " + source + " holds no " + PvzDist.assetName(version) +
                    " (" + version + " is the version this build is against): build the game first" +
                    " (./gradlew mcVersionZip in PvZ-Portable writes that file), point pvz at another version, or" +
                    " point it at a distribution zip / unpacked distribution directly.")
            }
            return fromArchive(project, dist, asset, "the distribution of the release directory " + source)
        }
        if (!Files.isRegularFile(source)) {
            throw GradleException("The local game distribution " + source + " does not exist: point pvz at the" +
                " distribution archive a build wrote (the game's mcVersionZip output), at an unpacked" +
                " distribution (a directory with the version package or a " + PvzDist.LIB + "/ of jars), or at a" +
                " directory of release archives.")
        }
        return fromArchive(project, dist, source, "the local distribution archive " + source)
    }

    /** What the distribution at {@code packageDirectory} (a zip, read without unpacking it) is. */
    private fun specOf(
        project: Project,
        dist: TaskProvider<PvzDistTask>,
        zip: Path,
        packageDirectory: Path,
        what: String,
    ): DistSpec {
        val manifest = PvzDist.packageOfZip(zip)
        return if (manifest != null) {
            packageSpec(project, dist, packageDirectory, manifest, what) { packageDirectory.resolve(manifest.gameJar) }
        } else {
            DistSpec(offlineJars(project, packageDirectory, dist), emptyList(), emptyList())
        }
    }

    /** Wires the {@code pvzDist} task to unpack one archive, and returns what the result is. */
    private fun fromArchive(project: Project, dist: TaskProvider<PvzDistTask>, zip: Path, what: String): DistSpec {
        dist.configure { task -> task.zip.set(zip.toFile()) }
        project.logger.info("pvz: {} is {}", what, zip)
        return specOf(project, dist, zip, buildDistDirectory(project), what)
    }

    /** The class path entries of a version package: its game jar as a file, its libraries as coordinates. */
    private fun packageSpec(
        project: Project,
        dist: TaskProvider<PvzDistTask>,
        packageDirectory: Path,
        packageContent: PvzDist.Package,
        what: String,
        game: () -> Path,
    ): DistSpec {
        val manifest = VersionJson.parse(packageContent.manifest, what)
        val libraries = manifest.javaLibraries().map { it.coordinate }
        // `group:artifact:version:classifier` is Gradle's notation for an artifact with a classifier,
        // which is exactly what a natives entry of the manifest is.
        val natives = manifest.nativeLibraries(VersionJson.Platform.current()).map { native ->
            native.coordinate + ":" + native.classifier
        }
        return DistSpec(gameFile(project, dist, game()), libraries, natives)
    }

    /** The game jar of a distribution as a class path entry, built by the task that unpacks it. */
    private fun gameFile(project: Project, dist: TaskProvider<PvzDistTask>, game: Path): FileCollection {
        val files: ConfigurableFileCollection = project.files(game.toFile())
        files.builtBy(dist)
        return files
    }

    /**
     * The {@code lib/&#42;.jar} of an application-style distribution: the launcher class path (the game,
     * the real Fabric Loader with Mixin and ASM, the SLF4J/Log4j stack and LWJGL with the natives of
     * every platform). This is the shape a release cut before the package naming has, and the shape
     * {@code distZip} writes; a version package instead names its libraries and they arrive as Maven
     * coordinates ({@link #configurePvz}), so this is empty for one.
     *
     * <p>They are the <em>files</em> and not the directory: a class path directory entry is a package
     * tree of {@code .class} files, so a directory holding jars contributes nothing (a real
     * installation puts the same list of jars on the command line, {@code java -cp "lib/&#42;"}).
     *
     * <p>When the distribution is unpacked from an archive, the jars do not exist while the build is
     * being configured, so the collection is resolved when it is used - after {@code pvzDist} has run,
     * which is what this collection says it is built by.
     */
    private fun offlineJars(project: Project, distribution: Path, dist: TaskProvider<PvzDistTask>): FileCollection {
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
     * {@code KnotClient} whose class path is
     * <ul>
     *   <li>the game distribution's jars (the game and the libraries the version package's JSON
     *       names) - added to the main runtime class path by {@link #configurePvz};</li>
     *   <li><b>this mod's own runtime class path</b>: its compiled classes and processed resources
     *       ({@code fabric.mod.json} among them), its {@code implementation} and
     *       {@code runtimeOnly} dependencies, exactly as Gradle resolves them; and</li>
     *   <li>the {@code include} configuration (Fabric Loom's {@code include}): the jars the mod
     *       carries inside itself are also on the class path of a development run, where the mod is
     *       the source set's output and not the built jar - so a nested mod is usable without
     *       unzipping it by hand.</li>
     * </ul>
     *
     * <p>A development run discovers the mods that are on the class path
     * ({@code -Dfabric.development=true} below), which is exactly how fabric-loom's
     * {@code runClient} loads the mod under development; so nothing has to be built, copied or
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
        val runDirectory = File(project.projectDir, "run")
        val mainSourceSet = project.extensions.getByType(SourceSetContainer::class.java)
            .getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        // What this mod is in a development run: the main source set's own output - the compiled
        // classes (build/classes/java/main, build/classes/kotlin/main) and the processed resources
        // (build/resources/main, where fabric.mod.json and the mixin configurations are). Its own
        // task dependencies (compileJava, compileKotlin, processResources) come with it, so a run
        // builds what it runs.
        //
        // Filtered to what exists *when the run is configured for execution*: a Kotlin mod's plugin
        // adds build/classes/kotlin/main to the output directories even when the mod has no Kotlin
        // source at all, and Fabric Loader refuses nothing but does warn about a class path entry that
        // is not there ("Class path entries reference missing files ... the game may not load
        // properly!" - Knot checks java.class.path). After the compile and resource tasks of the run
        // have been up to date or have run, this is exactly the output the mod has.
        val mainOutput = mainSourceSet.output.filter { file -> file.exists() }
        // The mod's runtime class path, resolved as one graph: this mod's `implementation` and
        // `runtimeOnly` dependencies plus the game distribution's jars (configurePvz adds them to the
        // launcher configuration), with the filtered output above in place of the source set's
        // unfiltered output directories.
        val runtimeClasspath = project.configurations.named(LAUNCHER_CLASSPATH)
        // The jars the mod carries inside itself (Fabric Loom's include): nested into the built jar,
        // and on the class path of a development run, where this project's output is not that jar.
        val included = JarInJar.configuration(project)
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
            task.description = "Runs the game through the real Fabric Loader with this mod's runtime class path."
            task.mainClass.set(LAUNCHER_CLASS)
            task.classpath = project.files(mainOutput, runtimeClasspath, included)
            task.workingDir = runDirectory
            task.doFirst { runDirectory.mkdirs() }
            // The game directory: Fabric Loader reads mods/ from it (as in PvZ-Portable's run task).
            task.jvmArgs("-Dpvz.gameDir=" + runDirectory.absolutePath)
            // This port is the only game provider that can be present, so skip the Minecraft
            // detection pass over the class path.
            task.jvmArgs("-Dfabric.skipMcProvider=true")
            // Development run: the game, this mod's own runtime class path and this mod itself are on
            // the class path, so let the loader discover the mods there (rather than only the jars in
            // run/mods/).
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
 * distribution, test classes, and whatever the mod declares for its tests) without this project's
 * own main output, so the mod under test can only be the jar in the {@code mods/} directory.
 * Source sets without resources contribute directories that do not exist; the loader warns about
 * those, so they are dropped.
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
 * see: a launch class path carries no {@code kotlin-stdlib} of its own, even when the mod declares
 * the stdlib for its tests (as a Kotlin mod does) - the copy it resolves {@code kotlin.*} from at
 * runtime is the one nested in {@code fabric-language-kotlin}'s jar, which the loader puts on the
 * class path when it loads that mod. The stdlib on this project's test class path exists for the
 * test JVM itself, and is left out of the fork, so the end-to-end test runs the mod under exactly
 * the runtime an installation provides - and its "where does the stdlib come from" probe has only
 * one possible answer.
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
