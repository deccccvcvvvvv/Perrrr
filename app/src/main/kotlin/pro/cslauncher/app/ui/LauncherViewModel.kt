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
import pro.cslauncher.core.content.*
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

/** OptiFine profiles launch an "<mc>-OptiFine_<edition>" version. */
val Profile.isOptiFine: Boolean get() = launchVersionId?.contains("-OptiFine_") == true

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
        val job = (jobs["task:profile-${p.id}"] ?: jobs[v])?.takeIf { it.active }
        if (st.gameRunning) return if (st.gameState == LaunchState.RUNNING) PlayState.PLAYING else PlayState.LAUNCHING
        if (job != null) return when {
            job.kind == JobKind.VERIFY_REPAIR || job.stage.contains("Verif", true) -> PlayState.VERIFYING
            p.lastPlayedAtMs != null -> PlayState.UPDATING
            else -> PlayState.DOWNLOADING
        }
        if (st.launching) return when (st.launch?.state) { LaunchState.INITIALIZING_GAME, LaunchState.LOADING_GAME -> PlayState.LAUNCHING; else -> PlayState.PREPARING }
        if (v in st.needsRepair) return PlayState.REPAIR_REQUIRED
        if (p.installationState in listOf("pending", "installing", "incomplete") || v !in st.installed) return PlayState.INSTALL
        if (st.failure != null) return PlayState.LAUNCH_FAILED
        return PlayState.READY
    }

    fun primaryAction() {
        val st = _ui.value; val p = st.selected ?: return
        when (playState(st, jobs.value)) {
            PlayState.INSTALL -> installProfile(p.id)
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
        if (jobs.value["task:profile-${p.id}"]?.active == true || p.installationState in listOf("pending", "installing", "incomplete")) {
            toast("Complete the instance installation first"); return
        }
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
                val w = p.resolutionWidth ?: (maxOf(dm.widthPixels, dm.heightPixels) * scale).toInt(); val h = p.resolutionHeight ?: (minOf(dm.widthPixels, dm.heightPixels) * scale).toInt()
                val shim = File(getApplication<Application>().applicationInfo.nativeLibraryDir, "libcsglfw.so").toPath()
                val launchProfile = if (account.type == AccountType.ELY_BY) {
                    val agent = ElyAuthenticator(s.http).ensureAgent(s.dirs.cache.resolve("auth"))
                    p.copy(jvmArgs = p.jvmArgs + " \"-javaagent:${agent.toAbsolutePath()}=https://authserver.ely.by\"")
                } else p
                val req = LaunchRequest(launchProfile, { s.accounts.session(account, settings.value.msaClientId) }, { rv -> s.device.rendererEnvironment(rv) }, facts, w, h,
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
        if (acc.type == AccountType.ELY_BY) {
            val (token, client) = s.accounts.elyTokens(acc.id)
            val login = ElyAuthenticator(s.http).refresh(token, client)
            s.accounts.storeElyTokens(login.account.id, login.token, login.client)
            s.accounts.upsert(login.account)
            return login.account
        }
        if (acc.type != AccountType.MICROSOFT) return acc
        if ((acc.accessTokenExpiresAtMs ?: 0) > System.currentTimeMillis() + 5 * 60_000) return acc
        val refresh = s.accounts.refreshToken(acc.id) ?: throw AuthException("Session for ${acc.username} expired. Sign in again.")
        val auth = MicrosoftAuthenticator(s.http, settings.value.msaClientId)
        val tokens = auth.refresh(refresh)
        val login = auth.loginMinecraft(tokens.accessToken)
        s.accounts.storeMicrosoftTokens(acc.id, login.accessToken, tokens.refreshToken)
        val updated = acc.copy(username = login.name, skinUrl = login.skinUrl, accessTokenExpiresAtMs = System.currentTimeMillis() + login.expiresInSec * 1000, needsRelogin = false)
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
    fun playProfile(id: String) = io {
        s.profiles.select(id)
        _ui.update { it.copy(profiles = s.profiles.load()) }
        reloadInstalled()
        withContext(Dispatchers.Main) { primaryAction() }
    }
    fun saveProfile(p: Profile) = io {
        require(jobs.value["task:profile-${p.id}"]?.active != true) { "Wait for installation to finish before editing this instance" }
        require((p.memoryMb ?: 512) <= maxHeapMb()) { "Memory exceeds the device safe limit" }
        val problems = ProfileValidator.validate(p)
        if (problems.isNotEmpty()) { toast(problems.joinToString { it.message }); return@io }
        s.profiles.upsert(p); reload()
    }
    fun createProfile(name: String, versionId: String, loader: LoaderType,
        memoryMb: Int? = settings.value.defaultMemoryMb,
        runtimeId: String? = settings.value.defaultRuntimeId,
        rendererId: String = settings.value.defaultRenderer,
        icon: String = "grass", jvmArgs: String = settings.value.defaultJvmArgs,
        directory: GameDirMode = GameDirMode.ISOLATED, loaderVersion: String? = null) = io {
        val p = Profile(name = name.trim().ifBlank { "$versionId ${loader.name.lowercase().replaceFirstChar(Char::uppercase)}" },
            versionId = versionId, memoryMb = memoryMb, runtimeId = runtimeId,
            rendererId = rendererId, iconKey = icon, jvmArgs = jvmArgs, gameDirMode = directory,
            fullscreen = settings.value.defaultFullscreen,
            resolutionWidth = settings.value.defaultResolutionWidth,
            resolutionHeight = settings.value.defaultResolutionHeight,
            loader = if (loader == LoaderType.VANILLA) null else LoaderSpec(loader, loaderVersion ?: ""),
            installationState = "pending")
        val errors = ProfileValidator.validate(p)
        require(errors.isEmpty()) { errors.joinToString { it.message } }
        require(memoryMb == null || memoryMb <= maxHeapMb()) { "Memory exceeds the device's safe limit" }
        s.profiles.upsert(p); s.profiles.select(p.id); reload()
        installProfile(p.id)
    }

    /** All dependencies and runtime provisioning share the foreground installation queue. */
    fun installProfile(id: String) = io {
        val original = s.profiles.load().profiles.first { it.id == id }
        InstallManager.startTask(getApplication(), "profile-$id", "${original.name} · ${original.versionId}", { ok ->
            io {
                val current = s.profiles.load().profiles.firstOrNull { it.id == id }
                if (current != null) s.profiles.upsert(current.copy(installationState = if (ok) "ready" else "incomplete",
                    installationError = if (ok) null else "Installation incomplete. Open Downloads for details and retry from Profiles."))
                reload()
            }
        }) {
            var p = s.profiles.load().profiles.first { it.id == id }
            s.profiles.upsert(p.copy(installationState = "installing")); reload()
            progress("Validating directory")
            val dir = p.gameDir(s.dirs)
            Files.createDirectories(dir)
            require(Files.isWritable(dir)) { "Instance directory is not writable" }
            require(Files.getFileStore(dir).usableSpace > 256L * 1024 * 1024) { "Less than 256 MB free in instance storage" }
            val manifest = s.manifests.load()
            installVersion(p.versionId, manifest)
            if (p.loader != null) {
                val li = LoaderInstaller(s.http, s.dirs)
                require(li.support(p.loader!!.type).supported) { li.support(p.loader!!.type).reason }
                val versions = li.versions(p.loader!!.type, p.versionId)
                val selected = p.loader!!.version.takeIf { it.isNotBlank() }
                    ?: versions.firstOrNull { it.stable }?.version ?: versions.firstOrNull()?.version
                    ?: error("No compatible loader for ${p.versionId}")
                require(versions.any { it.version == selected }) { "Loader $selected is incompatible with ${p.versionId}" }
                progress("Installing loader", selected)
                val launchId = li.install(p.loader!!.type, p.versionId, selected)
                p = p.copy(loader = p.loader!!.copy(version = selected), launchVersionId = launchId)
                s.profiles.upsert(p.copy(installationState = "installing")); reload()
                installVersion(launchId, manifest)
            }
            val resolved = s.installer.resolve(p.effectiveVersionId, manifest)
            val required = pro.cslauncher.core.runtime.RuntimeSelector.requiredMajorFor(resolved.meta.javaMajor)
            s.provisioner.ensureRuntime(required) { progress("Java runtime", it) }
            val choice = pro.cslauncher.core.runtime.RuntimeSelector.select(required, resolved.meta.usesLwjgl3,
                s.runtimes.list(), s.platform, p.runtimeId)
            require(choice.runtime != null) { choice.reason }
            s.profiles.upsert(p.copy(installationState = "ready", installationError = null)); reload()
            true
        }
    }
    fun duplicate(id: String, name: String) = io { s.profiles.duplicate(id, name); reload() }
    fun deletionPlan(id: String) = s.profiles.planDeletion(id)
    fun delete(id: String, deleteFiles: Boolean) = io { require(jobs.value["task:profile-$id"]?.active != true) { "Cancel or finish installation before removing this instance" }; s.profiles.delete(id, deleteFiles); reload() }
    fun exportProfile(id: String): String = s.profiles.export(id)
    fun importProfile(text: String) = io { s.profiles.import(text); reload() }
    fun maxHeapMb() = PerformanceAdvisor.maxHeapMb(facts)
    fun recommendedHeapMb() = PerformanceAdvisor.recommendedHeapMb(facts)

    // ------------------------------------------------------------------ accounts
    fun addEly(username: String, password: String, otp: String) = io {
        val login = ElyAuthenticator(s.http).login(username.trim(), password, otp.trim())
        s.accounts.storeElyTokens(login.account.id, login.token, login.client)
        s.accounts.upsert(login.account); s.accounts.setActive(login.account.id); reload()
    }
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
                    accessTokenExpiresAtMs = System.currentTimeMillis() + login.expiresInSec * 1000, skinUrl = login.skinUrl)
                s.accounts.storeMicrosoftTokens(acc.id, login.accessToken, tokens.refreshToken)
                s.accounts.upsert(acc); s.accounts.setActive(acc.id)
                _msa.value = MsaState.Done(login.name)
                reload()
            } catch (e: CancellationException) { _msa.value = MsaState.Idle
            } catch (e: Exception) { _msa.value = MsaState.Error(e.message ?: e.toString()) }
        }
    }
    fun cancelMicrosoft() { msaJob?.cancel(); _msa.value = MsaState.Idle }

    // ------------------------------------------------------------------ OptiFine, modpacks, catalog content
    fun inspectOptiFine(jar: File): OptiFineJarInfo = OptiFineInstaller(s.dirs).inspect(jar.toPath())

    /**
     * Installs OptiFine from a jar the user obtained from optifine.net: vanilla first, then the headless patcher, then the
     * resulting version is verified like any other. Attaches to [attachToProfileId] when it is a vanilla profile of the
     * same Minecraft version, otherwise creates an "OptiFine <mc>" profile.
     */
    fun installOptiFine(jar: File, attachToProfileId: String? = null): Boolean {
        val info = try { inspectOptiFine(jar) } catch (e: Exception) { toast(e.message ?: "Not an OptiFine jar"); return false }
        val started = InstallManager.startTask(getApplication(), "optifine-${info.versionId}", "OptiFine ${info.minecraftVersion} ${info.edition}", { reload() }) {
            val manifest = runCatching { s.manifests.load() }.getOrNull()
            installVersion(info.minecraftVersion, manifest)
            progress("Patching OptiFine", "Building the OptiFine library from your jar")
            val res = OptiFineInstaller(s.dirs).install(jar.toPath()) { done, total -> progress("Patching OptiFine", "$done / $total classes", done, total) }
            installVersion(res.versionId, manifest)
            val existing = attachToProfileId?.let { id -> s.profiles.load().profiles.firstOrNull { it.id == id } }
            val profile = if (existing != null && existing.versionId == info.minecraftVersion && (existing.loader?.type ?: LoaderType.VANILLA) == LoaderType.VANILLA)
                existing.copy(launchVersionId = res.versionId, loader = null)
            else Profile(name = "OptiFine ${info.minecraftVersion}", versionId = info.minecraftVersion, launchVersionId = res.versionId, iconKey = "bolt")
            s.profiles.upsert(profile); s.profiles.select(profile.id)
            s.activity.add(ActivityItem(ActivityKind.INSTALLED, "Installed OptiFine ${info.edition}", "Minecraft ${info.minecraftVersion} · ${profile.name}", profileId = profile.id), settings.value.recentActivityEnabled)
            toast("OptiFine ${info.edition} is ready in “${profile.name}”")
            true
        }
        if (!started) toast("This OptiFine version is already being installed")
        return started
    }

    private suspend fun InstallManager.TaskScope.modpackWork(mrpack: File, iconKey: String) {
        val index = Mrpack.read(mrpack.toPath())
        val inst = ModpackInstaller(s.downloads)
        inst.checkLoaderSupported(index)
        val manifest = runCatching { s.manifests.load() }.getOrNull()
        installVersion(index.minecraftVersion, manifest)
        var profile = Profile(name = index.name.take(64), versionId = index.minecraftVersion, gameDirMode = GameDirMode.ISOLATED, iconKey = iconKey)
        if (index.loader != LoaderType.VANILLA) {
            progress("Installing ${index.loader.name.lowercase()} ${index.loaderVersion}")
            val lv = index.loaderVersion ?: throw ModpackException("Modpack does not name a loader version")
            val id = LoaderInstaller(s.http, s.dirs).install(index.loader, index.minecraftVersion, lv)
            installVersion(id, manifest)
            profile = profile.copy(loader = LoaderSpec(index.loader, lv), launchVersionId = id)
        }
        val gameDir = profile.gameDir(s.dirs)   // a brand-new instance folder owned by this job
        try {
            val r = inst.installFiles(mrpack.toPath(), gameDir) { p -> progress("Downloading modpack files", p) }
            progress("Finishing", "${r.downloaded} files, ${r.overrides} overrides")
        } catch (e: Throwable) {
            gameDir.toFile().deleteRecursively(); throw e
        }
        s.profiles.upsert(profile); s.profiles.select(profile.id)
        s.activity.add(ActivityItem(ActivityKind.INSTALLED, "Installed modpack ${index.name}", "${index.minecraftVersion} · ${index.loader.name.lowercase()}", profileId = profile.id), settings.value.recentActivityEnabled)
        toast("Modpack “${index.name}” installed")
    }

    /** Installs a local .mrpack (picked with the file picker) as a new isolated profile. */
    fun installModpack(mrpack: File): Boolean {
        val index = try { Mrpack.read(mrpack.toPath()).also { ModpackInstaller(s.downloads).checkLoaderSupported(it) } } catch (e: Exception) { toast(e.message ?: "Not a valid .mrpack"); return false }
        return InstallManager.startTask(getApplication(), "modpack-${index.name}-${index.versionId}", "Modpack ${index.name}", { reload() }) { modpackWork(mrpack, "chest"); true }
            .also { if (!it) toast("${index.name} is already being installed") }
    }

    /** Downloads a modpack version from the catalog, then installs it like a local .mrpack. */
    fun installModpackFromCatalog(file: CatalogFile, title: String): Boolean {
        if (!file.fileName.endsWith(".mrpack")) { toast("Only Modrinth .mrpack modpacks can be installed automatically"); return false }
        return InstallManager.startTask(getApplication(), "modpack-${file.projectId}-${file.versionName}", "Modpack $title", { reload() }) {
            val dir = File(getApplication<Application>().cacheDir, "modpacks").toPath()
            val task = file.task(dir)
            progress("Downloading $title", file.fileName)
            val r = s.downloads.downloadAll("modpack", listOf(task)) { p -> progress("Downloading $title", p) }
            if (!r.ok) throw java.io.IOException(r.summary())
            try { modpackWork(task.destination.toFile(), "chest") } finally { Files.deleteIfExists(task.destination) }
            true
        }.also { if (!it) toast("$title is already being installed") }
    }

    fun installedContent(profile: Profile, kind: ProjectKind): Map<String, IndexedContent> =
        if (kind == ProjectKind.MODPACK) emptyMap() else runCatching { ContentIndex(profile.gameDir(s.dirs)).installed(kind) }.getOrDefault(emptyMap())

    /** Installs a catalog file (with required dependencies for mods) into the profile's game directory. */
    suspend fun installCatalogFile(catalog: ContentCatalog, title: String, file: CatalogFile, kind: ProjectKind, profile: Profile): Result<CatalogInstallResult> = withContext(Dispatchers.IO) {
        runCatching {
            val r = CatalogInstaller(s.downloads).install(catalog, title, file, kind, profile.gameDir(s.dirs), profile.versionId, profileLoaderId(profile, kind))
            if (kind == ProjectKind.MOD) s.activity.add(ActivityItem(ActivityKind.MOD_IMPORTED, "Added $title", "${profile.name} · ${file.versionName}", profileId = profile.id), settings.value.recentActivityEnabled)
            r
        }
    }

    /** Loader facet for catalog queries: mod loader for mods, shader loader (iris/optifine) for shaders, none otherwise. */
    fun profileLoaderId(profile: Profile, kind: ProjectKind): String? = when (kind) {
        ProjectKind.MOD -> profile.loader?.type?.takeIf { it != LoaderType.VANILLA }?.name?.lowercase()
        ProjectKind.SHADER -> if (profile.isOptiFine) "optifine" else null
        else -> null
    }

    // ------------------------------------------------------------------ settings & activity
    fun updateSettings(f: (LauncherSettings) -> LauncherSettings) = io {
        s.settings.update(f)
        val current = s.settings.state.value
        s.downloads.configure(current.downloadConcurrency.coerceIn(1, 16), current.downloadAttempts.coerceIn(1, 6))
    }
    fun clearActivity() = io { s.activity.clear(); reload() }
}
