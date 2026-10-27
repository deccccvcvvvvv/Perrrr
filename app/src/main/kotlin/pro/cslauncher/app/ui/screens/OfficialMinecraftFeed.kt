package pro.cslauncher.app.ui.screens

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Official Mojang Java patch-note feed. Its images are version-specific official launcher artwork. */
data class OfficialPatchNote(
    val title: String,
    val version: String,
    val type: String,
    val imageUrl: String?,
    val date: String,
    val shortText: String,
) {
    val articleUrl: String
        get() {
            val slug = version.lowercase().replace('.', '-')
            val articleSlug = if (type.equals("snapshot", true) || type.equals("pre-release", true) || type.equals("release-candidate", true))
                "minecraft-$slug" else "minecraft-java-edition-$slug"
            return "https://www.minecraft.net/en-us/article/$articleSlug"
        }
}

object OfficialMinecraftFeed {
    private const val FEED_URL = "https://launchercontent.mojang.com/v2/javaPatchNotes.json"
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_AGE_MS = 4 * 60 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(context: Context, forceRefresh: Boolean = false): List<OfficialPatchNote> = withContext(Dispatchers.IO) {
        val cache = File(context.cacheDir, "official-minecraft-java-patch-notes.json")
        val cached = if (cache.isFile) runCatching { cache.readText() }.getOrNull() else null
        val fresh = cache.isFile && System.currentTimeMillis() - cache.lastModified() < MAX_AGE_MS
        if (!forceRefresh && fresh && !cached.isNullOrBlank()) parse(cached)?.let { return@withContext it }

        val downloaded = runCatching { download() }.getOrNull()
        if (downloaded != null) {
            runCatching { cache.parentFile?.mkdirs(); cache.writeText(downloaded) }
            parse(downloaded)?.let { return@withContext it }
        }
        cached?.let(::parse).orEmpty()
    }

    private fun download(): String {
        val c = URL(FEED_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 12_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "CSLauncherPro/1.0 (official Minecraft Java news)")
        try {
            require(c.responseCode == HttpURLConnection.HTTP_OK) { "Official Minecraft news returned HTTP ${c.responseCode}" }
            require(c.contentLengthLong <= MAX_BYTES || c.contentLengthLong < 0) { "Official Minecraft news feed is too large" }
            val bytes = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES) { "Official Minecraft news feed is too large" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    private fun parse(raw: String): List<OfficialPatchNote>? = runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        val entries = root["entries"]?.jsonArray ?: JsonArray(emptyList())
        entries.mapNotNull { element ->
            val item = element.jsonObject
            val version = item.string("version") ?: return@mapNotNull null
            val imagePath = (item["image"] as? JsonObject)?.string("url")
            OfficialPatchNote(
                title = item.string("title") ?: "Minecraft $version",
                version = version,
                type = item.string("type") ?: "release",
                imageUrl = officialImageUrl(imagePath),
                date = item.string("date").orEmpty(),
                shortText = item.string("shortText").orEmpty(),
            )
        }
    }.getOrNull()

    private fun JsonObject.string(key: String): String? = runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    /** Relative paths from Mojang's JSON are resolved against the official launcher content host only. */
    private fun officialImageUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val url = if (path.startsWith("https://")) path else "https://launchercontent.mojang.com/${path.trimStart('/')}"
        val parsed = runCatching { URL(url) }.getOrNull() ?: return null
        return url.takeIf { parsed.protocol == "https" && parsed.host.equals("launchercontent.mojang.com", true) }
    }
}
