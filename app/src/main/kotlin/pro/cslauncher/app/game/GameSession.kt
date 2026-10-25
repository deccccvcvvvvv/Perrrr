package pro.cslauncher.app.game

import pro.cslauncher.core.launch.*
import pro.cslauncher.core.util.CsJson
import pro.cslauncher.core.util.FsUtil
import java.io.File

/**
 * Tracks one game run inside the ":game" process and persists a [SessionRecord] (no secrets) that the launcher
 * process reads to update the profile's last-run status, the activity feed and crash diagnostics.
 */
class GameSession(private val plan: LaunchPlan, private val fingerprint: String?) {
    private val file = File(plan.sessionDir, "session.json")
    private val startedAt = System.currentTimeMillis()
    @Volatile var state: LaunchState = LaunchState.INITIALIZING_GAME; private set
    @Volatile private var exited = false
    val monitor = StartupMonitor { _, _ -> onProgress() }

    init { write(null, null) }

    private fun onProgress() {
        val s = monitor.stateFor()
        if (s != state) { state = s; write(null, null) }
    }

    fun onLine(line: String) = monitor.onLine(line)

    @Synchronized fun failed(message: String) { if (exited) return; exited = true; state = LaunchState.FAILED; write(null, message, failedAt = LaunchState.INITIALIZING_GAME) }

    /** Called from the exiting VM thread; must finish synchronously because the process ends right after. */
    @Synchronized fun exited(code: Int) {
        if (exited) return; exited = true
        val crashed = Milestone.CRASH_DETECTED in monitor.milestones || (code != 0 && Milestone.READY !in monitor.milestones)
        val failedAt = if (code != 0 || crashed) monitor.stateFor() else null
        state = if (failedAt != null) LaunchState.FAILED else LaunchState.EXITED
        write(code, if (failedAt != null) "Game exited with code $code" else null, failedAt)
    }

    private fun write(exitCode: Int?, message: String?, failedAt: LaunchState? = null) {
        val rec = SessionRecord(plan.profileId, plan.versionId, plan.rendererId, state, failedAt, monitor.milestones.toList(), exitCode, message,
            monitor.crashLine?.take(300), startedAt, if (exitCode != null || state == LaunchState.FAILED) System.currentTimeMillis() else null, fingerprint)
        runCatching { FsUtil.writeAtomic(file.toPath(), CsJson.encodeToString(SessionRecord.serializer(), rec)) }
    }
}
