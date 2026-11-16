package dev.nucleusframework.nja.plugin.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Properties

/**
 * GraalVM CE for the build host: the release a graalvm-mobile kit ([kit]) is for (its `graalvm.tag`), or, without a kit
 * (the desktop), [release] (`latest` by default). Downloaded from graalvm/graalvm-ce-builds' release into Gradle's
 * cache ([cacheDir]) and extracted there once. Writes its home's path to [homeFile].
 */
@DisableCachingByDefault(because = "Downloads into Gradle's cache")
abstract class NjaGraalvmTask : DefaultTask() {
    @get:InputDirectory @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE) abstract val kit: DirectoryProperty
    @get:Input @get:Optional abstract val release: Property<String>
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:Internal abstract val offline: Property<Boolean>
    @get:OutputFile abstract val homeFile: RegularFileProperty

    init {
        // latest may have moved: checked every build (cheaply: the resolution is cached, GraalVM CE too)
        outputs.upToDateWhen { !release.isPresent || release.get() != NjaJdkMobileTask.LATEST }
    }

    @TaskAction
    fun stage() {
        val tag = if (kit.isPresent) {
            val properties = Properties().apply { File(kit.get().asFile, KIT_PROPERTIES).reader().use { load(it) } }
            properties.getProperty("graalvm.tag")?.takeIf { it.isNotEmpty() }
                ?: throw GradleException("NJA: the graalvm-mobile kit doesn't name its GraalVM release (graalvm.tag)")
        } else {
            GitHubReleases.resolve(UPSTREAM, release.getOrElse(NjaJdkMobileTask.LATEST), cacheDir.get().asFile, offline.get(), logger)
        }
        val dir = File(cacheDir.get().asFile, "$tag-${hostPlatform()}")
        val home = homeIn(dir)
        if (home == null || !File(home, nativeImageExecutable()).isFile) {
            if (offline.get()) throw GradleException("NJA: offline, and GraalVM CE $tag isn't in Gradle's cache")
            download(tag, dir)
        }
        homeFile.get().asFile.writeText(homeIn(dir)!!.canonicalPath)
    }

    // Its home, once extracted: named in home.path (no symbolic links on Windows), or by the link `home` of earlier versions
    private fun homeIn(dir: File): File? =
        File(dir, "home.path").takeIf { it.isFile }?.let { File(it.readText().trim()) } ?: File(dir, "home").takeIf { it.isDirectory }

    private fun download(tag: String, dir: File) {
        val suffix = "_${hostPlatform()}_bin.${if (isWindows()) "zip" else "tar.gz"}"
        val api = "https://api.github.com/repos/$UPSTREAM/releases/tags/$tag"
        val json = open(api).inputStream.use { it.readBytes().decodeToString() }
        val url = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+${Regex.escape(suffix)})\"").find(json)?.groupValues?.get(1)
            ?: throw GradleException("NJA: no GraalVM CE for ${hostPlatform()} in $UPSTREAM's release $tag")
        dir.deleteRecursively()
        val extracted = File(dir, "extracted").apply { mkdirs() }
        val archive = File(dir, "graalvm${suffix.substringAfter("_bin")}")
        logger.lifecycle("nja: downloading GraalVM CE $tag for ${hostPlatform()}")
        open(url).inputStream.use { input -> archive.outputStream().use { input.copyTo(it) } }
        // tar keeps the archive's symbolic links and permissions; Windows' own (bsdtar) reads zip archives too
        val tar = ProcessBuilder("tar", "-xf", archive.path, "-C", extracted.path).inheritIO().start()
        if (tar.waitFor() != 0) throw GradleException("NJA: can't extract $archive")
        archive.delete()
        val release = extracted.walkTopDown().maxDepth(5).firstOrNull { it.name == "release" && File(it.parentFile, "bin").isDirectory }
            ?: throw GradleException("NJA: no GraalVM home in $url")
        File(dir, "home.path").writeText(release.parentFile.canonicalPath)
    }

    // The GitHub API, with a token when there is one (its rate limit is the token's then)
    private fun open(url: String): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        (System.getenv("GITHUB_TOKEN") ?: System.getenv("GH_TOKEN"))?.takeIf { url.startsWith("https://api.github.com/") }?.let {
            connection.setRequestProperty("Authorization", "Bearer $it")
        }
        if (connection.responseCode !in 200..299) throw GradleException("NJA: $url: HTTP ${connection.responseCode}")
        return connection
    }

    companion object {
        const val UPSTREAM = "graalvm/graalvm-ce-builds"
        const val KIT_PROPERTIES = "graalvm-mobile.properties"

        fun isWindows() = System.getProperty("os.name").lowercase().startsWith("windows")

        /** native-image, relative to GraalVM's home. */
        fun nativeImageExecutable() = if (isWindows()) "bin/native-image.cmd" else "bin/native-image"

        /** The build host, as GraalVM CE names its archives. */
        fun hostPlatform(): String {
            val os = System.getProperty("os.name").lowercase()
            val arch = when (System.getProperty("os.arch")) {
                "aarch64", "arm64" -> "aarch64"
                "x86_64", "amd64" -> "x64"
                else -> throw GradleException("NJA: GraalVM CE has no build for ${System.getProperty("os.arch")}")
            }
            return when {
                os.startsWith("mac") -> "macos-$arch"
                os.startsWith("linux") -> "linux-$arch"
                os.startsWith("windows") -> "windows-$arch"
                else -> throw GradleException("NJA: GraalVM CE has no build for ${System.getProperty("os.name")}")
            }
        }
    }
}
