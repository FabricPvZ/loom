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
 * <p>A distribution is the release asset {@code pvz-portable-<version>-online.zip} the game's release
 * attaches: the Minecraft-launcher-style version package, a directory
 * {@code PvZ-Portable-<version>/} holding the game jar and the version manifest next to it
 * ({@link VersionJson}). The toolchain reads that manifest, so a mod build imports what it names the
 * way every other dependency is imported: the game is the one file that goes on the class path
 * ({@link #gameJar}, there is no repository to resolve it from), and everything else - the loader,
 * Mixin, ASM, the Log4j stack, LWJGL and the natives of the machine - is Maven coordinates
 * ({@link PvzModePlugin}). A release cut before the package naming carries the older
 * {@code pvz-portable-<version>.zip} instead, which is the application-style distribution
 * ({@code lib/} with the launcher class path): it is what {@link #classpath} describes and it is
 * still supported ({@link #releaseUrls} tries it second, and the extraction recognizes it), because
 * a version released once has to keep building.
 *
 * <p>{@code PvzModPlugin} decides where the distribution comes from (a GitHub release of a version,
 * or a distribution on disk), {@link PvzDistTask} obtains it, and this class holds the parts that
 * touch the outside world: the URLs of a version, the download, the extraction and the reading of
 * what the archive holds. Nothing here is Gradle-specific beyond the exception type and the logger,
 * so the rules are readable in one place.
 *
 * <p>Kotlin has no package-private class, so what was a package-private {@code final class} with
 * static methods is an {@code internal object}: the same names, read from this module only.
 */
internal object PvzDist {

    /** The name of the release asset of a version, and of the {@code mcVersionZip} archive of a build. */
    const val ASSET_PREFIX = "pvz-portable-"

    /** The asset (and {@code mcVersionZip}) extension. */
    const val ASSET_EXTENSION = ".zip"

    /** The directory of the application-style layout a mod compiles and launches against: the launcher class path. */
    const val LIB = "lib"

    /**
     * The directory of the application-style layout that would hold mod jars. A current distribution
     * has none (see the class comment), but an archive of an older release does, and it is unpacked
     * with the rest rather than dropped: the unpacked directory is what the archive holds.
     */
    const val MODS = "mods"

    /** Marks an extracted distribution and records what it was extracted from, so it is never stale. */
    const val MARKER = ".pvz-dist"

    /**
     * What an archive's distribution content is, as the relative path it lands at - two alternatives,
     * because the two layouts name their files differently:
     * <ul>
     *   <li>the {@code lib/…} and {@code mods/…} jars of an application-style archive, at any depth of
     *       the archive's root directory (the tail is what is kept: the layout of the distribution is
     *       {@code lib/} and {@code mods/}, wherever the archive wrapped them);</li>
     *   <li>the jar and manifest of a version package, at the package's root or in the
     *       {@code PvZ-Portable-<version>/} directory it wraps them in - kept as they are named, so the
     *       unpacked distribution has the layout of the package and the manifest's own file name is the
     *       name of the jar that lands next to it.</li>
     * </ul>
     */
    private val CONTENT: Pattern = Pattern.compile(
        """^(?:.*/)?((?:$LIB|$MODS)/[^/]+\.jar)$|^((?:[^/]+/)?[^/]+\.(?:jar|json))${'$'}"""
    )

    /**
     * The release asset name of a version, e.g. {@code pvz-portable-1.0.0-online.zip}.
     *
     * <p>The suffix is the workspace's package naming: the game's release attaches two archives with
     * one prefix - {@code -offline} for the self-contained distribution a player unpacks (every
     * {@code lib/} jar next to the launcher) and {@code -online} for the Minecraft-launcher-style
     * version package, whose dependencies a launcher (and a mod build) downloads. This toolchain
     * takes the online one: the manifest it carries is what turns the dependencies into Maven
     * coordinates ({@link VersionJson}).
     */
    fun assetName(version: String): String = ASSET_PREFIX + version + "-online" + ASSET_EXTENSION

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
        return listOf("$base/$version", "$base/v$version").map { tag -> "$tag/" + assetName(version) }
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
     * Unpacks the contents of a distribution zip into {@code distDirectory}, preserving the layout
     * the archive has - a version package's jar and manifest ({@code PvZ-Portable-<version>/…}), or
     * the {@code lib/} and {@code mods/} of an application-style archive - unless that directory
     * already holds exactly what this zip produces: the marker written next to it records the archive
     * (its path and SHA-256) and the files it produced (name and size), which is the identity of the
     * unpacked state and not a timestamp. A rebuilt archive, another distribution, or a file someone
     * deleted or replaced therefore leads to a fresh unpacking instead of a class path that is
     * missing something; the extraction is never mixed with what was there before either, because
     * the new one replaces the directory in a single move. Returns the number of files written
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
        var files = 0
        try {
            Files.createDirectories(temporary)
            files = unpack(zip, temporary)
            if (files == 0) {
                throw GradleException(
                    "The distribution archive $zip holds neither a version package (a jar and a manifest" +
                        " next to it) nor a " + LIB + "/ of jars: it does not look like the game" +
                        " distribution (" + assetName("<version>") + " of a release, or the archive the" +
                        " game's mcVersionZip / distZip task writes)."
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
        return files
    }

    /**
     * The marker of {@code distDirectory} as it is now: the archive it should have come from (path and
     * SHA-256) and the files that are in it (relative name and size, sorted). Written after an
     * unpacking and compared before the next one, so the two are the same string exactly when the
     * directory holds the complete unpacking of that archive and nothing else.
     */
    private fun marker(zip: Path, source: String, distDirectory: Path): String {
        val entries = contents(distDirectory).joinToString("\n") { file ->
            distDirectory.relativize(file).toString() + ":" + size(file)
        }
        // No trailing newline: the marker is compared to what a later run computes, so the two have to
        // be the same string, and a trailing separator would make every comparison fail (and every
        // build re-unpack).
        return "source=" + source + "\nsha256=" + sha256(zip) + (if (entries.isEmpty()) "" else "\n" + entries)
    }

    /** The size of a file, which is what the marker records about a jar next to its name. */
    private fun size(file: Path): Long {
        try {
            return Files.size(file)
        } catch (exception: IOException) {
            throw GradleException("Cannot read $file: ${exception.message}", exception)
        }
    }

    /**
     * Writes the distribution content of an archive into {@code target}: the version package's jar
     * and manifest, or the {@code lib/} and {@code mods/} jars of an application-style archive. The
     * matched part of an entry's name - and nothing else of it - is the path it lands at, so the
     * layout of the unpacked directory is the archive's own: an archive entry can name anything, and
     * none of it may escape the target.
     */
    private fun unpack(zip: Path, target: Path): Int {
        var files = 0
        try {
            ZipFile(zip.toFile()).use { archive ->
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) {
                        continue
                    }
                    val matcher = CONTENT.matcher(entry.name)
                    if (!matcher.matches()) {
                        continue
                    }
                    val name = matcher.group(1) ?: matcher.group(2)
                    val destination = target.resolve(name)
                    Files.createDirectories(destination.parent)
                    archive.getInputStream(entry).use { input ->
                        Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING)
                    }
                    files++
                }
            }
        } catch (exception: ZipException) {
            throw IOException("$zip is not a readable zip archive: ${exception.message}", exception)
        }
        return files
    }

    /**
     * A version package a distribution holds - the game jar and the manifest next to it
     * ({@link VersionJson}) - or {@code null} when the distribution is an application-style one
     * ({@code lib/} jars) instead. {@code gameJar} is the jar's path relative to the package root.
     */
    internal class Package(val manifest: String, val gameJar: String)

    /** The game jar of an unpacked distribution, or {@code null} when it is an application-style one. */
    fun gameJar(distDirectory: Path): Path? =
        packageOfDirectory(distDirectory)?.let { distDirectory.resolve(it.gameJar) }

    /** The version package of an archive, read without unpacking it, or {@code null} when it has none. */
    fun packageOfZip(zip: Path): Package? {
        try {
            ZipFile(zip.toFile()).use { archive ->
                val names = ArrayList<String>()
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) {
                        names.add(entry.name)
                    }
                }
                val manifest = packageManifest(names) ?: return null
                val text = archive.getInputStream(archive.getEntry(manifest)).use { input ->
                    input.readAllBytes().decodeToString()
                }
                return Package(text, manifest.substring(0, manifest.length - ".json".length) + ".jar")
            }
        } catch (exception: IOException) {
            throw GradleException("Cannot read the game distribution $zip: ${exception.message}", exception)
        }
    }

    /** The version package of an unpacked distribution (its root, or one directory below it), or {@code null}. */
    fun packageOfDirectory(distDirectory: Path): Package? {
        val directories = ArrayList<Path>()
        directories.add(distDirectory)
        if (Files.isDirectory(distDirectory)) {
            try {
                Files.list(distDirectory).use { entries ->
                    entries.filter { Files.isDirectory(it) }.sorted().forEach { directories.add(it) }
                }
            } catch (exception: IOException) {
                throw GradleException("Cannot list $distDirectory: ${exception.message}", exception)
            }
        }
        for (directory in directories) {
            for (manifest in filesIn(directory) { it.fileName.toString().endsWith(".json") }) {
                val name = manifest.fileName.toString()
                val jarName = name.substring(0, name.length - ".json".length) + ".jar"
                val jar = manifest.resolveSibling(jarName)
                if (Files.isRegularFile(jar)) {
                    val prefix = distDirectory.relativize(manifest).toString().replace('\\', '/')
                        .substring(0, distDirectory.relativize(manifest).toString().replace('\\', '/').length - name.length)
                    return Package(Files.readString(manifest), prefix + jarName)
                }
            }
        }
        return null
    }

    /**
     * The manifest of the version package a list of entry names holds: the first {@code <name>.json}
     * that has a {@code <name>.jar} next to it, at the package root or one directory below it.
     */
    private fun packageManifest(names: List<String>): String? {
        for (name in names.sorted()) {
            if (!name.endsWith(".json") || name.count { it == '/' } > 1) {
                continue
            }
            if (names.contains(name.substring(0, name.length - ".json".length) + ".jar")) {
                return name
            }
        }
        return null
    }

    /**
     * The class path of an application-style distribution: its {@code lib/} jars, in the order the
     * launcher uses them. A version package has none ({@link #gameJar} is its one file, and its
     * dependencies come from the manifest as Maven coordinates), so this is empty for one.
     * {@code mods/} is deliberately not part of it (see the class comment and
     * {@code PvzModPlugin#distJars}).
     */
    fun classpath(distDirectory: Path): List<Path> =
        filesIn(distDirectory.resolve(LIB)) { it.fileName.toString().endsWith(".jar") }

    /** Every file of an unpacked distribution, sorted by name. This is what an unpacking is measured by. */
    private fun contents(distDirectory: Path): List<Path> {
        if (!Files.isDirectory(distDirectory)) {
            return emptyList()
        }
        try {
            Files.walk(distDirectory).use { paths ->
                return paths
                    .filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString() != MARKER }
                    .sorted()
                    .toList()
            }
        } catch (exception: IOException) {
            throw GradleException("Cannot list $distDirectory: ${exception.message}", exception)
        }
    }

    /** The files of one directory that {@code matches}, sorted by name, or an empty list if it does not exist. */
    private fun filesIn(directory: Path, matches: (Path) -> Boolean): List<Path> {
        if (!Files.isDirectory(directory)) {
            return emptyList()
        }
        try {
            Files.list(directory).use { entries ->
                return entries
                    .filter { Files.isRegularFile(it) }
                    .filter(matches)
                    .sorted()
                    .toList()
            }
        } catch (exception: IOException) {
            throw GradleException("Cannot list the jars of $directory: ${exception.message}", exception)
        }
    }

    /**
     * Whether a directory is an application-style unpacked distribution: it is what {@code lib/}
     * means. A version package is the other shape ({@link #packageOf}), and a directory without
     * either is instead read as a release directory (a directory that holds
     * {@code pvz-portable-<version>-online.zip} files, e.g. the game's {@code build/distributions}).
     */
    fun isDistribution(directory: Path): Boolean = Files.isDirectory(directory.resolve(LIB))

    /** Fails with the message of the failure when a local distribution has no jar a mod could compile against. */
    fun requireUsable(distDirectory: Path, what: String) {
        Objects.requireNonNull(distDirectory, "distDirectory")
        if (packageOfDirectory(distDirectory) != null) {
            return
        }
        val jars = classpath(distDirectory)
        if (jars.isEmpty()) {
            throw GradleException(
                "$what holds no game: a distribution is either a version package (a jar and its" +
                    " manifest at the root, what a release attaches as " + assetName("<version>") +
                    ") or an application-style one (a " + LIB + "/ of jars, what distZip writes)." +
                    " Point the pvz configuration at one of those."
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
