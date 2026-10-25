package pro.cslauncher.core.storage

import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.util.FsUtil
import java.nio.file.Files
import java.nio.file.Path

enum class StorageCategory(val title: String, val regenerable: Boolean) {
    INSTALLATIONS("Game versions", true), LIBRARIES("Libraries", true), ASSETS("Assets", true), NATIVES("Extracted natives", true),
    RUNTIMES("Java runtimes", true), COMPONENTS("Native components", true), CACHE("Download cache & temp", true), SESSIONS("Launch logs", true),
    MODS("Mods", false), RESOURCE_PACKS("Resource packs", false), SHADER_PACKS("Shader packs", false), WORLDS("Worlds", false),
    SCREENSHOTS("Screenshots", false), RECORDINGS("Recordings", false), BACKUPS("Backups", false)
}
data class StorageEntry(val category: StorageCategory, val bytes: Long, val paths: List<Path>)

/** Size accounting with a strict split between regenerable launcher data and user content. */
class StorageAnalyzer(private val dirs: LauncherDirs) {
    fun analyze(gameDirs: List<Path>): List<StorageEntry> {
        fun e(c: StorageCategory, vararg p: Path) = StorageEntry(c, p.sumOf { FsUtil.sizeOf(it) }, p.toList())
        fun game(sub: String) = gameDirs.distinct().map { it.resolve(sub) }.toTypedArray()
        return listOf(
            e(StorageCategory.INSTALLATIONS, dirs.versions), e(StorageCategory.LIBRARIES, dirs.libraries), e(StorageCategory.ASSETS, dirs.assets),
            e(StorageCategory.NATIVES, dirs.nativeRoot), e(StorageCategory.RUNTIMES, dirs.runtimeRoot), e(StorageCategory.COMPONENTS, dirs.componentRoot),
            e(StorageCategory.CACHE, dirs.cache), e(StorageCategory.SESSIONS, dirs.sessions),
            e(StorageCategory.MODS, *game("mods")), e(StorageCategory.RESOURCE_PACKS, *game("resourcepacks")), e(StorageCategory.SHADER_PACKS, *game("shaderpacks")),
            e(StorageCategory.WORLDS, *game("saves")), e(StorageCategory.SCREENSHOTS, *game("screenshots")), e(StorageCategory.RECORDINGS, dirs.recordings),
            e(StorageCategory.BACKUPS, dirs.backups),
        )
    }

    /** Clears only regenerable, launcher-owned data. User categories are rejected. */
    fun clear(category: StorageCategory, keepSessions: Int = 5): Long {
        require(category.regenerable) { "${category.title} contains user content and cannot be cleared automatically" }
        val before: Long
        when (category) {
            StorageCategory.CACHE -> { before = FsUtil.sizeOf(dirs.cache); childrenOf(dirs.cache).forEach { FsUtil.deleteTreeWithin(dirs.cache, it) } }
            StorageCategory.NATIVES -> { before = FsUtil.sizeOf(dirs.nativeRoot); childrenOf(dirs.nativeRoot).forEach { FsUtil.deleteTreeWithin(dirs.nativeRoot, it) } }
            StorageCategory.SESSIONS -> { before = FsUtil.sizeOf(dirs.sessions); childrenOf(dirs.sessions).sortedByDescending { it.fileName.toString() }.drop(keepSessions).forEach { FsUtil.deleteTreeWithin(dirs.sessions, it) } }
            else -> throw IllegalArgumentException("${category.title} is removed per item (e.g. uninstall a version or runtime)")
        }
        return before - FsUtil.sizeOf(when (category) { StorageCategory.CACHE -> dirs.cache; StorageCategory.NATIVES -> dirs.nativeRoot; else -> dirs.sessions })
    }

    private fun childrenOf(p: Path): List<Path> = if (!Files.isDirectory(p)) emptyList() else Files.list(p).use { it.collect(java.util.stream.Collectors.toList()) }
}
