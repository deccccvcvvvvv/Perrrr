package pro.cslauncher.core.install

import pro.cslauncher.core.download.DownloadTask
import pro.cslauncher.core.meta.AssetIndex
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.util.Integrity
import java.nio.file.Files

enum class Component { METADATA, CLIENT, LIBRARY, ASSET_INDEX, ASSET, LOGGING_CONFIG, NATIVES, RUNTIME, RENDERER, GAME_DIRECTORY }

data class InstallIssue(val component: Component, val name: String, val problem: String, val repair: DownloadTask?)

data class InstallReport(val versionId: String, val checkedFiles: Int, val issues: List<InstallIssue>, val notes: List<String>) {
    val complete get() = issues.isEmpty()
    val repairable get() = issues.isNotEmpty() && issues.all { it.repair != null }
    val repairTasks get() = issues.mapNotNull { it.repair }
    fun summary() = if (complete) "Installation of $versionId verified ($checkedFiles files)"
                    else "${issues.size} problem(s) in $versionId: " + issues.groupBy { it.component }.entries.joinToString { "${it.value.size} ${it.key.name.lowercase()}" }
}

/** Verifies an installation and produces targeted repair tasks (only the broken files are re-downloaded). */
class Verifier(private val dirs: LauncherDirs, private val installer: Installer, private val platform: PlatformInfo) {
    fun verify(rv: ResolvedVersion, fullHash: Boolean): InstallReport {
        val issues = mutableListOf<InstallIssue>(); val notes = mutableListOf<String>()
        var checked = 0
        fun check(c: Component, t: DownloadTask?, name: String, integrity: Integrity, file: java.nio.file.Path) {
            checked++
            val p = integrity.check(file, fullHash)
            if (p != null) issues += InstallIssue(c, name, p, t)
        }
        for (id in rv.chain) if (!Files.isRegularFile(dirs.versionJson(id))) issues += InstallIssue(Component.METADATA, id, "metadata missing", null)
        installer.clientTask(rv)?.let { check(Component.CLIENT, it, it.label, it.integrity, it.destination) }
            ?: issues.add(InstallIssue(Component.CLIENT, rv.meta.id, "no client download in metadata", null))
        installer.loggingTask(rv)?.let { check(Component.LOGGING_CONFIG, it, it.label, it.integrity, it.destination) }
        for (rl in rv.libraries) {
            val d = rl.download
            val t = d?.takeIf { it.url.isNotBlank() }?.let { DownloadTask(it.url, rl.file, it.integrity, rl.library.name) }
            check(Component.LIBRARY, t, rl.library.name, d?.integrity ?: Integrity(), rl.file)
        }
        val idx = installer.assetIndexTask(rv)
        if (idx != null) {
            check(Component.ASSET_INDEX, idx, idx.label, idx.integrity, idx.destination)
            if (Files.isRegularFile(idx.destination)) runCatching { AssetIndex.parse(pro.cslauncher.core.util.FsUtil.readText(idx.destination)) }.onSuccess { index ->
                // Asset objects are content addressed; size check always, full hash when requested.
                for (t in installer.assetTasks(index)) check(Component.ASSET, t, t.label, t.integrity, t.destination)
            }.onFailure { issues += InstallIssue(Component.ASSET_INDEX, idx.label, "unreadable: ${it.message}", idx) }
        } else notes += "Version has no asset index"
        if (platform.isAndroid) {
            if (rv.desktopNativesReplaced.isNotEmpty()) notes += "${rv.desktopNativesReplaced.size} desktop native artifacts are replaced by the Android native component"
        } else if (rv.libraries.any { it.isNative }) {
            val nd = dirs.nativesDir(rv.meta.id)
            if (!Files.isDirectory(nd) || Files.list(nd).use { it.count() } == 0L) issues += InstallIssue(Component.NATIVES, rv.meta.id, "natives not extracted", null)
        }
        return InstallReport(rv.meta.id, checked, issues, notes)
    }
}
