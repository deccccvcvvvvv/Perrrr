package pro.cslauncher.core.install

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.cslauncher.core.download.*
import pro.cslauncher.core.meta.*
import pro.cslauncher.core.net.HttpTransport
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.util.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

enum class InstallStage(val title: String) {
    RESOLVE("Resolving version"), DIRECTORIES("Creating directories"), CLIENT("Downloading client"),
    LIBRARIES("Downloading libraries"), ASSETS("Downloading assets"), NATIVES("Preparing native libraries"),
    LOADER("Installing mod loader"), VERIFY("Verifying installation")
}

data class InstallEvent(val stage: InstallStage, val message: String, val progress: BatchProgress? = null)

class InstallException(val stage: InstallStage, message: String, cause: Throwable? = null) : Exception("[${stage.title}] $message", cause)

/** How each library resolves for the current platform. */
data class ResolvedLibrary(val library: Library, val file: Path, val download: Download?, val isNative: Boolean, val onClasspath: Boolean)

/** Everything needed to install/verify/launch one version on one platform. */
data class ResolvedVersion(
    val meta: VersionMetadata,
    val chain: List<String>,                // [child, parent, ...]
    val jarVersionId: String,               // version whose client jar is used
    val libraries: List<ResolvedLibrary>,
    val skippedByRules: List<String>,
    val desktopNativesReplaced: List<String>, // Android: glibc natives that must come from an Android component
)

class Installer(
    private val dirs: LauncherDirs,
    private val http: HttpTransport,
    private val downloads: DownloadManager,
    private val platform: PlatformInfo,
    private val assetBaseUrl: String = AssetObject.DEFAULT_BASE,
) {
    // ---------- Stage 1: resolve ----------
    suspend fun resolve(versionId: String, manifest: VersionManifest?): ResolvedVersion = withContext(Dispatchers.IO) {
        val chain = mutableListOf<String>()
        var meta = loadOrFetchJson(versionId, manifest)
        chain += meta.id
        var current = meta
        var jarId = if (current.clientDownload != null) current.id else versionId
        while (current.inheritsFrom != null) {
            val parentId = current.inheritsFrom!!
            if (parentId in chain) throw MetadataException("Circular inheritsFrom chain: ${chain + parentId}")
            val parent = loadOrFetchJson(parentId, manifest)
            chain += parent.id
            if (meta.clientDownload == null && parent.clientDownload != null) jarId = parent.id
            meta = VersionMetadata.merge(meta, parent)
            current = parent
        }
        if (meta.mainClass.isBlank()) throw MetadataException("Resolved version $versionId has no main class")
        resolveLibraries(meta, chain, jarId)
    }

    fun resolveLibraries(meta: VersionMetadata, chain: List<String>, jarId: String): ResolvedVersion {
        val out = mutableListOf<ResolvedLibrary>(); val skipped = mutableListOf<String>(); val replaced = mutableListOf<String>()
        val bitsArch = if (platform.is64Bit) "64" else "32"
        for (lib in meta.libraries) {
            if (!RuleEvaluator.allowed(lib.rules, platform)) { skipped += lib.name; continue }
            // Legacy natives: classifier chosen through the "natives" map.
            if (lib.natives.isNotEmpty()) {
                val cls = lib.natives[platform.osName]?.replace("\${arch}", bitsArch)
                if (cls != null) {
                    if (platform.isAndroid) replaced += "${lib.name}:$cls"
                    else {
                        val d = lib.classifiers[cls] ?: Download(
                            (lib.mavenBaseUrl ?: "https://libraries.minecraft.net/").trimEnd('/') + "/" + lib.coordinate.withClassifier(cls).path(),
                            Integrity(), lib.coordinate.withClassifier(cls).path())
                        out += ResolvedLibrary(lib, dirs.libraryFile(d.path ?: lib.coordinate.withClassifier(cls).path()), d, isNative = true, onClasspath = false)
                    }
                }
                if (lib.artifact != null) out += ResolvedLibrary(lib, dirs.libraryFile(lib.artifact.path ?: lib.coordinate.path()), lib.artifact, false, true)
                continue
            }
            if (lib.isNativeClassifierCoordinate) {
                // Modern (1.19+) natives are separate coordinates such as org.lwjgl:lwjgl:3.3.1:natives-linux.
                if (platform.isAndroid) { replaced += lib.name; continue }
                val a = lib.artifact ?: continue
                out += ResolvedLibrary(lib, dirs.libraryFile(a.path ?: lib.coordinate.path()), a, isNative = true, onClasspath = true)
                continue
            }
            val a = lib.artifact
            val path = a?.path ?: lib.coordinate.path()
            out += ResolvedLibrary(lib, dirs.libraryFile(path), a, isNative = false, onClasspath = true)
        }
        // Drop exact duplicates of the same file (same artifact listed twice).
        val dedup = out.distinctBy { it.file.toString() + it.isNative }
        return ResolvedVersion(meta, chain, jarId, dedup, skipped, replaced)
    }

    private suspend fun loadOrFetchJson(id: String, manifest: VersionManifest?): VersionMetadata {
        val f = dirs.versionJson(id)
        val entry = manifest?.find(id)
        if (Files.isRegularFile(f)) {
            val valid = entry?.sha1 == null || Hashing.sha1(f).equals(entry.sha1, true)
            if (valid) return runCatching { VersionMetadata.parse(pro.cslauncher.core.util.FsUtil.readText(f)) }.getOrElse {
                throw MetadataException("Stored metadata for $id is corrupt (${it.message}). Use Repair to re-download it.", it)
            }
            CsLog.w("Installer", "Metadata for $id changed upstream or is corrupt; re-downloading")
        }
        if (entry == null) throw MetadataException("Version '$id' is not installed and is not in the official version list")
        val r = downloads.downloadOne(DownloadTask(entry.url, f, Integrity(sha1 = entry.sha1), "$id.json"))
        if (r.state == TaskState.FAILED) throw MetadataException("Could not download metadata for $id: ${r.error}")
        return VersionMetadata.parse(pro.cslauncher.core.util.FsUtil.readText(f))
    }

    // ---------- Plan downloads (used by install and targeted repair) ----------
    fun clientTask(rv: ResolvedVersion): DownloadTask? {
        val d = rv.meta.clientDownload ?: return null
        return DownloadTask(d.url, dirs.versionJar(rv.jarVersionId), d.integrity, "${rv.jarVersionId}.jar")
    }
    fun libraryTasks(rv: ResolvedVersion): List<DownloadTask> = rv.libraries.mapNotNull { rl ->
        rl.download?.takeIf { it.url.isNotBlank() }?.let { DownloadTask(it.url, rl.file, it.integrity, rl.library.name) }
    }
    fun assetIndexTask(rv: ResolvedVersion): DownloadTask? = rv.meta.assetIndex?.let {
        DownloadTask(it.url, dirs.assetIndexFile(it.id), Integrity(sha1 = it.sha1, size = it.size), "asset index ${it.id}")
    }
    fun assetTasks(index: AssetIndex): List<DownloadTask> = index.objects.distinctBy { it.hash }.map {
        DownloadTask(it.url(assetBaseUrl), dirs.assetObjects.resolve(it.relativeObjectPath), Integrity(sha1 = it.hash, size = it.size.takeIf { s -> s >= 0 }), it.name)
    }
    fun loggingTask(rv: ResolvedVersion): DownloadTask? = rv.meta.logging?.let {
        DownloadTask(it.download.url, dirs.logConfigs.resolve(LauncherDirs.checkId(it.fileId)), it.download.integrity, it.fileId)
    }

    // ---------- Full installation ----------
    suspend fun install(versionId: String, manifest: VersionManifest?, onEvent: (InstallEvent) -> Unit = {}): InstallReport {
        fun stage(s: InstallStage, msg: String, p: BatchProgress? = null) = onEvent(InstallEvent(s, msg, p))
        stage(InstallStage.RESOLVE, "Reading metadata for $versionId")
        val rv = try { resolve(versionId, manifest) } catch (e: Exception) { throw InstallException(InstallStage.RESOLVE, e.message ?: "metadata error", e) }

        stage(InstallStage.DIRECTORIES, "Preparing directories")
        withContext(Dispatchers.IO) { dirs.createBase() }

        suspend fun batch(s: InstallStage, name: String, tasks: List<DownloadTask>) {
            if (tasks.isEmpty()) return
            val res = try { downloads.downloadAll(name, tasks) { stage(s, it.currentFile ?: name, it) } }
                      catch (e: InsufficientStorageException) { throw InstallException(s, e.message!!, e) }
            if (!res.ok) throw InstallException(s, res.summary(), DownloadFailedException(res))
        }
        batch(InstallStage.CLIENT, "$versionId client", listOfNotNull(clientTask(rv), loggingTask(rv)))
        batch(InstallStage.LIBRARIES, "$versionId libraries", libraryTasks(rv))

        val idxTask = assetIndexTask(rv)
        if (idxTask != null) {
            batch(InstallStage.ASSETS, "$versionId asset index", listOf(idxTask))
            val index = withContext(Dispatchers.IO) { AssetIndex.parse(pro.cslauncher.core.util.FsUtil.readText(idxTask.destination)) }
            batch(InstallStage.ASSETS, "$versionId assets", assetTasks(index))
            withContext(Dispatchers.IO) { materializeVirtualAssets(rv, index) }
        }

        stage(InstallStage.NATIVES, if (platform.isAndroid) "Natives are provided by the Android runtime component" else "Extracting natives")
        withContext(Dispatchers.IO) { extractNatives(rv) }

        stage(InstallStage.VERIFY, "Verifying files")
        val report = Verifier(dirs, this, platform).verify(rv, fullHash = true)
        stage(InstallStage.VERIFY, report.summary())
        return report
    }

    /** Legacy "virtual" asset indexes need a name-based copy under assets/virtual/<index>. */
    fun materializeVirtualAssets(rv: ResolvedVersion, index: AssetIndex) {
        if (!index.virtual) return
        val id = rv.meta.assetIndex?.id ?: return
        val root = dirs.assets.resolve("virtual").resolve(LauncherDirs.checkId(id))
        for (o in index.objects) {
            val target = SafeZip.safeTarget(root, o.name)
            if (Files.isRegularFile(target) && Files.size(target) == o.size) continue
            Files.createDirectories(target.parent)
            Files.copy(dirs.assetObjects.resolve(o.relativeObjectPath), target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Pre-1.6 "map_to_resources": copy into <gameDir>/resources at launch time (profile specific). */
    fun materializeResources(rv: ResolvedVersion, gameDir: Path) {
        val id = rv.meta.assetIndex?.id ?: return
        val f = dirs.assetIndexFile(id); if (!Files.isRegularFile(f)) return
        val index = AssetIndex.parse(pro.cslauncher.core.util.FsUtil.readText(f)); if (!index.mapToResources) return
        val root = gameDir.resolve("resources")
        for (o in index.objects) {
            val t = SafeZip.safeTarget(root, o.name)
            if (Files.isRegularFile(t) && Files.size(t) == o.size) continue
            Files.createDirectories(t.parent); Files.copy(dirs.assetObjects.resolve(o.relativeObjectPath), t, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Rebuilds the launcher-owned natives directory for desktop hosts (never touches user data). */
    fun extractNatives(rv: ResolvedVersion) {
        if (platform.isAndroid) return
        val out = dirs.nativesDir(rv.meta.id)
        if (Files.exists(out)) FsUtil.deleteTreeWithin(dirs.nativeRoot, out)
        Files.createDirectories(out)
        val nativeExt = Regex("\\.(so|dll|dylib|jnilib)$")
        for (rl in rv.libraries.filter { it.isNative }) {
            if (!Files.isRegularFile(rl.file)) continue
            val excludes = rl.library.extractExclude.ifEmpty { listOf("META-INF/") }
            SafeZip.extract(rl.file, out, filter = { name ->
                excludes.none { name.startsWith(it) } && (rl.library.natives.isNotEmpty() || nativeExt.containsMatchIn(name))
            })
        }
        // Flatten modern natives (e.g. linux/x64/org/lwjgl/liblwjgl.so) so java.library.path finds them.
        Files.walk(out).use { s -> s.filter { Files.isRegularFile(it) && it.parent != out && nativeExt.containsMatchIn(it.fileName.toString()) }.collect(java.util.stream.Collectors.toList()) }
            .forEach { Files.move(it, out.resolve(it.fileName.toString()), StandardCopyOption.REPLACE_EXISTING) }
    }
}
