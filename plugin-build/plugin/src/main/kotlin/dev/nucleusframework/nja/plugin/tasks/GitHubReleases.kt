package dev.nucleusframework.nja.plugin.tasks

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Properties

/**
 * A GitHub repository's releases: `latest` resolved through the release page's redirect (no API, no rate limit) and
 * kept a day in [cacheDir]; offline or the network down, the last one resolved.
 */
internal object GitHubReleases {
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    fun resolve(repo: String, release: String, cacheDir: File, offline: Boolean, logger: Logger): String {
        if (release != NjaJdkMobileTask.LATEST) return release
        val cache = File(cacheDir, "latest-${repo.replace('/', '_')}.properties")
        val known = Properties().apply { if (cache.isFile) cache.reader().use { load(it) } }
        val knownTag = known.getProperty("tag")
        val fresh = System.currentTimeMillis() - (known.getProperty("at")?.toLongOrNull() ?: 0L) < DAY_MILLIS
        if (knownTag != null && (fresh || offline)) return knownTag
        if (offline) throw GradleException("NJA: offline, and $repo's latest release was never resolved")
        val tag = try {
            latestTag(repo)
        } catch (e: Exception) {
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
}
