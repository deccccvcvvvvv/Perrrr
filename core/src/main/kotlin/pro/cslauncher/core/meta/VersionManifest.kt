package pro.cslauncher.core.meta

import pro.cslauncher.core.net.HttpTransport
import pro.cslauncher.core.util.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

enum class VersionType(val id: String) {
    RELEASE("release"), SNAPSHOT("snapshot"), OLD_BETA("old_beta"), OLD_ALPHA("old_alpha"), MODDED("modded");
    companion object { fun of(s: String?) = entries.firstOrNull { it.id == s } ?: MODDED }
}

data class ManifestEntry(val id: String, val type: VersionType, val url: String, val sha1: String?, val releaseTime: String, val complianceLevel: Int?)

data class VersionManifest(val latestRelease: String?, val latestSnapshot: String?, val versions: List<ManifestEntry>, val fetchedAt: Instant, val fromCache: Boolean) {
    fun find(id: String) = versions.firstOrNull { it.id == id }
    fun filter(types: Set<VersionType>, query: String = "") = versions.filter {
        it.type in types && (query.isBlank() || it.id.contains(query.trim(), ignoreCase = true))
    }

    companion object {
        const val URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        fun parse(text: String, fetchedAt: Instant, fromCache: Boolean): VersionManifest {
            val o = parseJsonObject(text, "Version manifest")
            val list = o.arr("versions") ?: throw MetadataException("Version manifest has no 'versions' array")
            val entries = list.mapNotNull { el ->
                val v = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val id = v.str("id") ?: return@mapNotNull null
                val url = v.str("url") ?: return@mapNotNull null
                ManifestEntry(id, VersionType.of(v.str("type")), url, v.str("sha1"), v.str("releaseTime") ?: "", v.int("complianceLevel"))
            }
            if (entries.isEmpty()) throw MetadataException("Version manifest contains no versions")
            val latest = o.obj("latest")
            return VersionManifest(latest?.str("release"), latest?.str("snapshot"), entries, fetchedAt, fromCache)
        }
    }
}

/** Fetches the official manifest with an on-disk cache; falls back to the cache when offline. */
class VersionManifestRepository(private val http: HttpTransport, private val cacheFile: Path, private val maxAge: Duration = Duration.ofHours(6)) {
    fun load(forceRefresh: Boolean = false): VersionManifest {
        val cached = readCache()
        if (!forceRefresh && cached != null && Duration.between(cached.fetchedAt, Instant.now()) < maxAge) return cached
        return try {
            val text = http.getText(VersionManifest.URL)
            val m = VersionManifest.parse(text, Instant.now(), false)
            FsUtil.writeAtomic(cacheFile, text)
            m
        } catch (e: Exception) {
            if (cached != null) { CsLog.w("Manifest", "Using cached manifest: ${e.message}"); cached }
            else throw MetadataException("Could not download the version list and no cached copy exists: ${e.message}", e)
        }
    }
    private fun readCache(): VersionManifest? = runCatching {
        if (!Files.isRegularFile(cacheFile)) return null
        VersionManifest.parse(pro.cslauncher.core.util.FsUtil.readText(cacheFile), Files.getLastModifiedTime(cacheFile).toInstant(), true)
    }.getOrNull()
}
