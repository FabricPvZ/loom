package cn.enaium.fabricpvz.loom

import java.nio.file.Files
import java.nio.file.Path

import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir

/**
 * The plugin, as a mod build sees it: a real Gradle build (TestKit, this plugin's class path) that
 * applies `cn.enaium.fabricpvz.loom`, names a released game version exactly the way a mod does, and
 * compiles a class that uses the game, Fabric Loader and LWJGL.
 *
 * <p>This is the local test a version of the plugin has to pass *before* it is published - the two
 * things it covers are the two that a unit test cannot: that the release asset is downloaded and
 * unpacked into the layout the manifest names, and that the game jar (a file) and the libraries the
 * manifest lists (Maven coordinates resolved by Gradle) both end up on a mod's compile class path.
 * Version 1.0.3 shipped without this test and could not compile a mod at all.
 *
 * <p>It builds against the version the toolchain defaults to ([PvzToolchain]'s game version), so a
 * plugin published with a version the release does not carry fails here rather than in a user's
 * build.
 */
class PvzPluginFunctionalTest {

    @Test
    fun `a mod build compiles against the release's version package`(@TempDir directory: Path) {
        // The repositories a mod of this workspace declares (and the guide's template carries): the API
        // is only in mavenLocal and the loader only on Fabric's maven, while the libraries the version
        // manifest names come from Maven Central.
        directory.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "functional-test-mod"

            dependencyResolutionManagement {
                repositories {
                    mavenLocal()
                    mavenCentral()
                    maven {
                        name = "Fabric"
                        url = uri("https://maven.fabricmc.net/")
                    }
                }
            }
            """.trimIndent()
        )
        directory.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("cn.enaium.fabricpvz.loom")
            }

            dependencies {
                pvz("${PvzToolchain.GAME}")
            }

            group = "test"
            version = "1.0.0"
            """.trimIndent()
        )
        val source = directory.resolve("src/main/java/test/Probe.java")
        source.parent.createDirectories()
        // One class that names the game, the loader and LWJGL: the three halves of a mod's compile
        // class path (the game jar as a file, the loader and a game library as Maven coordinates).
        source.writeText(
            """
            package test;

            import cn.enaium.fabricpvz.portable.lawn.Board;
            import net.fabricmc.api.ModInitializer;
            import org.lwjgl.sdl.SDL;

            public class Probe implements ModInitializer {
                @Override
                public void onInitialize() {
                    Class<?> game = Board.class;
                    Class<?> loader = ModInitializer.class;
                    Class<?> lwjgl = SDL.class;
                    if (game == null || loader == null || lwjgl == null) {
                        throw new IllegalStateException();
                    }
                }
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(directory.toFile())
            .withPluginClasspath()
            .withArguments("build", "--stacktrace")
            .forwardOutput()
            .build()

        assertTrue(
            result.task(":compileJava")?.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE),
            "the mod's compilation has to succeed against the distribution"
        )

        // The distribution itself: the version package of the game, unpacked by the pvzDist task, with
        // the game jar where the manifest names it.
        val project = PvzToolchain.GAME_VERSION
        val dist = directory.resolve("build/pvz/dist")
        val packageContent = PvzDist.packageOfDirectory(dist)
            ?: error("the distribution of ${PvzToolchain.GAME} was not unpacked into $dist")
        assertTrue(
            Files.isRegularFile(dist.resolve(packageContent.gameJar)),
            "the game jar the manifest names (${packageContent.gameJar}) has to be unpacked"
        )
        assertTrue(
            packageContent.gameJar.startsWith("PvZ-Portable-$project/"),
            "the package keeps its own layout: ${packageContent.gameJar}"
        )
    }
}
