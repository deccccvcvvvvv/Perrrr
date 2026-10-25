package pro.cslauncher.core.launch

import pro.cslauncher.core.component.LwjglCompat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import pro.cslauncher.core.account.AuthException
import pro.cslauncher.core.account.LaunchSession
import pro.cslauncher.core.component.ComponentManager
import pro.cslauncher.core.install.*
import pro.cslauncher.core.perf.DeviceFacts
import pro.cslauncher.core.perf.PerformanceAdvisor
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.profile.Profile
import pro.cslauncher.core.profile.ProfileValidator
import pro.cslauncher.core.renderer.*
import pro.cslauncher.core.runtime.*
import pro.cslauncher.core.util.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class FixAction { NONE, INSTALL_VERSION, REPAIR_INSTALLATION, SIGN_IN, INSTALL_RUNTIME, CHOOSE_RENDERER, INSTALL_COMPONENT, FREE_STORAGE, FIX_GAME_DIRECTORY, EDIT_PROFILE, INSTALL_LOADER }

data class LaunchIssue(val stage: LaunchState, val message: String, val fix: FixAction, val blocking: Boolean = true)

class LaunchBlockedException(val issues: List<LaunchIssue>) : Exception(issues.filter { it.blocking }.joinToString("\n") { it.message })

data class LaunchRequest(
    val profile: Profile,
    val session: () -> LaunchSession,
    val environment: (ResolvedVersion) -> RendererEnvironment,
    val device: DeviceFacts,
    val width: Int, val height: Int,
    val glfwShim: Path? = null,
    val failedRendererFingerprints: Set<String> = emptySet(),
    val launcherVersion: String = "1.0.0",
)

data class PreparedLaunch(val plan: LaunchPlan, val accessToken: String, val resolved: ResolvedVersion, val renderer: RendererDecision,
                          val runtime: RuntimeChoice, val rendererFingerprint: String, val warnings: List<LaunchIssue>)

/** Session status shared between the game process/executor and the launcher UI (no secrets). */
@Serializable data class SessionRecord(
    val profileId: String, val versionId: String, val rendererId: String, val state: LaunchState,
    val failedAt: LaunchState? = null, val milestones: List<Milestone> = emptyList(), val exitCode: Int? = null,
    val message: String? = null, val crashLine: String? = null, val startedAtMs: Long, val endedAtMs: Long? = null, val rendererFingerprint: String? = null,
)

/**
 * Supplies runtimes and native components automatically (from the APK or a verified download) so the user
 * never has to import anything by hand. Implementations must verify checksums before activating anything.
 */
interface Provisioner {
    /** Makes sure a runtime for Java [major] is installed if the launcher can supply one. */
    fun ensureRuntime(major: Int, progress: (String) -> Unit)
    /** Installs/updates bundled native component packs (LWJGL, renderers) when missing or outdated. */
    fun ensureComponents(progress: (String) -> Unit)
}

class LaunchCoordinator(
    private val dirs: LauncherDirs,
    private val platform: PlatformInfo,
    private val installer: Installer,
    private val runtimes: RuntimeManager,
    private val renderers: RendererRegistry,
    private val components: ComponentManager?,
    private val provisioner: Provisioner? = null,
) {
    private val planner = LaunchPlanner(dirs, platform)

    /** Stages 1-8: validation, runtime, renderer, plan. Throws [LaunchBlockedException] with corrective actions. */
    suspend fun prepare(req: LaunchRequest, sm: LaunchStateMachine): PreparedLaunch = withContext(Dispatchers.IO) {
        val p = req.profile
        val issues = mutableListOf<LaunchIssue>()
        fun block(stage: LaunchState): Nothing { sm.fail(issues.first { it.blocking }.message); throw LaunchBlockedException(issues) }

        sm.advance(LaunchState.VALIDATING)
        ProfileValidator.validate(p).forEach { issues += LaunchIssue(LaunchState.VALIDATING, "${it.field}: ${it.message}", FixAction.EDIT_PROFILE) }
        if (p.loader != null && p.launchVersionId == null) issues += LaunchIssue(LaunchState.VALIDATING, "${p.loader.type} loader is selected but not installed", FixAction.INSTALL_LOADER)
        val rv = runCatching { installer.resolve(p.effectiveVersionId, null) }.getOrElse {
            issues += LaunchIssue(LaunchState.VALIDATING, "Minecraft ${p.effectiveVersionId} is not installed (${it.message})", FixAction.INSTALL_VERSION); block(LaunchState.VALIDATING)
        }
        val report = Verifier(dirs, installer, platform).verify(rv, fullHash = false)
        if (!report.complete) issues += LaunchIssue(LaunchState.VALIDATING, report.summary() + ". First: ${report.issues.first().name} (${report.issues.first().problem})", FixAction.REPAIR_INSTALLATION)
        val session = runCatching { req.session() }.getOrElse {
            issues += LaunchIssue(LaunchState.VALIDATING, (it as? AuthException)?.message ?: "No usable account: ${it.message}", FixAction.SIGN_IN); null
        }
        val gameDir = runCatching { p.gameDir(dirs) }.getOrNull()
        if (gameDir == null) issues += LaunchIssue(LaunchState.VALIDATING, "Game directory is not configured", FixAction.FIX_GAME_DIRECTORY)
        else runCatching {
            Files.createDirectories(gameDir); LauncherDirs.GAME_SUBDIRS.forEach { Files.createDirectories(gameDir.resolve(it)) }
            val probe = gameDir.resolve(".cs-write-test"); pro.cslauncher.core.util.FsUtil.writeText(probe, "ok"); Files.delete(probe)
            val free = pro.cslauncher.core.util.FsUtil.usableSpace(gameDir)
            if (free < 150L * 1024 * 1024) issues += LaunchIssue(LaunchState.VALIDATING, "Only ${free / 1048576} MB free; at least 150 MB are needed for logs and world saves", FixAction.FREE_STORAGE)
        }.onFailure { issues += LaunchIssue(LaunchState.VALIDATING, "Game directory $gameDir is not writable: ${it.message}", FixAction.FIX_GAME_DIRECTORY) }
        if (issues.any { it.blocking }) block(LaunchState.VALIDATING)

        sm.advance(LaunchState.PREPARING_RUNTIME)
        val required = RuntimeSelector.requiredMajorFor(rv.meta.javaMajor)
        provisioner?.let { pv ->
            runCatching { pv.ensureRuntime(required) { msg -> sm.progress(msg) } }
                .onFailure { CsLog.w("Launch", "Automatic Java $required setup failed: ${it.message}"); issues += LaunchIssue(LaunchState.PREPARING_RUNTIME, "Automatic Java $required setup failed: ${it.message}", FixAction.INSTALL_RUNTIME, blocking = false) }
        }
        val choice = RuntimeSelector.select(required, rv.meta.usesLwjgl3, runtimes.list(), platform, p.runtimeId)
        if (choice.runtime == null) { issues += LaunchIssue(LaunchState.PREPARING_RUNTIME, choice.reason, FixAction.INSTALL_RUNTIME); block(LaunchState.PREPARING_RUNTIME) }
        if (choice.compatibility == Compatibility.RISKY) issues += LaunchIssue(LaunchState.PREPARING_RUNTIME, choice.reason, FixAction.INSTALL_RUNTIME, blocking = false)
        if (platform.isAndroid && choice.runtime.libjvm() == null) { issues += LaunchIssue(LaunchState.PREPARING_RUNTIME, "Runtime ${choice.runtime.id} has no libjvm.so", FixAction.INSTALL_RUNTIME); block(LaunchState.PREPARING_RUNTIME) }

        sm.advance(LaunchState.PREPARING_RENDERER)
        provisioner?.let { pv ->
            runCatching { pv.ensureComponents { msg -> sm.progress(msg) } }
                .onFailure { CsLog.w("Launch", "Bundled component setup failed: ${it.message}"); issues += LaunchIssue(LaunchState.PREPARING_RENDERER, "Bundled component setup failed: ${it.message}", FixAction.INSTALL_COMPONENT, blocking = false) }
        }
        val env = req.environment(rv)
        val lwjgl = if (platform.isAndroid) components?.lwjgl(platform.arch.androidAbi!!, LwjglCompat.gameVersion(rv.libraries.map { it.library.coordinate })) else null
        if (platform.isAndroid) {
            if (!rv.meta.usesLwjgl3) { issues += LaunchIssue(LaunchState.PREPARING_RENDERER, "Minecraft ${rv.meta.id} uses LWJGL 2; CS Launcher Pro currently supports LWJGL 3 versions (1.13+)", FixAction.EDIT_PROFILE); block(LaunchState.PREPARING_RENDERER) }
            if (lwjgl == null) { issues += LaunchIssue(LaunchState.PREPARING_RENDERER, "The LWJGL 3 Android component is not installed", FixAction.INSTALL_COMPONENT); block(LaunchState.PREPARING_RENDERER) }
            val bad = components!!.verify(lwjgl)
            if (bad.isNotEmpty()) { issues += LaunchIssue(LaunchState.PREPARING_RENDERER, "LWJGL component is damaged: ${bad.first()}", FixAction.INSTALL_COMPONENT); block(LaunchState.PREPARING_RENDERER) }
            LwjglCompat.problem(rv.libraries.map { it.library.coordinate }, lwjgl.lwjglVersion)?.let { issues += LaunchIssue(LaunchState.PREPARING_RENDERER, it, FixAction.EDIT_PROFILE); block(LaunchState.PREPARING_RENDERER) }
        }
        val memo = p.rendererMemo?.let { it.rendererId to it.fingerprint }
        val decision = renderers.decide(env, p.rendererId, p.rendererOptions, memo, req.failedRendererFingerprints)
        if (decision.backend == null || decision.config == null) {
            val missing = decision.statuses.values.any { it.state == RendererState.MISSING_COMPONENT }
            issues += LaunchIssue(LaunchState.PREPARING_RENDERER, decision.reason, if (missing) FixAction.INSTALL_COMPONENT else FixAction.CHOOSE_RENDERER)
            block(LaunchState.PREPARING_RENDERER)
        }

        val memory = p.memoryMb ?: PerformanceAdvisor.recommendedHeapMb(req.device, modCount(gameDir!!), choice.runtime.major)
        if (memory > PerformanceAdvisor.maxHeapMb(req.device)) issues += LaunchIssue(LaunchState.PREPARING_RUNTIME, "$memory MB exceeds the safe limit of ${PerformanceAdvisor.maxHeapMb(req.device)} MB", FixAction.EDIT_PROFILE, blocking = false)
        val sessionDir = dirs.sessions.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + p.id.take(8))
        Files.createDirectories(sessionDir.resolve("tmp"))
        installer.materializeResources(rv, gameDir!!)
        val s = session!!
        val plan = try {
            planner.plan(LaunchInput(p, rv, choice.runtime, s, decision.config, memory, req.width, req.height, sessionDir, lwjgl, req.glfwShim, req.launcherVersion))
        } catch (e: LaunchPlanException) {
            issues += LaunchIssue(LaunchState.PREPARING_RENDERER, e.message ?: "Invalid launch configuration", FixAction.EDIT_PROFILE); block(LaunchState.PREPARING_RENDERER)
        }
        FsUtil.writeAtomic(sessionDir.resolve("plan.json"), CsJson.encodeToString(LaunchPlan.serializer(), plan))
        pro.cslauncher.core.util.FsUtil.writeText(sessionDir.resolve("command-line.txt"), plan.redactedCommandLine())
        CsLog.i("Launch", "Prepared ${p.name}: ${rv.meta.id}, Java ${choice.runtime.major}, renderer ${decision.backend.id} (${decision.reason}), ${memory} MB")
        PreparedLaunch(plan, s.accessToken, rv, decision, choice, renderers.fingerprint(env, decision.backend.id), issues.filter { !it.blocking })
    }

    private fun modCount(gameDir: Path): Int = runCatching {
        Files.list(gameDir.resolve("mods")).use { s -> s.filter { it.fileName.toString().endsWith(".jar") }.count().toInt() }
    }.getOrDefault(0)
}
