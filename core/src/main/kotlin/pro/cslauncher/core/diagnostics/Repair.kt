package pro.cslauncher.core.diagnostics

import pro.cslauncher.core.download.DownloadManager
import pro.cslauncher.core.install.*
import pro.cslauncher.core.meta.VersionManifest
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.util.*
import java.nio.file.Files
import java.time.Instant

data class RepairPlan(val action: RepairAction, val affectedPaths: List<String>, val requiresConfirmation: Boolean, val explanation: String)
data class RepairResult(val action: RepairAction, val success: Boolean, val changed: List<String>, val verification: String)

/** Executes only safe, verifiable repairs and journals every change. Destructive actions are refused here. */
class RepairEngine(private val dirs: LauncherDirs, private val installer: Installer, private val downloads: DownloadManager, private val platform: PlatformInfo) {

    suspend fun plan(action: RepairAction, versionId: String): RepairPlan = when (action) {
        is RepairAction.RedownloadFiles -> {
            val rv = installer.resolve(versionId, null)
            val report = Verifier(dirs, installer, platform).verify(rv, fullHash = true)
            RepairPlan(action, report.repairTasks.map { it.destination.toString() }, false,
                if (report.complete) "All files verified; nothing to download." else "${report.repairTasks.size} file(s) will be re-downloaded: ${report.issues.take(5).joinToString { it.name }}")
        }
        RepairAction.RebuildNatives -> RepairPlan(action, listOf(dirs.nativesDir(versionId).toString()), false, "The launcher-owned natives folder for $versionId is regenerated from verified libraries.")
        RepairAction.RemoveTempFiles -> RepairPlan(action, launcherOwned().map { "$it/**/*.part" }, false, "Only unfinished download files ending in .part inside launcher-managed folders are deleted.")
        else -> RepairPlan(action, emptyList(), action.destructive, action.description)
    }

    suspend fun execute(action: RepairAction, versionId: String, manifest: VersionManifest?): RepairResult {
        require(!action.destructive) { "Destructive repairs must be performed explicitly by the user" }
        val result = when (action) {
            is RepairAction.RedownloadFiles -> {
                val rv = installer.resolve(versionId, manifest)
                val before = Verifier(dirs, installer, platform).verify(rv, fullHash = true)
                if (before.repairTasks.isNotEmpty()) downloads.downloadAll("repair $versionId", before.repairTasks)
                if (!platform.isAndroid) installer.extractNatives(rv)
                val after = Verifier(dirs, installer, platform).verify(rv, fullHash = true)
                RepairResult(action, after.complete, before.repairTasks.map { it.destination.toString() }, after.summary())
            }
            RepairAction.RebuildNatives -> {
                val rv = installer.resolve(versionId, null)
                installer.extractNatives(rv)
                val ok = platform.isAndroid || Files.isDirectory(dirs.nativesDir(versionId))
                RepairResult(action, ok, listOf(dirs.nativesDir(versionId).toString()), if (ok) "Natives rebuilt" else "Natives directory missing after rebuild")
            }
            RepairAction.RemoveTempFiles -> {
                val n = launcherOwned().sumOf { downloads.cleanPartialFiles(it) }
                RepairResult(action, true, listOf("$n partial file(s)"), "Removed $n partial download(s)")
            }
            else -> RepairResult(action, false, emptyList(), "This is guidance only: ${action.description}")
        }
        journal(versionId, result)
        return result
    }

    /** Launcher-managed download locations. Game directories (user content) are never scanned. */
    private fun launcherOwned() = listOf(dirs.versions, dirs.libraries, dirs.assets, dirs.cache, dirs.runtimeRoot, dirs.componentRoot)

    private fun journal(versionId: String, r: RepairResult) {
        val f = dirs.config.resolve("repair-journal.log")
        Files.createDirectories(f.parent)
        pro.cslauncher.core.util.FsUtil.writeText(f, "${Instant.now()} [$versionId] ${r.action.description} success=${r.success} changed=${r.changed.size} :: ${r.verification}\n",
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }
}
