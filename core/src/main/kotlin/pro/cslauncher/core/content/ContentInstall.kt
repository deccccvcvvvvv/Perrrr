package pro.cslauncher.core.content

import kotlinx.serialization.Serializable
import pro.cslauncher.core.download.BatchProgress
import pro.cslauncher.core.download.DownloadManager
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** One catalog project the launcher installed into a game directory. */
@Serializable
data class IndexedContent(
    val source: String, val projectId: String, val title: String, val fileName: String, val kind: String,
    val versionName: String, val dependencyOf: String? = null, val atMs: Long = System.currentTimeMillis(),
) {
    val key get() = ContentIndex.key(source, projectId)
}

@Serializable private data class ContentIndexFile(val version: Int = 1, val items: List<IndexedContent> = emptyList())

/**
 * Remembers which catalog projects were installed into a game directory, so the browser can show "Installed" and
 * dependency resolution can skip what is already there. Entries whose file the user removed are ignored.
 */
class ContentIndex(private val gameDir: Path) {
    private val file = gameDir.resolve(".cs-content.json")

    private fun readAll(): List<IndexedContent> =
        if (!Files.isRegularFile(file)) emptyList()
        else runCatching { CsJson.decodeFromString(ContentIndexFile.serializer(), String(Files.readAllBytes(file), Charsets.UTF_8)).items }.getOrDefault(emptyList())

    fun load(): List<IndexedContent> = readAll().filter { e ->
        val kind = runCatching { ProjectKind.valueOf(e.kind) }.getOrNull() ?: return@filter false
        kind != ProjectKind.MODPACK && Files.isRegularFile(gameDir.resolve(dirFor(kind)).resolve(e.fileName))
    }

    fun installed(kind: ProjectKind): Map<String, IndexedContent> = load().filter { it.kind == kind.name }.associateBy { it.key }

    @Synchronized fun record(items: List<IndexedContent>) {
        val keys = items.map { it.key }.toSet()
        val merged = load().filterNot { it.key in keys } + items
        Files.createDirectories(gameDir)
        FsUtil.writeAtomic(file, CsJson.encodeToString(ContentIndexFile.serializer(), ContentIndexFile(items = merged)))
    }

    companion object {
        fun key(source: String, projectId: String) = "$source:$projectId"
        fun dirFor(kind: ProjectKind): String = when (kind) {
            ProjectKind.MOD -> "mods"
            ProjectKind.RESOURCE_PACK -> "resourcepacks"
            ProjectKind.SHADER -> "shaderpacks"
            ProjectKind.DATAPACK -> "datapacks"
            ProjectKind.MODPACK -> throw IllegalArgumentException("Modpacks are installed as profiles, not into a folder")
        }

        private val VERSION_START = Regex("""^(.+?)[-_+]v?\d""")
        /** "fabric-api-0.92.0+1.20.1.jar" -> "fabric-api"; used to notice a manually added copy of a dependency. */
        fun baseName(fileName: String): String {
            val n = fileName.lowercase().removeSuffix(".disabled").removeSuffix(".jar").removeSuffix(".zip")
            return VERSION_START.find(n)?.groupValues?.get(1) ?: n
        }
    }
}

data class CatalogInstallResult(
    val installed: List<IndexedContent>,
    /** Older files of the same projects that the new versions replaced. */
    val replaced: List<String>,
    /** Required dependencies that were already present (installed by us or added manually). */
    val alreadyPresent: List<String>,
    /** Required dependencies with no file for this game version/loader. */
    val unresolved: List<String>,
) {
    fun summary(mainTitle: String): String = buildString {
        append("Installed $mainTitle")
        val deps = installed.size - 1
        if (deps > 0) append(" + $deps required dependenc${if (deps == 1) "y" else "ies"}")
        if (replaced.isNotEmpty()) append(" (replaced ${replaced.size} older file${if (replaced.size == 1) "" else "s"})")
        if (unresolved.isNotEmpty()) append(". ${unresolved.size} dependency(ies) have no compatible file")
    }
}

/** Installs a catalog file into a game directory together with its required dependencies (mods only), checksum verified. */
class CatalogInstaller(private val downloads: DownloadManager, private val maxDependencies: Int = 30) {

    suspend fun install(
        catalog: ContentCatalog, title: String, file: CatalogFile, kind: ProjectKind, gameDir: Path,
        gameVersion: String?, loader: String?, withDependencies: Boolean = true, onProgress: (BatchProgress) -> Unit = {},
    ): CatalogInstallResult {
        val index = ContentIndex(gameDir)
        val have = index.installed(kind)
        val dir = gameDir.resolve(ContentIndex.dirFor(kind))
        Files.createDirectories(dir)
        val existingBases = (dir.toFile().listFiles()?.filter { it.isFile }?.map { ContentIndex.baseName(it.name) } ?: emptyList()).toSet()

        val plan = LinkedHashMap<String, Pair<CatalogFile, IndexedContent>>()
        fun entry(f: CatalogFile, t: String, parent: String?) =
            IndexedContent(f.source, f.projectId, t, FsUtil.safeFileName(f.fileName), kind.name, f.versionName, parent)
        plan[ContentIndex.key(file.source, file.projectId)] = file to entry(file, title, null)

        val present = mutableListOf<String>(); val unresolved = mutableListOf<String>()
        if (withDependencies && kind == ProjectKind.MOD) {
            val queue = ArrayDeque(file.requiredDependencies.map { it to title })
            val seen = mutableSetOf(file.projectId)
            while (queue.isNotEmpty() && plan.size <= maxDependencies) {
                val (dep, parent) = queue.removeFirst()
                if (!seen.add(dep)) continue
                val k = ContentIndex.key(file.source, dep)
                val known = have[k]
                if (known != null) { present += known.title; continue }
                val f = try { catalog.files(dep, gameVersion, loader).firstOrNull() } catch (e: Exception) {
                    CsLog.w("Content", "Dependency $dep lookup failed: ${e.message}"); null
                }
                if (f == null) { unresolved += dep; continue }
                if (ContentIndex.baseName(f.fileName) in existingBases) { present += f.fileName; continue }
                plan[k] = f to entry(f, f.fileName.substringBeforeLast('.'), parent)
                f.requiredDependencies.forEach { queue += it to f.fileName }
            }
        }

        val r = downloads.downloadAll("content", plan.values.map { it.first.task(dir) }, onProgress)
        if (!r.ok) throw IOException("Download failed: ${r.summary()}")
        val replaced = plan.values.mapNotNull { (_, e) ->
            have[e.key]?.takeIf { it.fileName != e.fileName }?.let { old -> Files.deleteIfExists(dir.resolve(old.fileName)); old.fileName }
        }
        val items = plan.values.map { it.second }
        index.record(items)
        CsLog.i("Content", "Installed ${items.size} file(s) into ${dir.fileName}: ${items.joinToString { it.fileName }}")
        return CatalogInstallResult(items, replaced, present, unresolved)
    }
}
