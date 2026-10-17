package dev.nucleusframework.nja.plugin.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import javax.inject.Inject

/**
 * A mobile runtime for [target] (`android-aarch64`, `ios-simulator-aarch64`...), from a release of [repository]:
 * [release] (`latest` by default), its archive checked against the `.sha256` published with it, kept in Gradle's cache
 * ([cacheDir]) and extracted into [outputDir]. [product] names the releases: `jdk-mobile` (the JVM: `static/`,
 * `include/`, `lib/`) or `graalvm-mobile` (a native image's static libraries: `lib/`, `include/`,
 * `graalvm-mobile.properties`).
 *
 * `latest` is resolved through the release page's redirect (no API, no rate limit) and kept a day; offline, the last
 * one resolved.
 */
@DisableCachingByDefault(because = "Downloads; the archive is kept in Gradle's cache")
abstract class NjaJdkMobileTask : DefaultTask() {
    @get:Inject abstract val archives: ArchiveOperations
    @get:Inject abstract val files: FileSystemOperations

    @get:Input abstract val product: Property<String>
    @get:Input abstract val repository: Property<String>
    @get:Input abstract val release: Property<String>
    @get:Input abstract val target: Property<String>
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:Internal abstract val offline: Property<Boolean>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    init {
        product.convention(JDK_MOBILE)
        // latest may have moved: checked every build (cheaply: the resolution is cached, the archive too)
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun stage() {
        val repo = repository.get()
        val tag = resolve(repo, release.get())
        val asset = assetName(product.get(), tag, target.get())
        val out = outputDir.get().asFile
        val marker = File(out, ".nja-release")
        if (marker.isFile && marker.readText() == "$repo $tag") return

        val archive = download(repo, tag, asset)
        files.sync {
            from(archives.tarTree(archive))
            into(out)
            // jdk-mobile's files are read-only: the next sync must be able to replace them
            filePermissions { user { read = true; write = true } }
        }
        marker.writeText("$repo $tag")
        logger.lifecycle("nja: ${target.get()} from $repo $tag")
    }

    private fun resolve(repo: String, release: String): String {
        if (release != LATEST) return release
        val cache = File(cacheDir.get().asFile, "latest-${repo.replace('/', '_')}.properties")
        val known = Properties().apply { if (cache.isFile) cache.reader().use { load(it) } }
        val knownTag = known.getProperty("tag")
        val fresh = System.currentTimeMillis() - (known.getProperty("at")?.toLongOrNull() ?: 0L) < DAY_MILLIS
        if (knownTag != null && (fresh || offline.get())) return knownTag
        if (offline.get()) throw GradleException("NJA: offline, and $repo's latest release was never resolved")
        val tag = try {
            latestTag(repo)
        } catch (e: Exception) {
            // The network down: the last one resolved, if any
            knownTag?.also { logger.warn("nja: can't resolve $repo's latest release (${e.message}), using $it") } ?: throw e
        }
        cache.parentFile.mkdirs()
        Properties().apply {
            setProperty("tag", tag)
            setProperty("at", System.currentTimeMillis().toString())
            cache.writer().use { store(it, null) }
        }
        return tag
    }

    // github.com/<repo>/releases/latest redirects to .../releases/tag/<tag>
    private fun latestTag(repo: String): String {
        val connection = URI("https://github.com/$repo/releases/latest").toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = "HEAD"
        try {
            val location = connection.getHeaderField("Location")
                ?: throw GradleException("NJA: no latest release of $repo (HTTP ${connection.responseCode})")
            return location.substringAfterLast("/releases/tag/").takeIf { it != location && it.isNotEmpty() }
                ?: throw GradleException("NJA: unexpected latest release of $repo: $location")
        } finally {
            connection.disconnect()
        }
    }

    // In Gradle's cache, by release: checked against the .sha256 published with it
    private fun download(repo: String, tag: String, asset: String): File {
        val dir = File(cacheDir.get().asFile, "$repo/$tag").apply { mkdirs() }
        val archive = File(dir, asset)
        val checksum = File(dir, "$asset.sha256")
        val base = "https://github.com/$repo/releases/download/$tag/$asset"
        if (!checksum.isFile) fetch("$base.sha256", checksum)
        val expected = checksum.readText().trim().substringBefore(' ').lowercase()
        if (archive.isFile && sha256(archive) == expected) return archive
        if (offline.get()) throw GradleException("NJA: offline, and $asset of $tag isn't in Gradle's cache")
        fetch(base, archive)
        val actual = sha256(archive)
        if (actual != expected) {
            archive.delete()
            throw GradleException("NJA: unexpected $asset from $base (SHA-256 $actual, its .sha256 says $expected)")
        }
        return archive
    }

    private fun fetch(url: String, file: File) {
        val partial = File(file.parentFile, "${file.name}.part")
        URI(url).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
        if (!partial.renameTo(file)) throw GradleException("NJA: can't write $file")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val LATEST = "latest"
        const val JDK_MOBILE = "jdk-mobile"
        const val GRAALVM_MOBILE = "graalvm-mobile"
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000

        /** <product>-<version>b<build>.<revision> -> <product>-<version>-<target>-b<build>.<revision>.tar.gz */
        fun assetName(product: String, tag: String, target: String): String {
            val match = Regex("^${Regex.escape(product)}-(.+?)b([0-9]+\\.[0-9]+)$").matchEntire(tag)
                ?: throw GradleException("NJA: $tag isn't a $product release ($product-<version>b<build>.<revision>)")
            return "$product-${match.groupValues[1]}-$target-b${match.groupValues[2]}.tar.gz"
        }
    }
}
