package pro.cslauncher.core.install

import java.nio.file.Files
import java.nio.file.Path

/**
 * Directory layout. Shared, content-addressed data (libraries, assets, versions) is separated from per-profile
 * game directories. On Android [nativeRoot] and [runtimeRoot] MUST live in internal storage (dlopen is not
 * permitted from external storage), while game data can live in app-specific external storage.
 */
class LauncherDirs(
    val root: Path,
    val nativeRoot: Path = root.resolve("natives"),
    val runtimeRoot: Path = root.resolve("runtimes"),
    val componentRoot: Path = root.resolve("components"),
) {
    val versions: Path = root.resolve("versions")
    val libraries: Path = root.resolve("libraries")
    val assets: Path = root.resolve("assets")
    val assetIndexes: Path = assets.resolve("indexes")
    val assetObjects: Path = assets.resolve("objects")
    val logConfigs: Path = assets.resolve("log_configs")
    val instances: Path = root.resolve("instances")
    val sharedGameDir: Path = root.resolve("game")
    val config: Path = root.resolve("launcher-config")
    val cache: Path = root.resolve("cache")
    val backups: Path = root.resolve("backups")
    val sessions: Path = root.resolve("sessions")
    val recordings: Path = root.resolve("recordings")

    fun versionDir(id: String): Path = versions.resolve(checkId(id))
    fun versionJson(id: String): Path = versionDir(id).resolve("$id.json")
    fun versionJar(id: String): Path = versionDir(id).resolve("$id.jar")
    fun nativesDir(versionId: String): Path = nativeRoot.resolve(checkId(versionId))
    fun assetIndexFile(indexId: String): Path = assetIndexes.resolve("${checkId(indexId)}.json")
    fun libraryFile(relPath: String): Path {
        val p = libraries.resolve(relPath).normalize()
        require(p.startsWith(libraries.normalize()) && !relPath.contains("..")) { "Unsafe library path $relPath" }
        return p
    }

    fun createBase() = listOf(versions, libraries, assetIndexes, assetObjects, logConfigs, instances, sharedGameDir, config, cache,
        backups, sessions, recordings, nativeRoot, runtimeRoot, componentRoot).forEach { Files.createDirectories(it) }

    companion object {
        private val ID = Regex("^[A-Za-z0-9._+ -]{1,96}$")
        fun checkId(id: String): String { require(ID.matches(id) && id != "." && id != ".." && !id.contains("..")) { "Invalid identifier '$id'" }; return id }
        /** Standard game sub-directories created for each game directory. */
        val GAME_SUBDIRS = listOf("mods", "resourcepacks", "shaderpacks", "saves", "screenshots", "logs", "crash-reports", "config")
    }
}
