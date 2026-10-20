# loom

The mod toolchain of this workspace, as one Gradle plugin. It is published to the **Gradle Plugin
Portal** (`./gradlew publishPlugins`), which is how a mod gets it with the one line below and without
anything being published locally first; `./gradlew build publishToMavenLocal` still puts it into
`mavenLocal` for a developer of this workspace who wants the local copy (and for a version the portal
does not serve yet - a development version built here, see
[Publishing this plugin](#publishing-this-plugin)).

| | |
|---|---|
| plugin id | `cn.enaium.fabricpvz.loom` |
| artifact | `cn.enaium.fabricpvz:loom:1.0.14` |
| marker | `cn.enaium.fabricpvz.loom:cn.enaium.fabricpvz.loom.gradle.plugin:1.0.14` |
| portal | https://plugins.gradle.org/plugin/cn.enaium.fabricpvz.loom |

The plugin is written in Kotlin (`src/main/kotlin/cn/enaium/fabricpvz/loom/`): the same classes under
the same package and names, the same configurations, tasks and defaults as before. It is compiled
for Gradle's own JVM (`jvmTarget` 17, the oldest JVM Gradle 9 runs on), and its published POM
therefore declares the `kotlin-stdlib` its classes were compiled against — a Kotlin plugin cannot
leave its own runtime to whoever happens to have one. Nothing else is on that POM: the Kotlin Gradle
plugin is this project's build tool, not a dependency of the plugin or of a mod.

## Usage

```kotlin
plugins { id("cn.enaium.fabricpvz.loom") version "1.0.14" }

group = "cn.enaium.fabricpvz"
version = "1.0.0"
description = "…"   // published as the POM description

dependencies {
    // The game distribution this mod is built against. Optional: without it the toolchain takes the
    // release of the version it tracks (the asset pvz-portable-<version>.zip of its GitHub release).
    pvz("cn.enaium.fabricpvz:pvz-portable:1.0.0")
}
```

The version is required. With it Gradle resolves the plugin and its marker from the repositories of
the build's `pluginManagement` block, and the mods of this workspace list `gradlePluginPortal()` there
first: the portal serves every version it has been given, so nothing has to be published locally
before a mod can be built. `mavenLocal()` stays after the portal in those settings as a fallback for a
version the portal does not serve yet (a development version built here with
`./gradlew publishToMavenLocal`; 1.0.14 is the current one), and a version the portal serves always
resolves from the portal (see [Publishing this plugin](#publishing-this-plugin)).

What the plugin supplies is the game and nothing else — see [What it supplies](#what-it-supplies).
Everything else a mod uses is the mod's own declaration:

```kotlin
plugins {
    id("cn.enaium.fabricpvz.loom") version "1.0.14"
    kotlin("jvm") version "2.4.21"          // a Kotlin mod; a Java mod leaves it out
}

dependencies {
    // The stdlib: compile against it, never bundle it (fabric-language-kotlin provides it at runtime).
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")

    // The API this mod is built against (its aggregate jar, whose nested jars are its modules).
    compileOnly("cn.enaium.fabricpvz:fabric-api:1.2.1")
    testCompileOnly("cn.enaium.fabricpvz:fabric-api:1.2.1")
    testImplementation("cn.enaium.fabricpvz:fabric-api:1.2.1") { isTransitive = false }
    pvzRunClasspath("cn.enaium.fabricpvz:fabric-api:1.2.1") { isTransitive = false }

    // The end-to-end test: JUnit, and the language module a Kotlin mod runs on.
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("net.fabricmc:fabric-language-kotlin:1.14.2+kotlin.2.4.21") { isTransitive = false }
    pvzRunClasspath("net.fabricmc:fabric-language-kotlin:1.14.2+kotlin.2.4.21") { isTransitive = false }
}

// The Kotlin Gradle plugin would otherwise put the stdlib on `implementation` (and thus in the POM).
kotlin.stdlib.default.dependency=false   // gradle.properties
```

## The game distribution (`pvz`)

The game and the launcher of a mod are **one distribution** and not a list of coordinates: the
version package a release attaches (`PvZ-Portable-<version>/` with the game jar and its JSON
manifest next to it). The `pvz` configuration names it, the `pvzDist` task obtains it, and what it
supplies — the game jar as a file, the manifest's library list as Maven coordinates — is what a mod
compiles against, what its end-to-end test boots the loader on and what `runClient` launches: the
same jars in all three, so "compile against what runs" is true by construction instead of by keeping
two version lists in sync. (An older, application-style distribution — a `lib/` of jars, what
`distZip` writes — is read as the class path it is, for a vintage that has no manifest.)

A distribution ships **no mods**, and its `mods/` is not a class path of a mod build even when an
older archive has one: a mod jar on a launch class path is inert in an installation, and what a
player installs belongs in the game directory's own `mods/`. The `mods/` an archive brings along is
unpacked with the rest (so the extraction is the archive's content) but never becomes a class path
entry. That is also what makes a distribution of any vintage work: a released asset is cached per
version and never re-downloaded.

The version package the release carries has a JSON manifest next to the game jar, and its library
list is the second half of the game side: the real Fabric Loader, Mixin, ASM, the SLF4J/Log4j stack
and LWJGL with the natives of this machine. Those arrive as Maven coordinates (the natives are a
runtime class path only), so a mod compiles against exactly the versions the launcher loads.

| what a build may write | where the distribution comes from |
|---|---|
| nothing | `pvz("cn.enaium.fabricpvz:pvz-portable:1.0.0")`, the version of `PvzToolchain` |
| `pvz("cn.enaium.fabricpvz:pvz-portable:<version>")` | that version's GitHub release asset `pvz-portable-<version>.zip` |
| `pvz(files("<zip>"))` | a distribution archive on disk (e.g. what `./gradlew distZip` just wrote in `PvZ-Portable`) |
| `pvz(files("<directory>"))` | the unpacked distribution itself, used in place, nothing copied |
| `pvz(files("<directory-of-zips>"))` | a *release directory*: the `pvz-portable-<version>.zip` of the version the build is against, e.g. `PvZ-Portable/build/distributions` |

How it is obtained and where it is kept:

- **Release** (the two coordinate forms): the asset is downloaded once into
  `$GRADLE_USER_HOME/caches/pvz/pvz-portable-<version>.zip` (with its SHA-256 in a `.sha256` sidecar
  next to it, so the artifact is identifiable after the fact) and unpacked into
  `build/pvz/dist/` of the mod project, the `pvzDist` task's output. A cached asset is never
  re-downloaded — an offline build of a version that has been used before works (`--offline` with an
  empty cache fails with a message saying exactly that) — and an interrupted download never leaves a
  truncated zip behind (it lands as `.part` and is moved into place once complete).
- **Local archive or release directory**: no download; the archive is unpacked the same way.
- **Unpacked distribution directory**: used where it is — the two directories are inputs, not copies.
- The tag of a release is the plain version (e.g. `1.0.0`), with `v<version>` tried as a second
  attempt. A release host other than
  `https://github.com/FabricPvZ/pvz-portable/releases/download` is set with the `pvz.release.baseUrl`
  Gradle property (a mirror, or a directory served over `file:` / HTTP — that is how the download path
  of a version whose release does not exist yet gets tested).
- Unpacking is idempotent and self-checking: a marker file next to the jars records the archive
  (its path and SHA-256) and the jars that came out of it (name and size), so an unchanged archive is
  not unpacked again, while a rebuilt archive, another distribution, or a jar that was deleted or
  replaced by hand leads to a fresh unpacking — the class path is never one jar short. The new
  unpacking replaces the directory in a single move, so it is also never mixed with the previous one.

The failure modes are meant to be readable, and every one of them says what to do:

```
No game distribution release asset pvz-portable-9.9.9.zip for cn.enaium.fabricpvz:pvz-portable:1.0.0:
the release of that version has no distribution attached.
Tried:
  https://github.com/FabricPvZ/pvz-portable/releases/download/9.9.9/pvz-portable-9.9.9.zip -> HTTP 404
  ...
The release directory …/build/distributions holds no pvz-portable-1.0.0.zip (1.0.0 is the version this
build is against): build the game first (./gradlew distZip in PvZ-Portable writes that file), …
pvz(cn.enaium.fabricpvz:fabric-api:1.0.0) is not the game: the pvz configuration takes the game
distribution, pvz("cn.enaium.fabricpvz:pvz-portable:1.0.0") for the default version.
pvz takes one game distribution, not 2: [1.0.0] [<zip>]. Point it at one version, or at one local
distribution.
```

Two things worth knowing:

- **A release runs on every platform.** `lib/` carries the LWJGL native libraries of all six
  platforms a release supports (windows `natives-windows` / `natives-windows-arm64`, linux
  `natives-linux` / `natives-linux-arm64`, macos `natives-macos` / `natives-macos-arm64`), so one
  zip runs anywhere LWJGL's SDL3 build does: the distribution is not tied to the machine that
  built it.
- **The workspace's own mods take the release**, not a distribution on disk:
  `pvz("cn.enaium.fabricpvz:pvz-portable:1.0.0")` is the line in their build scripts, so each of them
  is built against the very asset the game's release carries — downloaded once, from the release of
  the version the toolchain tracks, and kept in `caches/pvz` (point one at a distribution on disk,
  `pvz(files("../PvZ-Portable/build/distributions"))`, while working on the game's own code).

A distribution is also how an installation is checked without the project it came from: unpack a
release zip anywhere, point `pvz` at that directory (`pvz(files("<unpacked-dist>"))`) and the mod's
`runClient`/tests run against those jars, with nothing of `PvZ-Portable`'s build directory on the
path. (Nothing besides the game comes from the toolchain: the API, the language module, JUnit and the
stdlib are the mod's own declarations, resolved from the repositories its settings declare.)

## What it supplies

The game, and the libraries the online version package's JSON names next to the game jar. Nothing
else — a mod's language plugin and stdlib, its test framework, its API and its language module are
its own declarations.

| | |
|---|---|
| `compileOnly` | the game jar of the version package (a file, built by `pvzDist`) and the libraries its manifest lists (the real Fabric Loader, Mixin, ASM, SLF4J/Log4j, LWJGL), as Maven coordinates |
| `testImplementation` | the same game jar and libraries: a mod's end-to-end test starts the real loader on exactly the jars its sources compile against |
| `testRuntimeOnly` | the native libraries of this machine (the manifest's natives entries): a native jar carries no class, so it is never a compile class path |
| `pvzRunClasspath` (not published) | the distribution's jars, and whatever the mod declares here (its API, `fabric-language-kotlin`, its own runtime libraries): what `runClient` launches *besides this mod itself* |
| `include` | the mods this mod carries inside itself (see [Jar-in-jar](#jar-in-jar-include)) |
| tasks | `pvzDist` (obtains the game distribution: downloads the release asset of the version or takes the local archive / unpacked distribution, and unpacks it into `build/pvz/dist/`), `runClient` (boots `net.fabricmc.loader.impl.launch.knot.KnotClient` with the distribution's jars, `pvzRunClasspath` and *this mod's own output* on the class path, development mode, `-Dpvz.gameDir=run` — see [The development run](#the-development-run)) |
| conventions | Java 25 toolchain and `--release 25`, UTF-8, jars with no bundled dependencies (a dependency is never shaded; a nested mod is `include`, and it stays a mod jar), `useJUnitPlatform()`, the test fork's `pvz.test.modJar` / `pvz.test.forkClasspath` system properties, a test fork class path without a Kotlin runtime of its own (the one a mod brings is what it runs on), headless SDL + first thread on macOS, the publication of the mod jar (`mavenLocal` coordinates, MIT) |

The game side is stated in one place, `PvzToolchain` (the game's version, the release host and the
Java release, 25): a mod build never repeats the game's coordinate, and the loader, Mixin, ASM,
SLF4J and LWJGL are not stated anywhere at all — they are the libraries the version package's JSON
names, at whatever version the release carries.

## The mod's own dependencies

Everything a mod uses besides the game is declared by the mod, in its own build file, with the
version it was built against. That is the point of the split: the toolchain cannot know which Kotlin
the mod compiles with, which API it links against, or which test framework it wants.

- **Kotlin**: `kotlin("jvm") version "2.4.21"` in the `plugins` block (2.4 is the first release line
  that accepts `jvmTarget 25` and can read the game's Java 25 class files), the stdlib as
  `compileOnly` plus `testImplementation`, and `kotlin.stdlib.default.dependency=false` in
  `gradle.properties` (so the Kotlin Gradle plugin's default `implementation(kotlin-stdlib)` does not
  reach the mod's POM). A Java mod leaves all of it out.
- **JUnit**: `testImplementation(platform("org.junit:junit-bom:6.1.3"))`,
  `testImplementation("org.junit.jupiter:junit-jupiter")` and
  `testRuntimeOnly("org.junit.platform:junit-platform-launcher")` — the platform launcher is what the
  toolchain's `useJUnitPlatform()` needs at runtime.
- **`fabric-language-kotlin`**: on `testRuntimeOnly` and `pvzRunClasspath`, `isTransitive = false`
  (the loader, Mixin, ASM and the Log4j stack of a launch class path are the distribution's jars;
  pulling the module's POM in as well would put a second Fabric Loader of the same version on that
  class path, which the loader refuses: "duplicate fabric loader classes found on classpath"). Its
  nested `kotlin-stdlib` is the runtime a Kotlin mod resolves `kotlin.*` from.
- **The API**: the aggregate jar of `cn.enaium.fabricpvz:fabric-api` — `compileOnly` and
  `testCompileOnly` (a test source set does not inherit `compileOnly`) *with* its POM dependencies,
  and `testImplementation` + `pvzRunClasspath` *without* them (`isTransitive = false`). It is one
  coordinate and one jar, because the API ships the way Fabric API does: the aggregate carries its
  five feature modules (`api-base`, `api-events`, `api-plant`, `api-resource`, `api-zombie`)
  **inside itself** (see [Jar-in-jar](#jar-in-jar-include)), so a mod depends on one id and an
  installation has one jar to drop into `mods/`. Compilation needs the modules themselves (javac and
  kotlinc read jar files, not jars nested inside a jar, and the aggregate's POM names them), while at
  runtime Fabric Loader reads the nested jars out of the aggregate — a class path that carried the
  modules separately as well would resolve every module twice. A development run discovers mods on
  the class path (`-Dfabric.development=true`, which `runClient` and the test fork both set), so the
  API is loaded from that one jar, with the modules the loader finds inside it — the loader's own mod
  list prints them nested under `fabric-api`:

  ```
  Loading 25 mods:
  	- fabric-api 1.0.0
  	   |-- api-base 1.0.0
  	   |-- api-events 1.0.0
  	   |-- api-plant 1.0.0
  	   |-- api-resource 1.0.0
  	   \-- api-zombie 1.0.0
  	- pvz-content-mod 1.0.0
  ```

## Jar-in-jar (`include`)

A mod may carry another mod inside its own jar, Fabric Loom's `include(...)`, which is how the API
aggregate ships its modules and how a mod can ship a library without asking the player to install it:

```kotlin
dependencies {
    include("com.example:some-library:1.0")   // a coordinate, a project(...), or files(...)
}
```

The toolchain does the rest (`JarInJar`), and the shape is Fabric Loader's own. `include(...)` is the
configuration the plugin creates (`include`), so the Kotlin DSL's type-safe accessor is the call
itself; `add("include", …)` is the same thing and always available.

- every included jar is copied into the built jar under `META-INF/jars/`, **as it is** — nothing is
  renamed, merged or remapped, so each nested jar keeps its own `fabric.mod.json`, id, version and
  entry points, which is what the loader reads (it loads a nested jar as a mod of its own);
- the packaged `fabric.mod.json` gets a `jars` array listing exactly the entries that are in
  `META-INF/jars/`, one entry each: `"jars": [ { "file": "META-INF/jars/<name>.jar" } ]`. The array is
  written into the archive the `jar` task just built and is *set*, not appended to, so re-running the
  task can neither nest a jar twice nor list one twice, and a jar with nothing included is not touched
  at all — it is byte for byte the jar the project built before;
- a name two included jars share is a build failure naming both files (a nested jar is addressed by
  its name inside the mod jar), and a mod jar that carries nested jars but has no `fabric.mod.json` to
  list them in is a build failure too: the loader would never read them.

The project that uses this in this workspace is the API aggregate itself
(`fabric-api/build.gradle.kts`). It is *not* a mod project of the toolchain (no game code, no `pvz`
distribution, its own publication), so it applies the plugin with `apply false` —
`id("cn.enaium.fabricpvz.loom") version "1.0.14" apply false` — which is what puts the plugin's
classes on its build script class path — and calls the mechanism directly, `JarInJar.include(...)`
with the configuration `JarInJar.configuration(project)`. One mechanism, one implementation; the
plugin is only the wiring a mod gets for free.

## Kotlin

A mod may be written in Kotlin, and then says so itself: `kotlin("jvm") version "2.4.21"` in its
`plugins` block compiles `src/main/kotlin` / `src/test/kotlin` at `jvmTarget` 25, so the mod's Kotlin
classes are the class file version the game's Java 25 classes are. Mixins stay Java (a Mixin
configuration is a Java class Mixin reads as such). The toolchain does not apply the Kotlin plugin:
a Java mod never downloads it, and a Kotlin mod states the version it compiles with.

Three rules follow from Fabric Loader's own behaviour and the stdlib's:

- **Entry points are Kotlin `class`es, not `object`s.** Fabric Loader instantiates a `ModInitializer`
  entry point reflectively through its no-argument constructor; a Kotlin `object` has only a private
  one, and reaching it needs a Fabric *language adapter* (the `"kotlin"` one), i.e. an entry point
  entry shaped `{"adapter": "kotlin", "value": "…"}`. The adapter is available once the mod declares
  `fabric-language-kotlin`, but the mods of this workspace keep the plain class: a class is what the
  loader can instantiate with no adapter in the path at all.
- **The stdlib is not bundled.** The mod sets `kotlin.stdlib.default.dependency=false` in its
  `gradle.properties` (otherwise the Kotlin Gradle plugin's default `implementation(kotlin-stdlib)`
  reaches the mod's POM) and declares the stdlib `compileOnly` (plus `testImplementation`, for its
  own test JVM), so its jar stays "own classes, resources and mixins only" (the jar task treats a
  duplicate as a bug).
- **The runtime stdlib is `fabric-language-kotlin`'s.** The copy a Kotlin mod resolves `kotlin.*`
  from at runtime is the one the real Fabric language module carries — its nested `kotlin-stdlib`,
  which the loader adds to the class path of every mod. The mod declares the module on
  `testRuntimeOnly` and `pvzRunClasspath` (both `isTransitive = false`) and in `fabric.mod.json`
  (`"depends": { "fabric-language-kotlin": ">=1.14.2" }`); an installation keeps the jar in `mods/`
  like any other mod. Two Kotlin mods in one installation therefore share one stdlib; if each
  bundled its own, the loader's `KnotClassLoader` would resolve every `kotlin.*` name from whichever
  mod jar it scans first and shadow the other copy, so two mods built against different stdlib
  versions would silently share a mixture of both. The toolchain only keeps a stdlib a mod declares
  for its test JVM off the **fork's** launch class path (the fork is the loader's, and the loader
  provides the stdlib through the language module).

## Logging

A mod logs with `LoggerFactory.getLogger(<mod id>)` and nothing else: the SLF4J API is one of the
jars of the game distribution (`lib/slf4j-api-*.jar`, on the compile class path like the rest of
them). The implementation (Log4j), its SLF4J binding and the configuration (`log4j2.xml`) are launch
libraries of the game distribution and thus jars of the distribution too,
which also points Fabric Loader's own log at the same stack, so Fabric Loader's lines, the Mixin
subsystem's lines and the mods' lines are one stream in one format. See `../PvZ-Portable/GAME-PROVIDER.md`.

## Why this plugin is not fabric-loom

This project is called `loom` for the role it plays for a mod author (one line in the mod build
instead of a hand-written toolchain), but it is not the Minecraft `fabric-loom` plugin — that one
cannot be used for this port, measured, not assumed:
without a `minecraft` dependency it fails to configure (`Configuration 'minecraft' has no
dependencies`), pointing `minecraft` at the game gives `Failed to find minecraft version: 1.0.0`
(Loom looks the version up in Mojang's manifest), giving it a real `com.mojang:minecraft` + yarn
makes it install its own repository set (dropping the settings' `mavenLocal`), inject Minecraft's
library set, and remap the jar to `intermediary`; and it does not supply the loader or Mixin at all
(`import org.spongepowered.asm.mixin.Mixin` fails to compile with Loom alone). Full transcript:
`../verification/VERIFICATION.md §31`.

One thing it does share with Loom is the shape of "the game a mod is built against": Loom's
`minecraft("com.mojang:minecraft:…")` + `mappings(…)` pair is this plugin's `pvz` configuration
(`pvz("cn.enaium.fabricpvz:pvz-portable:…")`), a coordinate in a `dependencies` block rather than a
hand-written path. What that coordinate resolves to is a whole distribution and not a Mojang version
plus a mapping set: there is no external manifest to look the version up in and nothing to remap, and
the classes a mod compiles against are the classes the loader loads.

Repositories deliberately stay the project's business (declared in `settings.gradle.kts`): a plugin
that installs its own repository set would silently drop `mavenLocal`.

## Publishing this plugin

```bash
./gradlew publishPlugins       # to the Gradle Plugin Portal (what a mod's one line resolves) - runs `test` first
./gradlew publishToMavenLocal  # to mavenLocal, for a developer of this workspace
```

The portal publication is `com.gradle.plugin-publish` (`build.gradle.kts`): it uploads the two
publications `java-gradle-plugin` registers - `cn.enaium.fabricpvz:loom` and the plugin marker
`cn.enaium.fabricpvz.loom:cn.enaium.fabricpvz.loom.gradle.plugin` - together with the listing
metadata the plugin page and its search entry are built from (`website`, `vcsUrl`, `displayName`,
`description`, `tags`, and the declared Gradle-feature compatibility). The publisher's API key comes
from the Gradle user properties (`gradle.publish.key` / `gradle.publish.secret`) or from the
`GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET` environment variables, never from this repository.

A plugin id the portal has not seen before is queued for review by Gradle engineers; until that review
accepts it, `publishPlugins` reports the submission as pending acceptance and the marker is not served
by `plugins.gradle.org/m2` yet, so nobody can resolve the plugin from the portal in the meantime. That
is what 1.0.0 went through (the id is accepted since 2026-10-15); a version published after it - 1.0.9
is one - is served as soon as `publishPlugins` reports success. (The version that is published is the
one a mod states - the artifact is published under this project's `version`, `1.0.13`.)

**Published only after it has been tested locally.** The rule of this workspace, and the reason it
exists: 1.0.3 was published without it and could not compile a single mod - it unpacked the version
package by flattening the `PvZ-Portable-<version>/` directory away, so the jar the manifest named was
not the file the extraction wrote, and only a *mod* build could see it. `publishPlugins` therefore
depends on `test`, which includes the functional test a unit test cannot replace
(`PvzPluginFunctionalTest`, Gradle's TestKit): it runs a real build that applies this plugin, names a
released game version exactly as a mod does, and compiles a class that uses the game, Fabric Loader
and LWJGL - the download of the release's version package, its layout, the game jar as a file and the
libraries the manifest names as Maven coordinates, all in one build. A version whose game version the
release does not carry fails there instead of in a user's build.

The checklist for a release, in order:

1. `./gradlew build` here - the unit tests (the manifest parser, the platform-to-natives rules, the
   unpacking of both layouts, the functional test above);
2. a mod of the workspace against it (`pvz("cn.enaium.fabricpvz:pvz-portable:<version>")`, a clean
   `build/pvz/dist`), `./gradlew build` in one of them;
3. `./gradlew publishPlugins` (which runs the tests again, as the gate) and the portal's
   `maven-metadata.xml` shows the new `latest`/`release`.

**State of the publications:** 1.0.0 (the first id acceptance), 1.0.1 (the runClient-that-loads-the-
mod-from-the-class-path release), 1.0.2 (the game's package naming: it downloaded
`pvz-portable-<version>-offline.zip`), 1.0.3 (the version package, with the layout bug above), 1.0.4
 (that bug fixed), 1.0.5 (the version package's manifest is read and its libraries are Maven
coordinates, linking against API 1.2.0), 1.0.6 (the game's 1.2.2), 1.0.7 (the game's 1.2.3, whose
resource search survives a launcher's remapped `user.home`), 1.0.8 (the game's 1.2.4, whose saves stay
where a plain run puts them) and 1.0.9 (the game's 1.2.5, whose logged build identity - product,
version, build number, commit date - comes from the build) are on the portal, with
`latest`/`release` = 1.0.9.

**1.0.14** narrows the toolchain to the game plus the libraries of the online version package's JSON,
and leaves the Kotlin plugin and stdlib, JUnit, `fabric-language-kotlin` and the API to each mod. It
is built and published to `mavenLocal`; the mods of this workspace state `version "1.0.14"` and list
`gradlePluginPortal()` first with `mavenLocal()` after it until the portal serves this version.
(1.0.12 and 1.0.13 were created on the portal by upload attempts that died while posting the
artifacts - this network cannot reach the portal's artifact host - and the portal does not allow a
version to be published twice, so the new contract ships as 1.0.14. 1.0.10 and 1.0.11 were never
usable and carry no artifacts.)

## License

MIT (see `LICENSE`). The game port itself (`PvZ-Portable`) is LGPL-3.0-or-later with the PopCap framework licence.
