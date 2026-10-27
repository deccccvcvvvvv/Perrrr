package pro.cslauncher.app.install

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import pro.cslauncher.app.Services
import pro.cslauncher.core.activity.ActivityItem
import pro.cslauncher.core.activity.ActivityKind
import pro.cslauncher.core.install.InstallStage
import pro.cslauncher.core.install.Verifier

enum class JobStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }
enum class JobKind { INSTALL, VERIFY_REPAIR, TASK }

data class InstallJob(
    val versionId: String, val kind: JobKind, val status: JobStatus = JobStatus.QUEUED, val stage: String = "Queued",
    val message: String = "", val finishedFiles: Int = 0, val totalFiles: Int = 0, val bytes: Long = 0, val failedFiles: Int = 0,
    val startedAtMs: Long = System.currentTimeMillis(), val endedAtMs: Long? = null,
    /** Display title; versionId doubles as the job key (a version id, or "task:<name>" for TASK jobs). */
    val title: String = versionId,
) {
    val fraction: Float? get() = if (totalFiles > 0) finishedFiles.toFloat() / totalFiles else null
    val active get() = status == JobStatus.QUEUED || status == JobStatus.RUNNING
}

/**
 * Runs installations/repairs in the launcher process. While any job is active a dataSync foreground service keeps the
 * process alive and shows progress; jobs survive leaving the screen and are cancellable.
 */
object InstallManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _jobs = MutableStateFlow<Map<String, InstallJob>>(emptyMap())
    val jobs: StateFlow<Map<String, InstallJob>> = _jobs
    private val running = HashMap<String, Job>()
    private val queue = kotlinx.coroutines.sync.Semaphore(1)

    private fun set(v: String, f: (InstallJob) -> InstallJob) = _jobs.update { m -> m[v]?.let { m + (v to f(it)) } ?: m }

    @Synchronized fun start(ctx: Context, versionId: String, kind: JobKind = JobKind.INSTALL, onFinished: (Boolean) -> Unit = {}) {
        if (running[versionId]?.isActive == true) return
        _jobs.update { it + (versionId to InstallJob(versionId, kind)) }
        ContextCompat.startForegroundService(ctx, Intent(ctx, InstallService::class.java))
        running[versionId] = scope.launch {
            var ok = false
            var acquired = false
            try {
                queue.acquire(); acquired = true

                set(versionId) { it.copy(status = JobStatus.RUNNING, stage = "Reading version list") }
                val manifest = runCatching { Services.manifests.load() }.getOrNull()
                if (kind == JobKind.INSTALL) {
                    val report = Services.installer.install(versionId, manifest) { ev ->
                        set(versionId) { j ->
                            val p = ev.progress
                            j.copy(stage = ev.stage.title, message = ev.message, finishedFiles = p?.finishedFiles ?: j.finishedFiles,
                                totalFiles = p?.totalFiles ?: j.totalFiles, bytes = p?.downloadedBytes ?: j.bytes, failedFiles = p?.failedFiles ?: j.failedFiles)
                        }
                    }
                    ok = report.complete
                    set(versionId) { it.copy(message = report.summary()) }
                } else {
                    set(versionId) { it.copy(stage = InstallStage.values().last().title, message = "Checking every file (SHA-1)") }
                    val rv = Services.installer.resolve(versionId, manifest)
                    val verifier = Verifier(Services.dirs, Services.installer, Services.platform)
                    val report = verifier.verify(rv, fullHash = true)
                    if (report.repairTasks.isNotEmpty()) {
                        set(versionId) { it.copy(stage = "Repairing", message = "Re-downloading ${report.repairTasks.size} file(s)", totalFiles = report.repairTasks.size) }
                        Services.downloads.downloadAll("repair-$versionId", report.repairTasks)
                    }
                    val after = verifier.verify(rv, fullHash = true)
                    ok = after.complete
                    set(versionId) { it.copy(message = if (report.issues.isEmpty()) "All ${after.checkedFiles} files verified" else "${report.issues.size} issue(s) found; ${after.summary()}") }
                    if (ok && report.issues.isNotEmpty()) Services.activity.add(ActivityItem(ActivityKind.REPAIRED, "Repaired $versionId", "${report.issues.size} file(s) restored"), Services.settings.state.value.recentActivityEnabled)
                }
                set(versionId) { it.copy(status = if (ok) JobStatus.DONE else JobStatus.FAILED, endedAtMs = System.currentTimeMillis()) }
                if (ok && kind == JobKind.INSTALL) Services.activity.add(ActivityItem(ActivityKind.INSTALLED, "Installed $versionId", "Verified and ready"), Services.settings.state.value.recentActivityEnabled)
                if (!ok) Services.activity.add(ActivityItem(ActivityKind.DOWNLOAD_FAILED, "Install of $versionId incomplete", "Open Downloads to retry"), Services.settings.state.value.recentActivityEnabled)
            } catch (e: CancellationException) {
                set(versionId) { it.copy(status = JobStatus.CANCELLED, message = "Cancelled. Downloaded files are kept and resumed next time.", endedAtMs = System.currentTimeMillis()) }
            } catch (e: Exception) {
                set(versionId) { it.copy(status = JobStatus.FAILED, message = e.message ?: e.toString(), endedAtMs = System.currentTimeMillis()) }
            } finally {
                if (acquired) queue.release()
                synchronized(this@InstallManager) { running.remove(versionId) }
                withContext(NonCancellable + Dispatchers.Main) { onFinished(ok) }
            }
        }
    }

    /** Progress reporter handed to [startTask] work. */
    class TaskScope internal constructor(private val key: String) {
        fun progress(stage: String, message: String = "", finished: Int? = null, total: Int? = null, bytes: Long? = null) =
            set(key) { j -> j.copy(stage = stage, message = message, finishedFiles = finished ?: j.finishedFiles, totalFiles = total ?: j.totalFiles, bytes = bytes ?: j.bytes) }
        fun progress(stage: String, p: pro.cslauncher.core.download.BatchProgress) =
            progress(stage, p.currentFile ?: "", p.finishedFiles, p.totalFiles, p.downloadedBytes)

        /** Installs (or completes/verifies) a version through the normal installer and writes the installed marker. */
        suspend fun installVersion(versionId: String, manifest: pro.cslauncher.core.meta.VersionManifest?) {
            val report = Services.installer.install(versionId, manifest) { ev ->
                val p = ev.progress
                set(key) { j -> j.copy(stage = "${ev.stage.title} · $versionId", message = ev.message, finishedFiles = p?.finishedFiles ?: j.finishedFiles,
                    totalFiles = p?.totalFiles ?: j.totalFiles, bytes = p?.downloadedBytes ?: j.bytes, failedFiles = p?.failedFiles ?: j.failedFiles) }
            }
            if (!report.complete) throw java.io.IOException("Installing $versionId is incomplete: ${report.summary()}")
            markInstalled(versionId)
        }
    }

    /** Same marker the launcher view model uses to show a version as installed. */
    fun markInstalled(versionId: String) {
        val dir = Services.dirs.versionDir(versionId)
        java.nio.file.Files.createDirectories(dir)
        dir.resolve(".cs-installed").toFile().writeText(System.currentTimeMillis().toString())
        java.nio.file.Files.deleteIfExists(dir.resolve(".cs-needs-repair"))
    }

    /**
     * Runs arbitrary long work (modpack or OptiFine installation) under the same foreground service, progress and
     * cancellation as installs. [work] returns true on success; exceptions are reported as the failure message.
     */
    @Synchronized fun startTask(ctx: Context, name: String, title: String, onFinished: (Boolean) -> Unit = {}, work: suspend TaskScope.() -> Boolean): Boolean {
        val key = "task:$name"
        if (running[key]?.isActive == true) return false
        _jobs.update { it + (key to InstallJob(key, JobKind.TASK, title = title)) }
        ContextCompat.startForegroundService(ctx, Intent(ctx, InstallService::class.java))
        running[key] = scope.launch {
            var ok = false
            var acquired = false
            try {
                queue.acquire(); acquired = true

                set(key) { it.copy(status = JobStatus.RUNNING, stage = "Starting") }
                ok = TaskScope(key).work()
                set(key) { it.copy(status = if (ok) JobStatus.DONE else JobStatus.FAILED, endedAtMs = System.currentTimeMillis(), stage = if (ok) "Done" else it.stage) }
            } catch (e: CancellationException) {
                set(key) { it.copy(status = JobStatus.CANCELLED, message = "Cancelled", endedAtMs = System.currentTimeMillis()) }
            } catch (e: Exception) {
                pro.cslauncher.core.util.CsLog.w("Task", "$title failed: ${e.message}")
                set(key) { it.copy(status = JobStatus.FAILED, message = e.message ?: e.toString(), endedAtMs = System.currentTimeMillis()) }
            } finally {
                if (acquired) queue.release()
                synchronized(this@InstallManager) { running.remove(key) }
                withContext(NonCancellable + Dispatchers.Main) { onFinished(ok) }
            }
        }
        return true
    }

    @Synchronized fun cancel(versionId: String) { running[versionId]?.cancel() }
    @Synchronized fun anyActive() = running.values.any { it.isActive }
    fun clearFinished() = _jobs.update { m -> m.filterValues { it.active } }
}
