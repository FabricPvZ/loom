// The mod toolchain: one Gradle plugin, published to the Gradle Plugin Portal (and, for a developer of
// this workspace, to mavenLocal as well), that a mod applies with a single line
// (`plugins { id("cn.enaium.fabricpvz.loom") version "1.0.6" }`) to get the game distribution (the
// game, the API, the real Fabric Loader, Mixin, ASM, SLF4J/Log4j and LWJGL, via the `pvz`
// configuration), the launcher and test class paths and the conventional tasks (run the game with the
// mod, run the mod's tests against the real loader) without declaring any of those artifacts by hand.
//
// It exists instead of fabric-loom because Loom is a Minecraft build plugin: it cannot even be
// configured without a `minecraft` dependency on a real Mojang version plus mappings, it injects
// Minecraft's own library set, it remaps the built jar to intermediary, and it does not supply the
// loader or Mixin anyway (see ../verification/VERIFICATION.md, "Loom 实验").
import org.gradle.plugin.compatibility.compatibility
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-gradle-plugin`
    `maven-publish`
    // The publication of the portal (plugins.gradle.org): which is how a mod gets this plugin without
    // anyone building `loom` first. It reads the publisher's API key from the Gradle user properties
    // (`gradle.publish.key` / `gradle.publish.secret`, or the `GRADLE_PUBLISH_KEY` /
    // `GRADLE_PUBLISH_SECRET` environment variables) and publishes the two publications
    // `java-gradle-plugin` already registers: this jar (`cn.enaium.fabricpvz:loom`) and the plugin
    // marker (`cn.enaium.fabricpvz.loom.gradle.plugin`). The `mavenLocal` publication stays - a
    // developer of this workspace still gets the plugin that way with `publishToMavenLocal`.
    id("com.gradle.plugin-publish") version "2.2.1"
    // The toolchain itself is written in Kotlin (the same five classes, same package, same names).
    // The version is the one PvzToolchain.KOTLIN states and the one a mod's `kotlin-stdlib` comes
    // from: the plugin compiles the mods' Kotlin, so its own compiler is that same release line.
    kotlin("jvm") version "2.4.21"
}

group = "cn.enaium.fabricpvz"
// The plugin's own version, stated once and nowhere else: a mod names it in its `plugins` block and
// the marker publication carries it. 1.0.6 is the game's 1.2.2 (the version package whose asset
// index a launcher can satisfy locally; 1.0.5 linked against API 1.2.0 and 1.0.4 followed the game's package naming and reads the version
// package it downloads instead of putting the archive's jars on a class path: the game jar is a file
// and every library the package's manifest names is a Maven coordinate (1.0.3 fixed the unpacking of the package layout; 1.0.2 loaded the
// offline package's lib/ as files; 1.0.1 loaded the unsuffixed asset; 1.0.0 copied the mod's jar into
// run/mods) - and 1.0.5 links against API 1.2.0). The mods of this workspace name the version they
// were built with.
version = "1.0.6"

// The Kotlin Gradle plugin the toolchain applies to every mod (`org.jetbrains.kotlin.jvm`), so that a
// mod never has to declare the plugin or its version: it is on this plugin's own class path, which is
// what the mod's build script class path resolves (the publication below publishes it as a runtime
// dependency of `cn.enaium.fabricpvz:loom`).
//
// The version is pinned to PvzToolchain.KOTLIN, which is also the kotlin-stdlib version a mod
// compiles against; the two must stay in sync with the runtime stdlib, which is the one
// fabric-language-kotlin (the language module this toolchain ships) nests in its jar (see
// PvzToolchain.FABRIC_LANGUAGE_KOTLIN).
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")

    // The plugin's own sources are Kotlin now, so the Kotlin Gradle plugin adds `kotlin-stdlib` to
    // `implementation` (the usual default), and it therefore becomes a runtime dependency of
    // `cn.enaium.fabricpvz:loom` - the version this plugin's classes were compiled against, which is
    // the only stdlib a consumer of this plugin should load. That is the one deliberate addition to
    // the published POM next to the Java version's dependency set.

    // The jar-in-jar mechanism (JarInJar) rewrites the packaged fabric.mod.json, i.e. it parses and
    // re-serializes JSON. That is Groovy's (groovy.json.JsonSlurper / JsonOutput), which ships with
    // Gradle and is on the class path of every plugin at runtime; only the compilation needs to be
    // told so. compileOnly, so the published POM of the plugin declares no dependency Gradle already
    // has on the plugin's own class path (a mod build would otherwise resolve a second Groovy).
    compileOnly(localGroovy())

    // The plugin's own tests: pure functions of the version manifest (parsing, and which native
    // libraries belong to a platform), the unpacking of both distribution layouts, and a functional
    // test that runs a real mod build with this plugin (Gradle's TestKit). The same Groovy is on the
    // test class path through Gradle's.
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// A version is published only after it has been tested locally (the rule of this workspace, and the
// reason 1.0.3 shipped a layout bug that only a mod build could see): publishing runs the tests,
// which include the functional test below - a real mod build against this plugin, resolving the
// release's version package and compiling a class that uses the game, the loader and LWJGL.
tasks.named("publishPlugins") {
    dependsOn(tasks.named("test"))
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// The Kotlin half of the same rule: the compiler Kotlin 2.4 runs on the Java 25 toolchain (it has to,
// to read the game's Java 25 class files) but emits the class file version the Java sources do.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// The portal's listing metadata: the project URL (the sources, which are the plugin's documentation as
// well - see README.md) and what the plugin is, per plugin. The portal requires all of it for a plugin
// it has not seen before, and it is what the plugin's page and search entry are built from.
gradlePlugin {
    website.set("https://github.com/FabricPvZ/loom")
    vcsUrl.set("https://github.com/FabricPvZ/loom")
    plugins {
        create("loom") {
            id = "cn.enaium.fabricpvz.loom"
            implementationClass = "cn.enaium.fabricpvz.loom.PvzModPlugin"
            displayName = "loom - pvz-fabric mod toolchain"
            description = "Builds a mod of the FabricPvZ workspace: the game distribution (pvz), the loader's launch/test class paths as its jars, and the runClient task."
            tags.set(listOf("fabric", "mixin", "pvz", "minecraft", "mod"))
            // The portal asks which Gradle features a plugin supports. This one is not verified to
            // work with the configuration cache (it touches other projects' configurations and
            // registers tasks in `afterEvaluate`), so it declares the feature unsupported rather
            // than claim a support nobody measured.
            compatibility {
                features {
                    configurationCache = false
                }
            }
        }
    }
}

// The plugin runs inside Gradle's own JVM, so it targets the oldest JVM Gradle 9 supports (17)
// instead of this workspace's Java 25: the mods' toolchain (25) is applied by the plugin at build
// time, it does not have to be the JVM loading the plugin.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 17
}

tasks.withType<ValidatePlugins>().configureEach {
    enableStricterValidation = true
}

// java-gradle-plugin already registers the artifact publication ('pluginMaven': this jar under
// cn.enaium.fabricpvz:loom) and the plugin marker
// (cn.enaium.fabricpvz.loom.gradle.plugin); only the POM metadata is ours to add.
publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") {
            pom {
                name.set("loom")
                description.set("Gradle plugin (id cn.enaium.fabricpvz.loom) that supplies the mod toolchain of the PvZ-Portable Fabric workspace")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}
