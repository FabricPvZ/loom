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
| artifact | `cn.enaium.fabricpvz:loom:1.0.4` |
| marker | `cn.enaium.fabricpvz.loom:cn.enaium.fabricpvz.loom.gradle.plugin:1.0.4` |
| portal | https://plugins.gradle.org/plugin/cn.enaium.fabricpvz.loom |

The plugin is written in Kotlin (`src/main/kotlin/cn/enaium/fabricpvz/loom/`): the same classes under
the same package and names, the same configurations, tasks and defaults as before. It is compiled
for Gradle's own JVM (`jvmTarget` 17, the oldest JVM Gradle 9 runs on), and its published POM
therefore declares the `kotlin-stdlib` its classes were compiled against — a Kotlin Gradle plugin
cannot leave its own runtime to whoever happens to have one; the `kotlin-gradle-plugin` it puts on a
mod's class path is unaffected by that.

## Usage

```kotlin
plugins { id("cn.enaium.fabricpvz.loom") version "1.0.5" }

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
first: the portal serves the version they state, so nothing has to be published locally before a mod
can be built. `mavenLocal()` stays after the portal in those settings as a fallback for a version the
portal does not serve yet (a development version built here with `./gradlew publishToMavenLocal`); a
version the portal serves, 1.0.1 among them, resolves from the portal (see
[Publishing this plugin](#publishing-this-plugin)).

## The game distribution (`pvz`)

The game and the launcher of a mod are **one distribution** and not a list of coordinates: the
`application` plugin's `distZip` layout, which is what a release attaches and what an installation
is. Its `lib/` is the launcher class path (the game jar, the game provider and, through it, the real
Fabric Loader with Mixin and ASM, the SLF4J/Log4j stack, and LWJGL with the native libraries of
every platform the game runs on — one release runs everywhere). The `pvz` configuration names it, the
`pvzDist` task obtains it, and its `lib/*.jar` are what a mod compiles against, what its end-to-end
test boots the loader on and what `runClient` launches — the same jars in all three, so "compile
against what runs" is true by construction instead of by keeping two version lists in sync.

A distribution ships **no mods**, and its `mods/` is not a class path of a mod build even when an
older archive has one: a mod jar on a launch class path is inert in an installation, and what a
player installs belongs in the game directory's own `mods/`. The one mod every mod of this workspace
needs — the API — is therefore a coordinate of the toolchain (`cn.enaium.fabricpvz:fabric-api`, see
[The API](#the-api) below), and the `mods/` an archive brings along is unpacked with the rest (so
the extraction is the archive's content) but never becomes a class path entry. That is also what
makes a distribution of any vintage work: a released asset is cached per version and never
re-downloaded, so a build may be pointed at one whose `mods/` still holds an older API.

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
path. (The API still comes from `mavenLocal`, because it is the toolchain's own coordinate and not
something a distribution carries: it is the one artifact of this workspace a mod's build resolves from
a repository rather than from the plugin or from the game's release.)

## What it supplies

| | |
|---|---|
| `compileOnly` | the launcher class path of the distribution (`build/pvz/dist/lib/*.jar`: the game, the loader, Mixin, ASM, SLF4J/Log4j, LWJGL), `org.jetbrains.kotlin:kotlin-stdlib`, and the API (`cn.enaium.fabricpvz:fabric-api`, see [The API](#the-api)) |
| `testCompileOnly` | the API too: a test source set does not inherit `compileOnly`, and a mod's end-to-end test compiles against the same API its sources do |
| `testImplementation` | the distribution's jars, the Kotlin stdlib and the API jar *without* its POM dependencies |
| `testRuntimeOnly` | `net.fabricmc:fabric-language-kotlin` (the language module a Kotlin mod runs on, without its POM dependencies) and the JUnit platform launcher |
| `pvzRunClasspath` (not published) | the distribution's jars, `fabric-language-kotlin` and the API jar (without its POM dependencies): what `runClient` launches *besides this mod itself* |
| `include` | the mods this mod carries inside itself (see [Jar-in-jar](#jar-in-jar-include)) |
| tasks | `pvzDist` (obtains the game distribution: downloads the release asset of the version or takes the local archive / unpacked distribution, and unpacks it into `build/pvz/dist/`, whose `lib/` is the class path), `runClient` (boots `net.fabricmc.loader.impl.launch.knot.KnotClient` with the distribution's jars *and this mod's own output* on the class path, development mode, `-Dpvz.gameDir=run` — see [The development run](#the-development-run)), plus the Kotlin compile tasks of the Kotlin Gradle plugin |
| conventions | Java 25 toolchain and `--release 25`, **Kotlin** (`org.jetbrains.kotlin.jvm`, `jvmTarget` 25, applied to every mod), UTF-8, jars with no bundled dependencies (a dependency is never shaded; a nested mod is `include`, and it stays a mod jar), `useJUnitPlatform()`, the test fork's `pvz.test.modJar` / `pvz.test.forkClasspath` system properties, headless SDL + first thread on macOS, the publication of the mod jar (`mavenLocal` coordinates, MIT) |

Versions of the toolchain itself live in one place, `PvzToolchain` (the game's version 1.0.0, the
API's 1.0.0, JUnit 6.1.3, Kotlin 2.4.21, `fabric-language-kotlin` 1.14.2+kotlin.2.4.21, Java 25): a
mod build states none of them, and the loader, Mixin, ASM and SLF4J are not stated anywhere — they
are the jars of the distribution, at whatever version the release carries. (The Kotlin Gradle
plugin's own version is also stated in `loom/build.gradle.kts`, because it has to sit on this
plugin's class path to be applicable; the two are kept in sync, and so is the `+kotlin.` line of
`fabric-language-kotlin` — it has to carry the very stdlib version the mods compile against.)

## The development run

`./gradlew runClient` starts the game the way `PvZ-Portable`'s own `./gradlew run` does — the
distribution's jars on the class path, `KnotClient`, `-Dpvz.gameDir=run`,
`-Dfabric.skipMcProvider=true` — with what makes it a *mod* run on top:

- **This mod is on the class path**, not copied into `mods/`: the class path is the
  `pvzRunClasspath` configuration (the distribution's jars, the language module, the API) plus
  `sourceSets.main.output` — the compiled classes and the processed resources, `fabric.mod.json`
  among them; only the output directories that exist, passed as one class path group (below).
  `-Dfabric.development=true` is what makes the loader discover a mod
  that is on the class path, which is exactly how fabric-loom's `runClient` loads the mod under
  development; a run therefore reflects the sources, with nothing built by hand and nothing copied.
- **`run/mods/` is neither written nor needed.** It stays what it is in an installation — the
  directory a person drops another mod into, read by the loader from the game directory — so the API
  and the language module no longer have to be there either. A jar of this mod that the old
  `pvzModsJar` task left in `run/mods/` (`run/` is not in git) has to be deleted once, and this is
  the one case where leaving it is worse than an error: the loader does *not* complain about the two
  candidates for the same mod id, it resolves them to one of them — measured on fabric-loader
  0.19.5, to the one in `run/mods` (its `added code source` names the jar, not the output
  directories) — so the jar would silently be the mod that runs and a run would show nothing of the
  sources.
- The mod's own output directories are passed as **one class path group**
  (`-Dfabric.classPathGroups=<classes dirs>:<resources dir>`). Fabric Loader looks for a mod in every
  class path entry on its own: it finds the `fabric.mod.json` in `build/resources/main`, so *that* is
  the directory it would register as the mod's code source, while the classes directories stay plain
  parent-classpath entries — and a class defined by the parent class loader cannot see anything the
  loader itself carries, the modules nested inside the API aggregate jar among them, which ends the
  run in a `NoClassDefFoundError` on the mod's first API call. A group makes all of those paths the
  mod's code sources, which is what a mod jar is in an installation (and what makes the loader, not
  the parent, define the mod's classes, so Mixin can transform them like any other mod's).

## The API

Every mod of this workspace requires the API in its `fabric.mod.json`
(`"depends": { "fabric-api": ">=1.0.0" }`) and declares nothing about it in Gradle: the toolchain
puts `cn.enaium.fabricpvz:fabric-api:1.0.0` (`PvzToolchain.API`) on the class paths a build compiles,
tests and runs against — the very class paths the distribution's API jars used to be on.

It is one coordinate and one jar, because the API ships the way Fabric API does: the aggregate jar
carries its five feature modules (`api-base`, `api-events`, `api-plant`, `api-resource`, `api-zombie`) **inside
itself** (see [Jar-in-jar](#jar-in-jar-include)), so a mod depends on one id and an installation has
one jar to drop into `mods/`. Two consequences for those class paths, and they are the two questions
a build asks about a dependency:

- **compilation** (`compileOnly`, `testCompileOnly`): the aggregate *with* its POM dependencies. javac
  reads jar files and not jars nested inside a jar, so the classes a mod compiles against have to be
  the module jars themselves; the aggregate declares them (`api` in its build and in its POM, exactly
  as Fabric API's aggregate does), so its one coordinate brings both.
- **runtime** (`testImplementation`, `pvzRunClasspath`): the aggregate *alone*, declared
  `isTransitive = false`. Fabric Loader reads the nested jars out of the aggregate, so a class path
  that carried the modules separately as well would resolve every module twice. This is the same
  shape as the `fabric-language-kotlin` declaration (one jar, whose nested `kotlin-stdlib` is the
  runtime the loader provides) and the shape of an installation, where one jar in `mods/` is what the
  five modules are loaded from. A development run discovers mods on the class path
  (`-Dfabric.development=true`, which `runClient` and the test fork both set), so the API is loaded
  in both from that one jar, with the modules the loader finds inside it — the loader's own mod list
  prints them nested under `fabric-api`:

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
`id("cn.enaium.fabricpvz.loom") version "1.0.5" apply false` — which is what puts the plugin's
classes on its build script class path — and calls the mechanism directly, `JarInJar.include(...)`
with the configuration `JarInJar.configuration(project)`. One mechanism, one implementation; the
plugin is only the wiring a mod gets for free.

## Kotlin

A mod may be written in Kotlin without declaring anything: the plugin applies
`org.jetbrains.kotlin.jvm` and compiles `src/main/kotlin` / `src/test/kotlin` at `jvmTarget` 25, so
the mod's Kotlin classes are the class file version the game's Java 25 classes are. Mixins stay Java
(a Mixin configuration is a Java class Mixin reads as such).

Two rules follow from Fabric Loader's own behaviour:

- **Entry points are Kotlin `class`es, not `object`s.** Fabric Loader instantiates a `ModInitializer`
  entry point reflectively through its no-argument constructor; a Kotlin `object` has only a private
  one, and reaching it needs a Fabric *language adapter* (the `"kotlin"` one), i.e. an entry point
  entry shaped `{"adapter": "kotlin", "value": "…"}`. The adapter is available (the plugin ships
  `fabric-language-kotlin`, below), but the mods of this workspace keep the plain class: a class is
  what the loader can instantiate with no adapter in the path at all.
- **The stdlib is not bundled.** The Kotlin Gradle plugin's default `implementation(kotlin-stdlib)` is
  switched off (`kotlin.stdlib.default.dependency=false`) and the plugin declares the stdlib
  `compileOnly` instead, so a mod compiles against it and its jar stays "own classes, resources and
  mixins only" (the jar task treats a duplicate as a bug). The copy a Kotlin mod resolves `kotlin.*`
  from at runtime is the one **`fabric-language-kotlin` carries** — the real Fabric language module,
  whose nested `kotlin-stdlib` the loader adds to the class path of every mod. The plugin puts that
  artifact on the class path a run uses (`testRuntimeOnly`, and the `runClient` class path) and a mod
  declares it in `fabric.mod.json`
  (`"depends": { "fabric-language-kotlin": ">=1.14.2" }`); an installation keeps the jar in `mods/`
  like any other mod. Two Kotlin mods in one installation therefore share one stdlib; if each bundled
  its own, the loader's `KnotClassLoader` would resolve every `kotlin.*` name from whichever mod jar
  it scans first and shadow the other copy, so two mods built against different stdlib versions
  would silently share a mixture of both. Declaring the stdlib in the plugin *instead* (a launcher
  library next to the loader) is what this workspace used to do and no longer does: with the language
  module present that copy would shadow - or be shadowed by - the module's own, which is exactly the
  duplicate this rule exists to prevent.

## Logging

A mod logs with `LoggerFactory.getLogger(<mod id>)` and nothing else: the SLF4J API is one of the
jars of the game distribution (`lib/slf4j-api-*.jar`, on the compile class path like the rest of
them). The implementation (Log4j), its SLF4J binding and the configuration (`log4j2.xml`) are launch
libraries published by `cn.enaium.fabricpvz:fabric-provider` and thus jars of the distribution too,
which also points Fabric Loader's own log at the same stack, so Fabric Loader's lines, the Mixin
subsystem's lines and the mods' lines are one stream in one format. See `fabric-provider/README.md`.

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
is what 1.0.0 went through (the id is accepted since 2026-10-15); a version published after it - 1.0.1
is one - is served as soon as `publishPlugins` reports success. (The version that is published is the
one a mod states - the artifact is published under this project's `version`, 1.0.1.)

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
(that bug fixed) and 1.0.5 (the current one: the version package's manifest is read and its libraries
are Maven coordinates, and it links against API 1.2.0) are all on the portal, with `latest`/`release`
= 1.0.5. `https://plugins.gradle.org/m2/` is therefore the one source a mod of this workspace needs,
and publishing this plugin to `mavenLocal` is not a prerequisite of a mod build any more. The mods
state `version "1.0.5"` and list `gradlePluginPortal()` first in their `settings.gradle.kts`, keeping
`mavenLocal()` after it as a fallback for a version the portal does not serve yet (a development
version built here).

## License

MIT (see `LICENSE`). The game port itself (`PvZ-Portable`) is LGPL-3.0-or-later with the PopCap framework licence.
