package pro.cslauncher.core.e2e

import kotlinx.coroutines.*
import pro.cslauncher.core.account.AccountRepository
import pro.cslauncher.core.account.InMemorySecretStore
import pro.cslauncher.core.download.DownloadManager
import pro.cslauncher.core.install.*
import pro.cslauncher.core.launch.*
import pro.cslauncher.core.meta.VersionManifestRepository
import pro.cslauncher.core.net.UrlConnectionTransport
import pro.cslauncher.core.perf.DeviceFacts
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.profile.Profile
import pro.cslauncher.core.renderer.RendererEnvironment
import pro.cslauncher.core.renderer.RendererRegistry
import pro.cslauncher.core.runtime.RuntimeManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Host end-to-end validation of the launcher core against the real Mojang services:
 * manifest → install → idempotent re-install → full SHA-1 verification → corruption + targeted repair →
 * launch preparation (state machine, runtime/renderer selection) → real game process until the title screen.
 *
 * Usage: HostE2E <version> <launcherRoot> <evidenceDir> [launch|install-only] [heapMb] [settleSeconds]
 * The game needs a display: run under Xvfb (DISPLAY must be set). Offline account is used for a local, single-player
 * smoke test only.
 */
fun main(args: Array<String>) = runBlocking {
    val version = args.getOrElse(0) { "1.18.2" }
    val root = Paths.get(args.getOrElse(1) { System.getProperty("user.home") + "/.cache/cs-e2e" }).toAbsolutePath()
    val evidence = Paths.get(args.getOrElse(2) { "docs/evidence" }).toAbsolutePath().also { Files.createDirectories(it) }
    val doLaunch = args.getOrElse(3) { "launch" } == "launch"
    val heap = args.getOrElse(4) { "768" }.toInt()
    val settleSec = args.getOrElse(5) { "40" }.toLong()
    val log = StringBuilder()
    fun out(s: String) { val line = "[${Instant.now()}] $s"; println(line); log.appendLine(line) }
    fun saveReport(name: String) = Files.writeString(evidence.resolve(name), log.toString())

    val platform = PlatformInfo.host()
    out("Host: ${platform.osName} ${platform.arch} ${platform.osVersion}; JVM ${System.getProperty("java.version")}; root=$root")
    val dirs = LauncherDirs(root)
    val http = UrlConnectionTransport()
    val dm = DownloadManager(http)
    val installer = Installer(dirs, http, dm, platform)

    // 1. Manifest
    val manifest = VersionManifestRepository(http, dirs.cache.resolve("version_manifest_v2.json")).load()
    out("Manifest: ${manifest.versions.size} versions, latest release ${manifest.latestRelease}, fromCache=${manifest.fromCache}")
    check(manifest.versions.any { it.id == version }) { "$version not in manifest" }

    // 2. Install
    var lastPrint = 0L
    val t0 = System.currentTimeMillis()
    val report = installer.install(version, manifest) { ev ->
        val now = System.currentTimeMillis()
        val p = ev.progress
        if (p == null) out("[${ev.stage}] ${ev.message}")
        else if (now - lastPrint > 5000 || p.finishedFiles == p.totalFiles) {
            lastPrint = now
            out("[${ev.stage}] ${p.batchName}: ${p.finishedFiles}/${p.totalFiles} files, ${p.downloadedBytes / 1048576} MB, failed ${p.failedFiles}")
        }
    }
    out("Install finished in ${(System.currentTimeMillis() - t0) / 1000}s: ${report.summary()}")
    check(report.complete) { "Install incomplete: ${report.issues.take(5)}" }

    // 3. Idempotent re-install: everything must be skipped quickly.
    val t1 = System.currentTimeMillis()
    val again = installer.install(version, manifest)
    out("Re-install (should skip all files) in ${(System.currentTimeMillis() - t1) / 1000}s: ${again.summary()}")

    // 4. Full hash verification, then corruption + targeted repair.
    val rv = installer.resolve(version, manifest)
    val verifier = Verifier(dirs, installer, platform)
    val full = verifier.verify(rv, fullHash = true)
    out("Full SHA-1 verification: ${full.summary()}")
    check(full.complete)
    val victim = rv.libraries.first { !it.isNative }.file
    val original = Files.readAllBytes(victim)
    Files.write(victim, original.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
    val broken = verifier.verify(rv, fullHash = true)
    out("After corrupting ${victim.fileName}: ${broken.issues.size} issue(s): ${broken.issues.joinToString { "${it.name} (${it.problem})" }}")
    check(broken.issues.size == 1 && broken.repairTasks.size == 1)
    val repair = dm.downloadAll("repair", broken.repairTasks)
    val fixed = verifier.verify(rv, fullHash = true)
    out("Targeted repair: ${repair.summary()}; re-verify: ${fixed.summary()}")
    check(fixed.complete && original.contentEquals(Files.readAllBytes(victim)))
    saveReport("host-e2e-$version-install.log")
    if (!doLaunch) return@runBlocking

    // 5. Launch preparation through the same coordinator the app uses.
    val accounts = AccountRepository(dirs.config.resolve("accounts.json"), InMemorySecretStore())
    val acc = accounts.addOffline("CSHostTest")
    val profile = Profile(name = "Host E2E $version", versionId = version, memoryMb = heap)
    val ramMb = (Files.readAllLines(Paths.get("/proc/meminfo")).first { it.startsWith("MemTotal") }.filter { it.isDigit() }.toLong() / 1024).toInt()
    val coordinator = LaunchCoordinator(dirs, platform, installer, RuntimeManager(dirs, platform, listOf(Paths.get(System.getProperty("java.home")))), RendererRegistry(), null)
    val sm = LaunchStateMachine()
    val transitions = mutableListOf<String>()
    val watchStates = launch { sm.status.collect { transitions += it.state.name + (it.failedAt?.let { f -> "(failedAt=$f)" } ?: "") } }
    val prepared = try {
        coordinator.prepare(LaunchRequest(profile, { accounts.session(acc, "") },
            { r -> RendererEnvironment(platform, 0, 0, null, emptyMap(), r.meta.id, r.meta.usesLwjgl3, false) },
            DeviceFacts(ramMb, platform.is64Bit, 0, Runtime.getRuntime().availableProcessors(), false), 854, 480), sm)
    } catch (e: LaunchBlockedException) { e.issues.forEach { out("BLOCKED: ${it.stage} ${it.message} -> ${it.fix}") }; saveReport("host-e2e-$version-launch.log"); throw e }
    out("Prepared: Java ${prepared.runtime.runtime?.major} (${prepared.runtime.reason}), renderer ${prepared.renderer.backend?.id} (${prepared.renderer.reason})")
    out("Command line (redacted): ${prepared.plan.redactedCommandLine().take(4000)}")
    prepared.warnings.forEach { out("Warning: ${it.message}") }

    // 6. Run the real game until it reaches the title screen, capture a screenshot, then stop it.
    val game = DesktopGameProcess(prepared.plan, prepared.accessToken, sm)
    val readyAtRef = java.util.concurrent.atomic.AtomicLong(0)
    val shot = evidence.resolve("host-e2e-$version-title.png")
    val watcher = launch(Dispatchers.IO) {
        while (readyAtRef.get() == 0L) delay(500)
        out("READY milestone reached; waiting ${settleSec}s for the title screen to render (software GL)")
        delay(settleSec * 1000)
        val display = System.getenv("DISPLAY")
        if (display != null) {
            val p = ProcessBuilder("import", "-display", display, "-window", "root", shot.toString()).redirectErrorStream(true).start()
            p.waitFor(30, TimeUnit.SECONDS)
            out("Screenshot: ${if (Files.isRegularFile(shot)) "$shot (${Files.size(shot)} bytes)" else "failed: " + p.inputStream.bufferedReader().readText()}")
        }
        game.stop()
    }
    val gameStart = System.currentTimeMillis()
    val result = game.run(timeoutMs = 15 * 60_000L, onLine = { line ->
        if (line.contains("Created: ") || line.contains("Setting user") || line.contains("LWJGL") || line.contains("OpenGL") || line.contains("ERROR") || line.contains("Exception"))
            out("[game] ${line.take(300)}")
        if (readyAtRef.get() == 0L && line.contains(Regex("Sound engine started|Created: \\d+x\\d+x?\\d* minecraft:textures/atlas/blocks\\.png-atlas"))) readyAtRef.set(System.currentTimeMillis())
    })
    watcher.cancel()
    delay(200); watchStates.cancel()
    out("Game process ended: exit=${result.exitCode}, stoppedByLauncher=${result.stoppedByLauncher}, milestones=${result.milestones}, " +
        "crash=${result.crashLine}, ${result.durationMs / 1000}s, time-to-READY=${readyAtRef.get().let { if (it > 0) "${(it - gameStart) / 1000}s" else "n/a" }}")
    out("State transitions: ${transitions.joinToString(" -> ")}")
    out("Game log: ${result.logFile}")
    Files.copy(result.logFile, evidence.resolve("host-e2e-$version-game.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    saveReport("host-e2e-$version-launch.log")
    val ok = Milestone.READY in result.milestones && Milestone.CRASH_DETECTED !in result.milestones && sm.state == LaunchState.EXITED
    out(if (ok) "HOST E2E PASSED" else "HOST E2E FAILED")
    saveReport("host-e2e-$version-launch.log")
    if (!ok) kotlin.system.exitProcess(1)
}
