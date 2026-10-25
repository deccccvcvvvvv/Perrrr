package pro.cslauncher.core.content

import kotlinx.serialization.json.*
import pro.cslauncher.core.download.BatchProgress
import pro.cslauncher.core.download.DownloadManager
import pro.cslauncher.core.download.DownloadTask
import pro.cslauncher.core.install.SafeZip
import pro.cslauncher.core.profile.LoaderType
import pro.cslauncher.core.util.*
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

class ModpackException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class MrpackFile(
    val path: String, val sha1: String, val sha512: String, val size: Long?,
    val downloads: List<String>, val clientEnv: String?,
) {
    val clientSupported get() = clientEnv != "unsupported"
    val optional get() = clientEnv == "optional"
}

data class MrpackIndex(
    val name: String, val versionId: String, val summary: String?,
    val minecraftVersion: String, val loader: LoaderType, val loaderVersion: String?,
    val files: List<MrpackFile>,
)

/** Reader for Modrinth's .mrpack format (https://support.modrinth.com/en/articles/8802351-modrinth-modpack-format-mrpack). */
object Mrpack {
    const val INDEX = "modrinth.index.json"
    /** Download hosts the format allows. Anything else is refused rather than fetched. */
    val ALLOWED_HOSTS = setOf("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com")
    private const val MAX_OVERRIDE_BYTES = 4L * 1024 * 1024 * 1024
    private const val MAX_OVERRIDE_FILES = 100_000

    fun read(mrpack: Path): MrpackIndex = try {
        ZipFile(mrpack.toFile()).use { z ->
            val e = z.getEntry(INDEX) ?: throw ModpackException("Not a Modrinth modpack: $INDEX is missing")
            if (e.size > 32L * 1024 * 1024) throw ModpackException("Modpack index is unreasonably large")
            parse(z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) })
        }
    } catch (e: ModpackException) { throw e } catch (e: Exception) { throw ModpackException("Could not read the modpack: ${e.message}", e) }

    fun parse(text: String): MrpackIndex {
        val o = runCatching { CsJson.parseToJsonElement(text).jsonObject }.getOrElse { throw ModpackException("Modpack index is not valid JSON") }
        val fmt = o.int("formatVersion")
        if (fmt != 1) throw ModpackException("Unsupported modpack format version $fmt")
        if (o.str("game") != "minecraft") throw ModpackException("This modpack is not for Minecraft (game='${o.str("game")}')")
        val deps = o.obj("dependencies")?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() } ?: emptyMap()
        val mc = deps["minecraft"]?.takeIf { it.isNotBlank() } ?: throw ModpackException("Modpack does not declare a Minecraft version")
        val (loader, lv) = when {
            deps["fabric-loader"] != null -> LoaderType.FABRIC to deps["fabric-loader"]
            deps["quilt-loader"] != null -> LoaderType.QUILT to deps["quilt-loader"]
            deps["neoforge"] != null -> LoaderType.NEOFORGE to deps["neoforge"]
            deps["forge"] != null -> LoaderType.FORGE to deps["forge"]
            else -> LoaderType.VANILLA to null
        }
        val files = o.arr("files")?.map { el ->
            val f = el.jsonObject
            val path = checkPath(f.str("path") ?: throw ModpackException("A modpack file has no path"))
            val h = f.obj("hashes") ?: throw ModpackException("$path has no hashes")
            val sha1 = h.str("sha1")?.takeIf { HEX40.matches(it) } ?: throw ModpackException("$path has no valid sha1")
            val sha512 = h.str("sha512")?.takeIf { HEX128.matches(it) } ?: throw ModpackException("$path has no valid sha512")
            MrpackFile(path, sha1.lowercase(), sha512.lowercase(), f.long("fileSize"),
                f.arr("downloads")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                f.obj("env")?.str("client"))
        } ?: emptyList()
        val dupes = files.groupBy { it.path.lowercase() }.filter { it.value.size > 1 }.keys
        if (dupes.isNotEmpty()) throw ModpackException("Modpack lists the same file twice: ${dupes.first()}")
        return MrpackIndex(o.str("name")?.take(64)?.ifBlank { null } ?: "Modpack", o.str("versionId") ?: "", o.str("summary"), mc, loader, lv, files)
    }

    private val HEX40 = Regex("^[0-9a-fA-F]{40}$")
    private val HEX128 = Regex("^[0-9a-fA-F]{128}$")

    /** Relative, forward-slash path without traversal; files may not be dropped outside the game directory. */
    fun checkPath(p: String): String {
        val n = p.replace('\\', '/')
        if (n.isBlank() || n.startsWith("/") || n.contains('\u0000') || Regex("^[A-Za-z]:").containsMatchIn(n) || n.split('/').any { it == ".." || it == "." || it.isEmpty() })
            throw ModpackException("Unsafe file path in modpack: $p")
        return n
    }

    /** First HTTPS download URL on an allowed host, or null. */
    fun pickUrl(urls: List<String>): String? = urls.firstOrNull { u ->
        runCatching { val uri = URI(u); uri.scheme == "https" && uri.host?.lowercase() in ALLOWED_HOSTS && uri.userInfo == null }.getOrDefault(false)
    }

    /** Extracts overrides/ then client-overrides/ (client wins) into [gameDir], with traversal/symlink/zip-bomb protection. */
    fun extractOverrides(mrpack: Path, gameDir: Path): Int {
        var count = 0; var bytes = 0L
        ZipFile(mrpack.toFile()).use { z ->
            for (prefix in listOf("overrides/", "client-overrides/")) {
                for (e in z.entries().asSequence().filter { it.name.replace('\\', '/').startsWith(prefix) }.toList()) {
                    val rel = e.name.replace('\\', '/').removePrefix(prefix)
                    if (rel.isEmpty()) continue
                    val target = SafeZip.safeTarget(gameDir, rel)
                    var p: Path? = target.parent
                    while (p != null && p.startsWith(gameDir.toAbsolutePath().normalize())) {
                        if (Files.isSymbolicLink(p)) throw ModpackException("Refusing to write through symbolic link $p"); p = p.parent
                    }
                    if (e.isDirectory || rel.endsWith("/")) { Files.createDirectories(target); continue }
                    if (++count > MAX_OVERRIDE_FILES) throw ModpackException("Too many override files")
                    Files.createDirectories(target.parent)
                    if (Files.isSymbolicLink(target)) throw ModpackException("Refusing to overwrite symbolic link $target")
                    z.getInputStream(e).use { ins ->
                        Files.newOutputStream(target).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = ins.read(buf); if (n < 0) break
                                bytes += n
                                if (bytes > MAX_OVERRIDE_BYTES) throw ModpackException("Modpack overrides exceed 4 GB; refusing to extract")
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
        }
        return count
    }
}

data class ModpackPlan(val index: MrpackIndex, val tasks: List<DownloadTask>, val skipped: List<String>, val totalBytes: Long)
data class ModpackResult(val downloaded: Int, val overrides: Int, val skipped: List<String>)

/** Downloads a parsed modpack's files into a game directory and applies its overrides. Loader/vanilla installation is done by the caller. */
class ModpackInstaller(
    private val downloads: DownloadManager,
    /** URL policy; tests substitute a local server, production always uses the host allow-list. */
    private val pickUrl: (List<String>) -> String? = Mrpack::pickUrl,
) {
    fun plan(index: MrpackIndex, gameDir: Path, includeOptional: Boolean = true): ModpackPlan {
        val skipped = mutableListOf<String>(); val tasks = mutableListOf<DownloadTask>()
        for (f in index.files) {
            if (!f.clientSupported) { skipped += "${f.path} (server-only)"; continue }
            if (f.optional && !includeOptional) { skipped += "${f.path} (optional)"; continue }
            val url = pickUrl(f.downloads) ?: throw ModpackException("${f.path} has no download from an allowed host (${Mrpack.ALLOWED_HOSTS.joinToString()})")
            tasks += DownloadTask(url, SafeZip.safeTarget(gameDir, f.path), Integrity(sha1 = f.sha1, sha512 = f.sha512, size = f.size), f.path.substringAfterLast('/'))
        }
        return ModpackPlan(index, tasks, skipped, tasks.sumOf { it.integrity.size ?: 0L })
    }

    fun checkLoaderSupported(index: MrpackIndex) {
        if (index.loader == LoaderType.FORGE || index.loader == LoaderType.NEOFORGE)
            throw ModpackException("${index.name} needs ${index.loader.name.lowercase().replaceFirstChar { it.uppercase() }}, which this launcher cannot install yet. Pick a Fabric or Quilt modpack.")
        if (index.loader != LoaderType.VANILLA && index.loaderVersion.isNullOrBlank()) throw ModpackException("Modpack does not say which ${index.loader} version it needs")
    }

    suspend fun installFiles(mrpack: Path, gameDir: Path, onProgress: (BatchProgress) -> Unit = {}): ModpackResult {
        val index = Mrpack.read(mrpack)
        checkLoaderSupported(index)
        Files.createDirectories(gameDir)
        val plan = plan(index, gameDir)
        val r = downloads.downloadAll("Modpack ${index.name}", plan.tasks, onProgress)
        if (!r.ok) throw ModpackException("Some modpack files failed to download: ${r.summary()}")
        val overrides = Mrpack.extractOverrides(mrpack, gameDir)
        FsUtil.writeAtomic(gameDir.resolve("cs-modpack.json"), buildJsonObject {
            put("name", index.name); put("version", index.versionId); put("minecraft", index.minecraftVersion)
            put("loader", index.loader.name); index.loaderVersion?.let { put("loaderVersion", it) }
            put("installedAtMs", System.currentTimeMillis())
        }.toString())
        CsLog.i("Modpack", "Installed ${index.name} ${index.versionId}: ${plan.tasks.size} files, $overrides overrides, ${plan.skipped.size} skipped")
        return ModpackResult(plan.tasks.size, overrides, plan.skipped)
    }
}
