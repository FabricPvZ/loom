package cn.enaium.fabricpvz.loom

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Duration
import java.util.Comparator
import java.util.HexFormat
import java.util.Objects
import java.util.regex.Pattern
import java.util.zip.ZipException
import java.util.zip.ZipFile

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger

/**
 * The game distribution a mod is built against, and the one place that says how it is obtained.
 *
 * <p>A distribution is the release asset {@code pvz-portable-<version>.zip} the game's release
 * attaches - the {@code application} plugin's {@code distZip} layout: {@code lib/} with the
 * game jar and everything the launcher starts it with (the provider, which publishes the real Fabric
 * Loader with Mixin and ASM, the SLF4J/Log4j stack and LWJGL with the native libraries of every
 * platform it runs on) and, in an archive an older release cut, a {@code mods/} directory with mod
 * jars. The distribution a current release carries has no {@code mods/} at all: a mod is something
 * the player installs, so what a mod build needs beyond the game - the API - comes from the
 * toolchain's own coordinates ({@code PvzModPlugin#configureApi}) and not from the distribution.
 *
 * <p>Only {@code lib/} is a class path ({@link #classpath}); {@code mods/} is unpacked when an
 * archive has one, so that the extraction is the archive's own content and the marker below can
 * notice a changed archive, but it is never one of the entries a mod compiles, tests or launches
 * against ({@code PvzModPlugin#distJars} says why).
 *
 * <p>{@code PvzModPlugin} decides where the distribution comes from (a GitHub release of a version,
 * or a distribution on disk), {@link PvzDistTask} obtains it, and this class holds the parts that
 * touch the outside world: the URLs of a version, the download, the extraction and the jar listing.
 * Nothing here is Gradle-specific beyond the exception type and the logger, so the rules are
 * readable in one place.
 *
 * <p>Kotlin has no package-private class, so what was a package-private {@code final class} with
 * static methods is an {@code internal object}: the same names, read from this module only.
 */
internal object PvzDist {

    /** The name of the release asset of a version, and of the {@code distZip} archive of a build. */
    const val ASSET_PREFIX = "pvz-portable-"

    /** The asset (and {@code distZip}) extension. */
    const val ASSET_EXTENSION = ".zip"

    /** The directory of the released layout a mod compiles and launches against: the launcher class path. */
    const val LIB = "lib"

    /**
     * The directory of the released layout that would hold mod jars. A current distribution has none
     * (see the class comment), but an archive of an older release does, and it is unpacked with the
     * rest rather than dropped: the unpacked directory is what the archive holds.
     */
    const val MODS = "mods"

    /** Marks an extracted distribution and records what it was extracted from, so it is never stale. */
    const val MARKER = ".pvz-dist"

    /** {@code lib/…} and {@code mods/…} jars, at any depth of the archive's root directory. */
    private val JAR: Pattern = Pattern.compile("""^(?:.*/)?($LIB|$MODS)/([^/]+\.jar)${'$'}""")

    /** The release asset name of a version, e.g. {@code pvz-portable-1.0.0.zip}. */
    fun assetName(version: String): String = ASSET_PREFIX + version + ASSET_EXTENSION

    /**
     * The release asset URLs of a version, in the order they are tried.
     *
     * <p>The tag is the plain version - {@code 1.0.0}, the version every artifact of this
     * workspace carries - but {@code v1.0.0} is tried as a second attempt as well, because a
     * {@code v} prefix is a common tagging convention: both are tried instead of failing a build
     * over the tag's shape.
     *
     * <p>{@code baseUrl} is {@link PvzToolchain#RELEASE_BASE_URL} unless the build overrides it
     * with the {@code pvz.release.baseUrl} Gradle property (a mirror, or a directory served over
     * {@code file:} / HTTP while testing the download path of an unreleased version).
     */
    fun releaseUrls(version: String, baseUrl: String): List<String> {
        val base = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
        val asset = assetName(version)
        return listOf("$base/$version/$asset", "$base/v$version/$asset")
    }

    /**
     * Makes sure the release asset of {@code urls} is in the cache and returns its path. An asset
     * that is already there is kept (a version's artifact is immutable, and a cached one is what
     * makes a build without network possible); a download lands as a temporary file next to it and
     * is moved into place only once it is complete, so an interrupted download never leaves a
     * truncated zip behind.
     */
    fun download(version: String, urls: List<String>, target: Path, offline: Boolean, logger: Logger): Path {
        if (Files.isRegularFile(target)) {
            logger.info("pvz distribution: using the cached release asset {}", target)
            return target
        }
        if (offline) {
            throw GradleException("The game distribution ${PvzToolchain.GROUP}:${PvzToolchain.GAME_ARTIFACT}" +
                ":$version is not cached ($target) and Gradle runs with --offline, so its" +
                " release asset cannot be downloaded. Run without --offline, or point the pvz configuration" +
                " at a local distribution: pvz(files(\"<distribution.zip>\")) or" +
                " pvz(files(\"<unpacked distribution>\")) - see loom/README.md.")
        }
        val failures = mutableListOf<String>()
        for (url in urls) {
            try {
                val bytes = fetch(url, target, logger)
                logger.lifecycle("pvz distribution: downloaded {} ({} bytes) from {}", target.fileName, bytes, url)
                return target
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                failures.add("$url -> ${exception.message}")
                logger.info("pvz distribution: {} did not provide the asset: {}", url, exception.message)
            } catch (exception: IOException) {
                failures.add("$url -> ${exception.message}")
                logger.info("pvz distribution: {} did not provide the asset: {}", url, exception.message)
            } catch (exception: RuntimeException) {
                failures.add("$url -> ${exception.message}")
                logger.info("pvz distribution: {} did not provide the asset: {}", url, exception.message)
            }
        }
        throw GradleException(noAsset(version) + "\nTried:\n  " + failures.joinToString("\n  ") +
            "\nA version without a release cannot be downloaded: check the version, wait for the release" +
            " of that tag, or build the game and point the pvz configuration at that distribution" +
            " (pvz(files(\"<distribution.zip>\")) / pvz(files(\"<unpacked distribution>\")))." +
            " A different release host is set with -Ppvz.release.baseUrl=<url> - see loom/README.md.")
    }

    /** The message of the "no such release asset" failure, which every download failure shares. */
    private fun noAsset(version: String): String {
        return "No game distribution for ${PvzToolchain.GROUP}:${PvzToolchain.GAME_ARTIFACT}:$version" +
            ": the release of that version carries no asset " + assetName(version) + "."
    }

    /** Fetches one URL into {@code target} through a temporary file, and returns the byte count. */
    private fun fetch(url: String, target: Path, logger: Logger): Long {
        val uri = URI.create(url)
        Files.createDirectories(target.parent)
        // Per-process temporary file: two builds of the workspace may fetch the same asset at once, and
        // the second one must not move a half-written file of the first into the cache.
        val temporary = target.resolveSibling(target.fileName.toString() + ".part-" + ProcessHandle.current().pid())
        Files.deleteIfExists(temporary)
        val bytes: Long
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            val source = Path.of(uri)
            if (!Files.isRegularFile(source)) {
                throw IOException("no such file: $source")
            }
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            bytes = Files.size(temporary)
        } else {
            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build()
            val request = HttpRequest.newBuilder(uri)
                .header("User-Agent", "pvz-loom/" + PvzToolchain.GAME_VERSION)
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 200) {
                response.body().use { body ->
                    body.readNBytes(256)
                }
                throw IOException("HTTP " + response.statusCode())
            }
            var transferred = 0L
            response.body().use { body ->
                Files.newOutputStream(temporary).use { out ->
                    transferred = body.transferTo(out)
                }
            }
            bytes = transferred
        }
        if (bytes == 0L) {
            Files.deleteIfExists(temporary)
            throw IOException("the response was empty")
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (exception: IOException) {
            Files.deleteIfExists(temporary)
            throw exception
        }
        Files.writeString(Path.of("$target.sha256"), sha256(target))
        return bytes
    }

    /** The SHA-256 of a file, as the recorded identity of a downloaded (or local) distribution zip. */
    fun sha256(file: Path): String {
        val digest: MessageDigest
        try {
            digest = MessageDigest.getInstance("SHA-256")
        } catch (impossible: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 is required by the Java platform", impossible)
        }
        try {
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                var read = input.read(buffer)
                while (read != -1) {
                    digest.update(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
        } catch (exception: IOException) {
            throw GradleException("Cannot read $file: ${exception.message}", exception)
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    /**
     * Unpacks the jars of a distribution zip into {@code distDirectory} ({@code lib/} and, when the
     * archive has one, {@code mods/} - the released layout), unless that directory already holds
     * exactly what this zip produces: the marker written next to the jars records the archive (its
     * path and SHA-256) and the jars it produced (name and size), which is the identity of the
     * unpacked state and not a timestamp. A rebuilt archive, another distribution, or a jar someone
     * deleted or replaced therefore leads to a fresh unpacking instead of a class path that is
     * missing something; the extraction is never mixed with what was there before either, because
     * the new one replaces the directory in a single move. Returns the number of jars written
     * (0 when the directory was already current).
     */
    fun extract(zip: Path, distDirectory: Path, source: String, logger: Logger): Int {
        val current = marker(zip, source, distDirectory)
        val markerFile = distDirectory.resolve(MARKER)
        if (Files.isRegularFile(markerFile)) {
            try {
                if (current == Files.readString(markerFile).trim()) {
                    logger.info("pvz distribution: {} already holds this distribution", distDirectory)
                    return 0
                }
                logger.info(
                    "pvz distribution: {} is not the unpacking of {} any more, re-unpacking",
                    distDirectory, zip.fileName
                )
            } catch (exception: IOException) {
                logger.info("pvz distribution: re-unpacking {} ({})", distDirectory, exception.message)
            }
        }
        val temporary = distDirectory.resolveSibling(
            distDirectory.fileName.toString() + ".tmp-" + ProcessHandle.current().pid()
        )
        delete(temporary)
        var jars = 0
        try {
            Files.createDirectories(temporary)
            jars = unpack(zip, temporary)
            if (jars == 0) {
                throw GradleException(
                    "The distribution archive $zip has no jars in $LIB/ or " +
                        "$MODS/: it does not look like the game distribution (" + assetName("<version>") +
                        " of a release, or the archive the game's distZip task writes)."
                )
            }
            Files.writeString(temporary.resolve(MARKER), marker(zip, source, temporary))
        } catch (exception: IOException) {
            delete(temporary)
            throw GradleException(
                "Cannot unpack the game distribution $zip into $distDirectory: " +
                    exception.message, exception
            )
        }
        delete(distDirectory)
        try {
            Files.move(temporary, distDirectory)
        } catch (exception: IOException) {
            delete(temporary)
            throw GradleException(
                "Cannot move the unpacked game distribution to $distDirectory: " +
                    exception.message, exception
            )
        }
        return jars
    }

    /**
     * The marker of {@code distDirectory} as it is now: the archive it should have come from (path and
     * SHA-256) and the jars that are in it (name and size, in class path order). Written after an
     * unpacking and compared before the next one, so the two are the same string exactly when the
     * directory holds the complete unpacking of that archive and nothing else.
     */
    private fun marker(zip: Path, source: String, distDirectory: Path): String {
        val marker = StringBuilder("source=").append(source)
            .append("\nsha256=").append(sha256(zip)).append('\n')
        for (jar in jars(distDirectory)) {
            marker.append(distDirectory.relativize(jar)).append(':').append(size(jar)).append('\n')
        }
        return marker.toString()
    }

    /** The size of a file, which is what the marker records about a jar next to its name. */
    private fun size(file: Path): Long {
        try {
            return Files.size(file)
        } catch (exception: IOException) {
            throw GradleException("Cannot read $file: ${exception.message}", exception)
        }
    }

    /** Writes the {@code lib/} and {@code mods/} jars of an archive into {@code target}. */
    private fun unpack(zip: Path, target: Path): Int {
        var jars = 0
        try {
            ZipFile(zip.toFile()).use { archive ->
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) {
                        continue
                    }
                    val matcher = JAR.matcher(entry.name)
                    if (!matcher.matches()) {
                        continue
                    }
                    // Only the matched directory and the file name are used, never the entry's own path:
                    // an archive entry can name anything, and nothing of it may escape the target.
                    val destination = target.resolve(matcher.group(1)).resolve(matcher.group(2))
                    Files.createDirectories(destination.parent)
                    archive.getInputStream(entry).use { input ->
                        Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING)
                    }
                    jars++
                }
            }
        } catch (exception: ZipException) {
            throw IOException("$zip is not a readable zip archive: ${exception.message}", exception)
        }
        return jars
    }

    /**
     * The class path of a distribution: its {@code lib/} jars, in the order the launcher uses them.
     * What a mod compiles against, what its tests boot the loader on and what {@code runClient}
     * launches; {@code mods/} is deliberately not part of it (see the class comment and
     * {@code PvzModPlugin#distJars}).
     */
    fun classpath(distDirectory: Path): List<Path> = list(distDirectory.resolve(LIB))

    /**
     * Every jar of an unpacked distribution, {@code lib/} first and {@code mods/} after it. This is
     * what an unpacking is measured by (its marker records these files), not what a class path is made
     * of: a {@code mods/} jar an older archive brought along is part of what that archive contains.
     */
    fun jars(distDirectory: Path): List<Path> {
        val jars = mutableListOf<Path>()
        jars.addAll(list(distDirectory.resolve(LIB)))
        jars.addAll(list(distDirectory.resolve(MODS)))
        jars.sortWith(Comparator.comparing<Path, String> { it.fileName.toString() })
        return jars
    }

    /** The {@code .jar} files of one directory, sorted by name, or an empty list if it does not exist. */
    private fun list(directory: Path): List<Path> {
        if (!Files.isDirectory(directory)) {
            return emptyList()
        }
        try {
            Files.list(directory).use { entries ->
                return entries
                    .filter { it.fileName.toString().endsWith(".jar") }
                    .filter { Files.isRegularFile(it) }
                    .sorted(Comparator.comparing<Path, String> { it.fileName.toString() })
                    .toList()
            }
        } catch (exception: IOException) {
            throw GradleException("Cannot list the jars of $directory: ${exception.message}", exception)
        }
    }

    /**
     * Whether a directory is an unpacked distribution: it is what {@code lib/} means. A directory
     * without a {@code lib/} is instead read as a release directory (a directory that holds
     * {@code pvz-portable-<version>.zip} files, e.g. the game's {@code build/distributions}).
     */
    fun isDistribution(directory: Path): Boolean = Files.isDirectory(directory.resolve(LIB))

    /** Fails with the message of the failure when a local distribution has no jars a mod could compile against. */
    fun requireUsable(distDirectory: Path, what: String) {
        Objects.requireNonNull(distDirectory, "distDirectory")
        val jars = classpath(distDirectory)
        if (jars.isEmpty()) {
            throw GradleException(
                "$what has no jars in $LIB/, so a mod cannot compile against it:" +
                    " point the pvz configuration at a game distribution - the zip a release attaches (" +
                    assetName("<version>") + "), the directory it unpacks to, or the directory the game's" +
                    " distZip task writes ($LIB/, the launcher class path)."
            )
        }
    }

    /** The cache path of a version's release asset: one directory per artifact, shared by every build. */
    fun cachedAsset(cacheDirectory: Path, version: String): Path = cacheDirectory.resolve(assetName(version))

    /** Deletes a file or a directory tree, ignoring every failure: it is a best-effort cleanup of a cache entry. */
    private fun delete(path: Path) {
        if (!Files.exists(path)) {
            return
        }
        try {
            Files.walk(path).use { paths ->
                paths.sorted(Comparator.reverseOrder<Path>()).forEach { entry ->
                    try {
                        Files.deleteIfExists(entry)
                    } catch (ignored: IOException) {
                        // A cache entry that cannot be deleted is replaced in place by the caller's own copy.
                    }
                }
            }
        } catch (ignored: IOException) {
            // Same: the entries below are re-created by the extraction that follows.
        }
    }
}
