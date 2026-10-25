package pro.cslauncher.core.content

import kotlinx.serialization.json.*
import pro.cslauncher.core.download.DownloadTask
import pro.cslauncher.core.net.HttpTransport
import pro.cslauncher.core.util.*
import java.net.URLEncoder
import java.nio.file.Path

enum class ProjectKind(val modrinth: String) { MOD("mod"), RESOURCE_PACK("resourcepack"), SHADER("shader"), DATAPACK("datapack") }
data class CatalogProject(val source: String, val id: String, val slug: String, val title: String, val description: String, val downloads: Long, val iconUrl: String?, val author: String?, val categories: List<String>)
data class CatalogFile(val source: String, val projectId: String, val versionName: String, val fileName: String, val url: String, val sha1: String?, val sha512: String?, val size: Long?,
                       val gameVersions: List<String>, val loaders: List<String>, val requiredDependencies: List<String>) {
    fun task(dir: Path) = DownloadTask(url, dir.resolve(FsUtil.safeFileName(fileName)), Integrity(sha1 = sha1, sha512 = sha512, size = size), fileName)
}

interface ContentCatalog {
    val name: String
    val available: Boolean
    fun search(query: String, kind: ProjectKind, gameVersion: String?, loader: String?, offset: Int = 0): List<CatalogProject>
    fun files(projectId: String, gameVersion: String?, loader: String?): List<CatalogFile>
}

/** Modrinth public API v2 (no key needed; a descriptive User-Agent is required by their terms). */
class ModrinthCatalog(private val http: HttpTransport) : ContentCatalog {
    override val name = "Modrinth"; override val available = true
    private val base = "https://api.modrinth.com/v2"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    override fun search(query: String, kind: ProjectKind, gameVersion: String?, loader: String?, offset: Int): List<CatalogProject> {
        val facets = buildList { add("[\"project_type:${kind.modrinth}\"]"); gameVersion?.let { add("[\"versions:$it\"]") }; if (loader != null && kind == ProjectKind.MOD) add("[\"categories:$loader\"]") }
        val o = parseJsonObject(http.getText("$base/search?query=${enc(query)}&limit=20&offset=$offset&facets=${enc(facets.joinToString(",", "[", "]"))}"), "Modrinth search")
        return o.arr("hits")?.map { h -> val p = h.jsonObject
            CatalogProject("modrinth", p.str("project_id")!!, p.str("slug") ?: "", p.str("title") ?: "", p.str("description") ?: "", p.long("downloads") ?: 0, p.str("icon_url"), p.str("author"), p.arr("categories")?.map { it.jsonPrimitive.content } ?: emptyList())
        } ?: emptyList()
    }

    override fun files(projectId: String, gameVersion: String?, loader: String?): List<CatalogFile> {
        require(Regex("^[A-Za-z0-9_-]{1,64}$").matches(projectId)) { "Invalid project id" }
        val q = buildList { loader?.let { add("loaders=${enc("[\"$it\"]")}") }; gameVersion?.let { add("game_versions=${enc("[\"$it\"]")}") } }.joinToString("&")
        val arr = CsJson.parseToJsonElement(http.getText("$base/project/$projectId/version" + if (q.isNotEmpty()) "?$q" else "")).jsonArray
        return arr.mapNotNull { v -> val o = v.jsonObject
            val f = o.arr("files")?.map { it.jsonObject }?.let { fs -> fs.firstOrNull { it.bool("primary") == true } ?: fs.firstOrNull() } ?: return@mapNotNull null
            val url = f.str("url") ?: return@mapNotNull null
            if (!url.startsWith("https://")) return@mapNotNull null
            CatalogFile("modrinth", projectId, o.str("version_number") ?: "", f.str("filename") ?: "download.jar", url, f.obj("hashes")?.str("sha1"), f.obj("hashes")?.str("sha512"), f.long("size"),
                o.arr("game_versions")?.map { it.jsonPrimitive.content } ?: emptyList(), o.arr("loaders")?.map { it.jsonPrimitive.content } ?: emptyList(),
                o.arr("dependencies")?.mapNotNull { d -> d.jsonObject.takeIf { it.str("dependency_type") == "required" }?.str("project_id") } ?: emptyList())
        }
    }
}

/**
 * CurseForge Core API. Requires a user-supplied API key (their terms forbid embedding keys in public apps).
 * Without a key the catalog reports itself unavailable instead of failing obscurely.
 */
class CurseForgeCatalog(private val http: HttpTransport, private val apiKey: String?) : ContentCatalog {
    override val name = "CurseForge"; override val available get() = !apiKey.isNullOrBlank()
    private val base = "https://api.curseforge.com/v1"
    private fun h() = mapOf("x-api-key" to (apiKey ?: throw IllegalStateException("CurseForge needs an API key (Settings › Network)")), "Accept" to "application/json")
    private val classIds = mapOf(ProjectKind.MOD to 6, ProjectKind.RESOURCE_PACK to 12, ProjectKind.SHADER to 6552, ProjectKind.DATAPACK to 6945)
    private val loaderIds = mapOf("forge" to 1, "fabric" to 4, "quilt" to 5, "neoforge" to 6)

    override fun search(query: String, kind: ProjectKind, gameVersion: String?, loader: String?, offset: Int): List<CatalogProject> {
        val q = buildString {
            append("gameId=432&classId=${classIds[kind]}&pageSize=20&index=$offset&searchFilter=${URLEncoder.encode(query, "UTF-8")}")
            gameVersion?.let { append("&gameVersion=$it") }; loader?.let { l -> loaderIds[l]?.let { append("&modLoaderType=$it") } }
        }
        val o = parseJsonObject(http.getText("$base/mods/search?$q", h()), "CurseForge search")
        return o.arr("data")?.map { m -> val p = m.jsonObject
            CatalogProject("curseforge", p.long("id").toString(), p.str("slug") ?: "", p.str("name") ?: "", p.str("summary") ?: "", p.long("downloadCount") ?: 0,
                p.obj("logo")?.str("thumbnailUrl"), p.arr("authors")?.firstOrNull()?.jsonObject?.str("name"), p.arr("categories")?.mapNotNull { it.jsonObject.str("name") } ?: emptyList())
        } ?: emptyList()
    }

    override fun files(projectId: String, gameVersion: String?, loader: String?): List<CatalogFile> {
        require(projectId.all { it.isDigit() }) { "Invalid project id" }
        val q = buildList { gameVersion?.let { add("gameVersion=$it") }; loader?.let { l -> loaderIds[l]?.let { add("modLoaderType=$it") } } }.joinToString("&")
        val o = parseJsonObject(http.getText("$base/mods/$projectId/files?$q", h()), "CurseForge files")
        return o.arr("data")?.mapNotNull { f -> val x = f.jsonObject
            val url = x.str("downloadUrl") ?: return@mapNotNull null   // null when the author disallows third-party downloads
            val sha1 = x.arr("hashes")?.firstOrNull { it.jsonObject.int("algo") == 1 }?.jsonObject?.str("value")
            CatalogFile("curseforge", projectId, x.str("displayName") ?: "", x.str("fileName") ?: "file.jar", url, sha1, null, x.long("fileLength"),
                x.arr("gameVersions")?.map { it.jsonPrimitive.content } ?: emptyList(), emptyList(),
                x.arr("dependencies")?.mapNotNull { d -> d.jsonObject.takeIf { it.int("relationType") == 3 }?.long("modId")?.toString() } ?: emptyList())
        } ?: emptyList()
    }
}
