package pro.cslauncher.core.launch

import kotlinx.coroutines.*
import pro.cslauncher.core.util.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

data class GameExitResult(val exitCode: Int?, val milestones: Set<Milestone>, val crashLine: String?, val logFile: Path, val durationMs: Long, val stoppedByLauncher: Boolean)

/**
 * Runs a [LaunchPlan] as a child process on desktop hosts (used for host validation of the launcher core).
 * Android uses the in-process JNI path in the app module instead, because executing binaries from app storage is blocked.
 */
class DesktopGameProcess(private val plan: LaunchPlan, private val accessToken: String, private val sm: LaunchStateMachine) {
    @Volatile private var process: Process? = null
    @Volatile private var stopRequested = false

    suspend fun run(stopWhen: (Set<Milestone>) -> Boolean = { false }, timeoutMs: Long = Long.MAX_VALUE, onLine: (String) -> Unit = {}): GameExitResult = withContext(Dispatchers.IO) {
        val p = plan.withSecret(accessToken)
        val cmd = listOf(p.javaExecutable) + p.jvmArgs + p.mainClass + p.gameArgs
        val pb = ProcessBuilder(cmd).directory(Paths.get(p.workingDir).toFile()).redirectErrorStream(true)
        pb.environment().putAll(p.env)
        val log = Paths.get(p.logFile); Files.createDirectories(log.parent)
        val xml = Log4jXml()
        sm.advance(LaunchState.INITIALIZING_GAME)
        val start = System.currentTimeMillis()
        var stopped = false
        stopRequested = false
        val monitor = StartupMonitor { m, _ ->
            when (m) {
                Milestone.MAIN_ENTERED -> if (sm.state == LaunchState.INITIALIZING_GAME) sm.advance(LaunchState.LOADING_GAME)
                Milestone.READY -> if (sm.state == LaunchState.LOADING_GAME || sm.state == LaunchState.INITIALIZING_GAME) sm.advance(LaunchState.RUNNING)
                else -> {}
            }
        }
        val proc = try { pb.start() } catch (e: Exception) { sm.fail("Could not start Java: ${e.message}"); throw e }
        process = proc
        monitor.markJvmStarted()
        Files.newBufferedWriter(log).use { w ->
            val reader = proc.inputStream.bufferedReader()
            val readJob = launch {
                while (isActive) {
                    val raw = reader.readLine() ?: break
                    for (line in xml.feed(raw)) {
                        val safe = Redactor.redact(line)
                        w.write(safe); w.newLine()
                        monitor.onLine(safe); onLine(safe)
                        if (stopWhen(monitor.milestones) && !stopped) { stopped = true; proc.destroy() }
                    }
                }
            }
            val deadline = start + timeoutMs
            while (proc.isAlive && System.currentTimeMillis() < deadline) { if (stopRequested) { stopped = true; proc.destroy() }; delay(200) }
            if (stopRequested) stopped = true
            if (proc.isAlive) { stopped = true; proc.destroy(); if (!proc.waitFor(10, TimeUnit.SECONDS)) proc.destroyForcibly() }
            readJob.join()
        }
        val code = runCatching { proc.exitValue() }.getOrNull()
        val clean = stopped || code == 0
        if (clean && Milestone.CRASH_DETECTED !in monitor.milestones) { if (sm.state != LaunchState.FAILED) sm.advance(LaunchState.EXITED) }
        else sm.fail("Game exited with code $code at ${sm.state}: ${monitor.crashLine ?: "see log"}")
        GameExitResult(code, monitor.milestones, monitor.crashLine, log, System.currentTimeMillis() - start, stopped)
    }

    /** Stops the game at the user's (or test harness') request; recorded as a clean, launcher-initiated exit. */
    fun stop() { stopRequested = true; process?.destroy() }
}
