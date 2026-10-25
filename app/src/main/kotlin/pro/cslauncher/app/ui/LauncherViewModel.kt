package pro.cslauncher.app.ui

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import pro.cslauncher.app.BuildConfig
import pro.cslauncher.app.Services
import pro.cslauncher.app.game.GameActivity
import pro.cslauncher.app.install.InstallJob
import pro.cslauncher.app.install.InstallManager
import pro.cslauncher.app.install.JobKind
import pro.cslauncher.app.install.JobStatus
import pro.cslauncher.core.account.*
import pro.cslauncher.core.activity.ActivityItem
import pro.cslauncher.core.activity.ActivityKind
import pro.cslauncher.core.content.LoaderInstaller
import pro.cslauncher.core.diagnostics.CrashAnalyzer
import pro.cslauncher.core.diagnostics.FailureKind
import pro.cslauncher.core.diagnostics.Diagnosis
import pro.cslauncher.core.launch.*
import pro.cslauncher.core.meta.VersionManifest
import pro.cslauncher.core.perf.PerformanceAdvisor
import pro.cslauncher.core.profile.*
import pro.cslauncher.core.settings.LauncherSettings
import pro.cslauncher.core.util.CsJson
import java.io.File
import java.nio.file.Files

enum class PlayState(val label: String) {
    INSTALL("Install"), DOWNLOADING("Downloading"), VERIFYING("Verifying"), PREPARING("Preparing"), LAUNCHING("Launching"),
    PLAYING("Playing"), UPDATING("Updating"), REPAIR_REQUIRED("Repair Required"), LAUNCH_FAILED("Launch Failed"), READY("Play"), NO_PROFILE("Create a profile")
}

sealed class MsaState {
    data object Idle : MsaState()
    data class Code(val userCode: String, val uri: String, val expiresAtMs: Long) : MsaState()
    data class Working(val message: String) : MsaState()
    data class Error(val message: String) : MsaState()
    data class Done(val name: String) : MsaState()
}

data class LastFailure(val title: String, val issues: List<LaunchIssue>, val diagnoses: List<Diagnosis>, val sessionDir: String?)

data class UiState(
    val profiles: ProfilesFile = ProfilesFile(),
    val accounts: AccountsFile = AccountsFile(),
    val manifest: VersionManifest? = null,
    val manifestError: String? = null,
    val installed: Set<String> = emptySet(),
    val needsRepair: Set<String> = emptySet(),
    val launch: LaunchStatus? = null,
    val launching: Boolean = false,
    val gameRunning: Boolean = false,
    val gameState: LaunchState? = null,
    val failure: LastFailure? = null,
    val activity: List<ActivityItem> = emptyList(),
    val message: String? = null,
) {
    val selected: Profile? get() = profiles.profiles.firstOrNull { it.id == profiles.selectedId } ?: profiles.profiles.firstOrNull()
    val activeAccount: Account? get() = accounts.accounts.firstOrNull { it.id == accounts.activeId }
}

class LauncherViewModel(app: Application) : AndroidViewModel(app) {
    private val s = Services
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui
    val settings: StateFlow<LauncherSettings> = s.settings.state
    val jobs: StateFlow<Map<String, InstallJob>> = InstallManager.jobs
    private val _msa = MutableStateFlow<MsaState>(MsaState.Idle)
    val msa: StateFlow<MsaState> = _msa
    private var msaJob: Job? = null
    // v2: v1 entries could blame a renderer for failures it did not cause, so they are no longer read.
    private val failedFingerprintsFile get() = s.dirs.config.resolve("renderer-failures-v2.txt").toFile()
    private val processedFile get() = s.dirs.config.resolve("processed-sessions.txt").toFile()
    val device = s.device
    val facts by lazy { s.device.facts() }

    init {
        reload()
        refreshManifest(false)
        viewModelScope.launch { jobs.collect { reloadInstalled() } }
    }

    fun toast(msg: String?) = _ui.update { it.copy(message = msg) }

    private fun io(block: suspend CoroutineScope.() -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { toast(e.message ?: e.toString()) }
    }

    fun reload() = io {
        _ui.update { it.copy(profiles = s.profiles.load(), accounts = s.accounts.load(), activity = s.activity.list()) }
        reloadInstalled()
        processSessions()
    }

    private fun marker(v: String) = s.dirs.versionDir(v).resolve(".cs-installed")
    private fun repairFlag(v: String) = s.dirs.versionDir(v).resolve(".cs-needs-repair")

    private fun reloadInstalled() {
        val ids = _ui.value.profiles.profiles.map { it.effectiveVersionId }.toSet()
        _ui.update { st -> st.copy(installed = ids.filter { Files.isRegularFile(marker(it)) }.toSet(), needsRepair = ids.filter { Files.isRegularFile(repairFlag(it)) }.toSet()) }
    }

    fun refreshManifest(force: Boolean) = io {
        val m = runCatching { s.manifests.load(force) }
        _ui.update { it.copy(manifest = m.getOrNull() ?: it.manifest, manifestError = m.exceptionOrNull()?.let { e -> "Version list unavailable: ${e.message}" }) }
    }

    // ------------------------------------------------------------------ play state
    fun playState(st: UiState, jobs: Map<String, InstallJob>): PlayState {
        val p = st.selected ?: return PlayState.NO_PROFILE
        val v = p.effectiveVersionId
        val job = jobs[v]?.takeIf { it.active }
        if (st.gameRunning) return if (st.gameState == LaunchState.RUNNING) PlayState.PLAYING else PlayState.LAUNCHING
        if (job != null) return when {
            job.kind == JobKind.VERIFY_REPAIR || job.stage.contains("Verif", true) -> PlayState.VERIFYING
            p.lastPlayedAtMs != null -> PlayState.UPDATING
            else -> PlayState.DOWNLOADING
        }
        if (st.launching) return when (st.launch?.state) { LaunchState.INITIALIZING_GAME, LaunchState.LOADING_GAME -> PlayState.LAUNCHING; else -> PlayState.PREPARING }
        if (v in st.needsRepair) return PlayState.REPAIR_REQUIRED
        if (v !in st.installed) return PlayState.INSTALL
        if (st.failure != null) return PlayState.LAUNCH_FAILED
        return PlayState.READY
    }

    fun primaryAction() {
        val st = _ui.value; val p = st.selected ?: return
        when (playState(st, jobs.value)) {
            PlayState.INSTALL -> install(p.effectiveVersionId)
            PlayState.REPAIR_REQUIRED -> repair(p.effectiveVersionId)
            PlayState.READY, PlayState.LAUNCH_FAILED -> play()
            else -> {}
        }
    }

    fun install(versionId: String) = InstallManager.start(getApplication(), versionId, JobKind.INSTALL) { ok -> afterJob(versionId, ok) }
    fun repair(versionId: String) = InstallManager.start(getApplication(), versionId, JobKind.VERIFY_REPAIR) { ok -> afterJob(versionId, ok) }
    fun cancelJob(versionId: String) = InstallManager.cancel(versionId)
    private fun afterJob(v: String, ok: Boolean) = io {
        if (ok) { Files.createDirectories(marker(v).parent); marker(v).toFile().writeText(System.currentTimeMillis().toString()); Files.deleteIfExists(repairFlag(v)) }
        else if (Files.isRegularFile(marker(v))) { Files.deleteIfExists(marker(v)); repairFlag(v).toFile().writeText("verify failed") }
        reloadInstalled()
    }

    // ------------------------------------------------------------------ launch
    private fun gameProcessAlive(): Boolean {
        val am = getApplication<Application>().getSystemService(ActivityManager::class.java)
        val name = getApplication<Application>().packageName + ":game"
        return am.runningAppProcesses?.any { it.processName == name } == true
    }

    fun play() {
        val st = _ui.value; val p = st.selected ?: return
        if (st.launching || gameProcessAlive()) { toast("The game is already running"); return }
        val acc = st.activeAccount ?: run { toast("Add an account first (Accounts)"); return }
        if (!LaunchLocks.tryAcquire(p.id)) return
        _ui.update { it.copy(launching = true, failure = null, launch = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val sm = LaunchStateMachine()
            val watch = launch { sm.status.collect { stt -> _ui.update { it.copy(launch = stt) } } }
            try {
                val account = ensureFreshSession(acc)
                val dm = getApplication<Application>().resources.displayMetrics
                val scale = p.renderScale.coerceIn(0.3f, 1f)
                val w = (maxOf(dm.widthPixels, dm.heightPixels) * scale).toInt(); val h = (minOf(dm.widthPixels, dm.heightPixels) * scale).toInt()
                val shim = File(getApplication<Application>().applicationInfo.nativeLibraryDir, "libcsglfw.so").toPath()
                val req = LaunchRequest(p, { s.accounts.session(account, settings.value.msaClientId) }, { rv -> s.device.rendererEnvironment(rv) }, facts, w, h,
                    glfwShim = shim, failedRendererFingerprints = failedFingerprints(), launcherVersion = BuildConfig.VERSION_NAME)
                val prepared = s.coordinator.prepare(req, sm)
                prepared.warnings.forEach { pro.cslauncher.core.util.CsLog.w("Launch", it.message) }
                sm.advance(LaunchState.INITIALIZING_GAME)
                val intent = Intent(getApplication(), GameActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(GameActivity.EXTRA_SESSION_DIR, prepared.plan.sessionDir).putExtra(GameActivity.EXTRA_ACCOUNT_ID, account.id)
                    .putExtra(GameActivity.EXTRA_FINGERPRINT, prepared.rendererFingerprint).putExtra(GameActivity.EXTRA_RENDER_SCALE, scale)
                s.profiles.upsert(p.copy(lastPlayedAtMs = System.currentTimeMillis()))
                s.activity.add(ActivityItem(ActivityKind.LAUNCHED, "Launched ${p.name}", "${prepared.plan.versionId} · ${prepared.renderer.backend?.displayName ?: prepared.plan.rendererId}", profileId = p.id), settings.value.recentActivityEnabled)
                withContext(Dispatchers.Main) { getApplication<Application>().startActivity(intent) }
                _ui.update { it.copy(gameRunning = true, gameState = LaunchState.INITIALIZING_GAME) }
            } catch (e: LaunchBlockedException) {
                _ui.update { it.copy(failure = LastFailure("Launch blocked", e.issues, emptyList(), null)) }
                LaunchLocks.release(p.id)
            } catch (e: AuthException) {
                _ui.update { it.copy(failure = LastFailure("Sign-in required", listOf(LaunchIssue(LaunchState.VALIDATING, e.message ?: "Sign in again", FixAction.SIGN_IN)), emptyList(), null)) }
                LaunchLocks.release(p.id)
            } catch (e: Exception) {
                runCatching { sm.fail(e.message ?: "error") }
                _ui.update { it.copy(failure = LastFailure("Launch failed", listOf(LaunchIssue(sm.state, e.message ?: e.toString(), FixAction.NONE)), emptyList(), null)) }
                LaunchLocks.release(p.id)
            } finally {
                watch.cancel()
                _ui.update { it.copy(launching = false) }
                reload()
            }
        }
    }

    /** Refreshes an expired Microsoft session with the stored refresh token before launching. */
    private fun ensureFreshSession(acc: Account): Account {
        if (acc.type != AccountType.MICROSOFT) return acc
        if ((acc.accessTokenExpiresAtMs ?: 0) > System.currentTimeMillis() + 5 * 60_000) return acc
        val refresh = s.accounts.refreshToken(acc.id) ?: throw AuthException("Session for ${acc.username} expired. Sign in again.")
        val auth = MicrosoftAuthenticator(s.http, settings.value.msaClientId)
        val tokens = auth.refresh(refresh)
        val login = auth.loginMinecraft(tokens.accessToken)
        s.accounts.storeMicrosoftTokens(acc.id, login.accessToken, tokens.refreshToken)
        val updated = acc.copy(accessTokenExpiresAtMs = System.currentTimeMillis() + login.expiresInSec * 1000, needsRelogin = false)
        s.accounts.upsert(updated)
        return updated
    }

    private fun failedFingerprints(): Set<String> = if (failedFingerprintsFile.isFile) failedFingerprintsFile.readLines().filter { it.isNotBlank() }.toSet() else emptySet()

    /** Reads session records written by the game process and applies their results once. */
    private fun processSessions() {
        val dirs = s.dirs.sessions.toFile().listFiles()?.filter { File(it, "session.json").isFile }?.sortedByDescending { it.name } ?: return
        val processed = if (processedFile.isFile) processedFile.readLines().toMutableSet() else mutableSetOf()
        val alive = gameProcessAlive()
        val latest = dirs.firstOrNull()
        for (dir in dirs.take(10)) {
            if (dir.name in processed) continue
            val rec = runCatching { CsJson.decodeFromString(SessionRecord.serializer(), File(dir, "session.json").readText()) }.getOrNull() ?: continue
            val ended = rec.endedAtMs != null || (!alive || dir != latest)
            if (!ended) { _ui.update { it.copy(gameRunning = true, gameState = rec.state) }; continue }
            val profile = s.profiles.load().profiles.firstOrNull { it.id == rec.profileId }
            val outcome = when {
                rec.endedAtMs == null -> RunOutcome.KILLED
                rec.state == LaunchState.EXITED -> RunOutcome.SUCCESS
                Milestone.READY in rec.milestones -> RunOutcome.CRASHED
                else -> RunOutcome.FAILED_TO_START
            }
            if (profile != null) {
                var p = profile.copy(lastRun = RunStatus(outcome, rec.endedAtMs ?: System.currentTimeMillis(), rec.failedAt?.name, rec.exitCode, rec.message ?: rec.crashLine))
                if (Milestone.READY in rec.milestones && rec.rendererFingerprint != null) p = p.copy(rendererMemo = RendererMemo(rec.rendererId, rec.rendererFingerprint!!, System.currentTimeMillis()))
                s.profiles.upsert(p)
            }
            val gameDir = profile?.gameDir(s.dirs)
            val diagnoses = if (outcome == RunOutcome.SUCCESS) emptyList() else runCatching {
                CrashAnalyzer.analyze(CrashAnalyzer.collectEvidence(gameDir ?: s.dirs.root, File(dir, "game-output.log").toPath()), rec.exitCode)
            }.getOrDefault(emptyList())
            // Only a failure that can be the renderer's fault counts against it (pointer-tag aborts, Java errors,
            // missing files etc. do not), so an unrelated failure can never lock a working renderer out.
            val rendererSuspect = diagnoses.isEmpty() || diagnoses.first().kind.rendererRelated
            if (outcome == RunOutcome.FAILED_TO_START && Milestone.RENDER_INIT !in rec.milestones && rec.rendererFingerprint != null && rendererSuspect)
                failedFingerprintsFile.appendText(rec.rendererFingerprint + "\n")   // next auto selection prefers another renderer configuration
            if (outcome != RunOutcome.SUCCESS && dir == latest) {
                val processCrashed = outcome == RunOutcome.KILLED && diagnoses.any { it.kind != FailureKind.PROCESS_KILLED }
                val title = when {
                    outcome == RunOutcome.CRASHED || processCrashed -> "Game crashed"
                    outcome == RunOutcome.KILLED -> "Game was stopped"
                    else -> "Game failed to start"
                }
                _ui.update { it.copy(failure = LastFailure(title,
                    listOfNotNull(rec.message?.let { m -> LaunchIssue(rec.failedAt ?: LaunchState.INITIALIZING_GAME, m, FixAction.NONE) }), diagnoses, dir.absolutePath)) }
                s.activity.add(ActivityItem(ActivityKind.CRASHED, title, diagnoses.firstOrNull()?.kind?.title ?: (rec.crashLine ?: "exit ${rec.exitCode}"), profileId = rec.profileId), settings.value.recentActivityEnabled)
            }
            processed += dir.name
            LaunchLocks.release(rec.profileId)
        }
        processedFile.writeText(processed.toList().takeLast(200).joinToString("\n"))
        if (!alive) _ui.update { it.copy(gameRunning = false, gameState = null) }
        _ui.update { it.copy(profiles = s.profiles.load(), activity = s.activity.list()) }
    }

    fun dismissFailure() = _ui.update { it.copy(failure = null) }

    // ------------------------------------------------------------------ profiles
    fun select(id: String) = io { s.profiles.select(id); reload() }
    fun saveProfile(p: Profile) = io {
        val problems = ProfileValidator.validate(p)
        if (problems.isNotEmpty()) { toast(problems.joinToString { it.message }); return@io }
        s.profiles.upsert(p); reload()
    }
    fun createProfile(name: String, versionId: String, loader: LoaderType) = io {
        var p = Profile(name = name.trim().ifBlank { versionId }, versionId = versionId)
        if (loader != LoaderType.VANILLA) {
            val li = LoaderInstaller(s.http, s.dirs)
            val support = li.support(loader); if (!support.supported) { toast(support.reason); return@io }
            val v = li.versions(loader, versionId).firstOrNull { it.stable } ?: li.versions(loader, versionId).firstOrNull() ?: run { toast("No $loader version for $versionId"); return@io }
            val id = li.install(loader, versionId, v.version)
            p = p.copy(loader = LoaderSpec(loader, v.version), launchVersionId = id)
        }
        s.profiles.upsert(p); s.profiles.select(p.id); reload()
    }
    fun duplicate(id: String, name: String) = io { s.profiles.duplicate(id, name); reload() }
    fun deletionPlan(id: String) = s.profiles.planDeletion(id)
    fun delete(id: String, deleteFiles: Boolean) = io { s.profiles.delete(id, deleteFiles); reload() }
    fun exportProfile(id: String): String = s.profiles.export(id)
    fun importProfile(text: String) = io { s.profiles.import(text); reload() }
    fun maxHeapMb() = PerformanceAdvisor.maxHeapMb(facts)
    fun recommendedHeapMb() = PerformanceAdvisor.recommendedHeapMb(facts)

    // ------------------------------------------------------------------ accounts
    fun addOffline(name: String) = io { s.accounts.addOffline(name.trim()); reload() }
    fun setActiveAccount(id: String) = io { s.accounts.setActive(id); reload() }
    fun removeAccount(id: String) = io { s.accounts.remove(id); reload() }

    fun startMicrosoft() {
        msaJob?.cancel()
        val clientId = settings.value.msaClientId
        if (clientId.isBlank()) { _msa.value = MsaState.Error("Enter the Azure application (client) ID in Settings › Accounts first. CS Launcher Pro ships no embedded credentials."); return }
        msaJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val auth = MicrosoftAuthenticator(s.http, clientId)
                _msa.value = MsaState.Working("Requesting a sign-in code…")
                val dc = auth.startDeviceCode()
                val expires = System.currentTimeMillis() + dc.expiresInSec * 1000L
                _msa.value = MsaState.Code(dc.userCode, dc.verificationUri, expires)
                var tokens: MsaTokens? = null
                while (tokens == null) {
                    if (System.currentTimeMillis() > expires) throw AuthException("The code expired. Start again.")
                    delay(dc.intervalSec.coerceAtLeast(2) * 1000L)
                    tokens = auth.pollDeviceCode(dc)
                }
                _msa.value = MsaState.Working("Signing in to Minecraft services…")
                val login = auth.loginMinecraft(tokens.accessToken)
                if (!login.ownsGame) throw AuthException("This Microsoft account does not own Minecraft: Java Edition.")
                val acc = Account("msa-" + login.uuid.replace("-", ""), AccountType.MICROSOFT, login.name, login.uuid, login.xuid,
                    accessTokenExpiresAtMs = System.currentTimeMillis() + login.expiresInSec * 1000)
                s.accounts.storeMicrosoftTokens(acc.id, login.accessToken, tokens.refreshToken)
                s.accounts.upsert(acc); s.accounts.setActive(acc.id)
                _msa.value = MsaState.Done(login.name)
                reload()
            } catch (e: CancellationException) { _msa.value = MsaState.Idle
            } catch (e: Exception) { _msa.value = MsaState.Error(e.message ?: e.toString()) }
        }
    }
    fun cancelMicrosoft() { msaJob?.cancel(); _msa.value = MsaState.Idle }

    // ------------------------------------------------------------------ settings & activity
    fun updateSettings(f: (LauncherSettings) -> LauncherSettings) = io { s.settings.update(f) }
    fun clearActivity() = io { s.activity.clear(); reload() }
}
