package pro.cslauncher.core.profile

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.util.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

@Serializable enum class LoaderType { VANILLA, FABRIC, QUILT, FORGE, NEOFORGE }
@Serializable data class LoaderSpec(val type: LoaderType, val version: String)
@Serializable enum class GameDirMode { SHARED, ISOLATED, CUSTOM }
@Serializable enum class RunOutcome { SUCCESS, CRASHED, FAILED_TO_START, KILLED }
@Serializable data class RunStatus(val outcome: RunOutcome, val atMs: Long, val stage: String? = null, val exitCode: Int? = null, val summary: String? = null)
/** Remembers a working renderer for a specific configuration fingerprint (version + components + device). */
@Serializable data class RendererMemo(val rendererId: String, val fingerprint: String, val atMs: Long)

@Serializable
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val versionId: String,                       // base Minecraft version
    val loader: LoaderSpec? = null,
    val launchVersionId: String? = null,         // installed loader version id (inheritsFrom base)
    val runtimeId: String? = null,               // null = automatic
    val rendererId: String = "auto",
    val rendererOptions: Map<String, String> = emptyMap(),
    val memoryMb: Int? = null,                   // null = recommended
    val jvmArgs: String = "",
    val gameArgs: String = "",
    val gameDirMode: GameDirMode = GameDirMode.ISOLATED,
    val customGameDir: String? = null,
    val controlLayoutId: String = "default-touch",
    val renderScale: Float = 1.0f,
    val resolutionWidth: Int? = null,
    val resolutionHeight: Int? = null,
    val fullscreen: Boolean = false,
    val env: Map<String, String> = emptyMap(),
    val iconKey: String = "grass",
    val installationState: String = "existing",
    val installationError: String? = null,

    val createdAtMs: Long = System.currentTimeMillis(),
    val lastPlayedAtMs: Long? = null,
    val lastRun: RunStatus? = null,
    val rendererMemo: RendererMemo? = null,
) {
    val effectiveVersionId get() = launchVersionId ?: versionId

    fun gameDir(dirs: LauncherDirs): Path = when (gameDirMode) {
        GameDirMode.SHARED -> dirs.sharedGameDir
        GameDirMode.ISOLATED -> dirs.instances.resolve(LauncherDirs.checkId(id))
        GameDirMode.CUSTOM -> Paths.get(customGameDir ?: error("Custom game directory not set"))
    }
}

data class ValidationProblem(val field: String, val message: String)

object ProfileValidator {
    private val ENV_KEY = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
    fun validate(p: Profile): List<ValidationProblem> {
        val out = mutableListOf<ValidationProblem>()
        runCatching { LauncherDirs.checkId(p.id) }.onFailure { out += ValidationProblem("id", "Invalid profile identifier") }
        if (p.name.isBlank()) out += ValidationProblem("name", "Profile name cannot be empty")
        if (p.name.length > 64) out += ValidationProblem("name", "Profile name is longer than 64 characters")
        runCatching { LauncherDirs.checkId(p.versionId) }.onFailure { out += ValidationProblem("versionId", "Invalid version id") }
        p.memoryMb?.let { if (it < 512) out += ValidationProblem("memoryMb", "At least 512 MB is required") }
        if (p.gameDirMode == GameDirMode.CUSTOM && p.customGameDir.isNullOrBlank()) out += ValidationProblem("customGameDir", "Choose a game directory")
        if ((p.resolutionWidth == null) != (p.resolutionHeight == null)) out += ValidationProblem("resolution", "Both resolution dimensions are required")
        if (p.resolutionWidth != null && p.resolutionWidth !in 320..7680) out += ValidationProblem("resolution", "Width must be 320–7680")
        if (p.resolutionHeight != null && p.resolutionHeight !in 240..4320) out += ValidationProblem("resolution", "Height must be 240–4320")
        if (p.renderScale !in 0.25f..1.0f) out += ValidationProblem("renderScale", "Render scale must be between 25% and 100%")
        p.env.keys.filterNot { ENV_KEY.matches(it) }.forEach { out += ValidationProblem("env", "Invalid environment variable name '$it'") }
        JvmArgs.validate(JvmArgs.tokenize(p.jvmArgs)).filter { it.severity == JvmArgs.Severity.ERROR }.forEach { out += ValidationProblem("jvmArgs", it.message) }
        return out
    }
}

/** Tokenizes and validates user JVM arguments so invalid flags are reported before the JVM refuses to start. */
object JvmArgs {
    enum class Severity { INFO, WARNING, ERROR }
    data class Finding(val arg: String, val severity: Severity, val message: String)

    fun tokenize(s: String): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder(); var quote: Char? = null; var any = false
        for (c in s) {
            when {
                quote != null -> if (c == quote) quote = null else cur.append(c)
                c == '"' || c == '\'' -> { quote = c; any = true }
                c.isWhitespace() -> { if (cur.isNotEmpty() || any) { out += cur.toString(); cur.clear(); any = false } }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty() || any) out += cur.toString()
        return out
    }

    fun validate(args: List<String>): List<Finding> = args.map { a ->
        when {
            a == "-cp" || a == "-classpath" || a.startsWith("-Djava.class.path") -> Finding(a, Severity.ERROR, "The classpath is managed by the launcher")
            a == "-jar" -> Finding(a, Severity.ERROR, "-jar is not supported; the launcher starts the version's main class")
            a.startsWith("-Xmx") || a.startsWith("-Xms") -> Finding(a, Severity.WARNING, "Memory flags override the profile memory setting")
            a.startsWith("-Djava.library.path") -> Finding(a, Severity.WARNING, "Overriding java.library.path can break native loading")
            a.startsWith("-javaagent") || a.startsWith("-agentpath") || a.startsWith("-agentlib") -> Finding(a, Severity.WARNING, "Java agents run arbitrary code inside the game")
            a.startsWith("-XX:") || a.startsWith("-D") || a.startsWith("-X") || a.startsWith("--add-") || a.startsWith("-verbose") || a.startsWith("--enable-") -> Finding(a, Severity.INFO, "ok")
            a.startsWith("-") -> Finding(a, Severity.WARNING, "Unrecognised JVM option; the JVM may refuse to start")
            else -> Finding(a, Severity.ERROR, "'$a' is not a JVM option (did you mean a game argument?)")
        }
    }.filter { it.severity != Severity.INFO }
}

@Serializable data class ProfilesFile(val schemaVersion: Int = ProfileStore.SCHEMA, val selectedId: String? = null, val profiles: List<Profile> = emptyList())

/** What a profile deletion will remove; presented to the user before anything happens. */
data class DeletionPlan(val profile: Profile, val removesConfigOnly: Boolean, val gameDir: Path?, val gameDirBytes: Long, val sharedWithOthers: Boolean)

class ProfileStore(private val file: Path, private val dirs: LauncherDirs) {
    companion object { const val SCHEMA = 2 }
    private val lock = Any()

    fun load(): ProfilesFile = synchronized(lock) {
        if (!Files.isRegularFile(file)) return ProfilesFile()
        val root = parseJsonObject(pro.cslauncher.core.util.FsUtil.readText(file), "profiles.json")
        val migrated = migrate(root)
        CsJson.decodeFromJsonElement(ProfilesFile.serializer(), migrated)
    }

    /** v1 stored "memory" as a string like "2G" and "renderer" instead of "rendererId". */
    internal fun migrate(root: JsonObject): JsonObject {
        val v = root.int("schemaVersion") ?: 1
        if (v >= SCHEMA) return root
        val profiles = (root.arr("profiles") ?: JsonArray(emptyList())).map { el ->
            val o = el.jsonObject.toMutableMap()
            o["memory"]?.let { m ->
                val s = m.jsonPrimitive.content.uppercase()
                val mb = when { s.endsWith("G") -> s.dropLast(1).toDoubleOrNull()?.times(1024)?.toInt(); s.endsWith("M") -> s.dropLast(1).toIntOrNull(); else -> s.toIntOrNull() }
                o.remove("memory"); if (mb != null) o["memoryMb"] = JsonPrimitive(mb)
            }
            o["renderer"]?.let { o.remove("renderer"); o["rendererId"] = it }
            JsonObject(o)
        }
        return JsonObject(root + mapOf("schemaVersion" to JsonPrimitive(SCHEMA), "profiles" to JsonArray(profiles)))
    }

    fun save(pf: ProfilesFile) = synchronized(lock) {
        backup()
        FsUtil.writeAtomic(file, CsJson.encodeToString(ProfilesFile.serializer(), pf.copy(schemaVersion = SCHEMA)))
    }

    fun upsert(p: Profile): ProfilesFile = synchronized(lock) {
        val problems = ProfileValidator.validate(p)
        require(problems.isEmpty()) { problems.joinToString("; ") { it.message } }
        val pf = load()
        val list = pf.profiles.filterNot { it.id == p.id } + p
        val next = pf.copy(profiles = list.sortedBy { it.createdAtMs }, selectedId = pf.selectedId ?: p.id)
        save(next); next
    }

    fun select(id: String) = synchronized(lock) { val pf = load(); require(pf.profiles.any { it.id == id }); save(pf.copy(selectedId = id)) }

    fun duplicate(id: String, newName: String): Profile = synchronized(lock) {
        val src = load().profiles.first { it.id == id }
        val copy = src.copy(id = UUID.randomUUID().toString(), name = newName, createdAtMs = System.currentTimeMillis(), lastPlayedAtMs = null, lastRun = null, rendererMemo = null,
            // An isolated duplicate gets its own (empty) directory; shared/custom keep pointing to the same place.
            gameDirMode = src.gameDirMode)
        upsert(copy); copy
    }

    fun planDeletion(id: String): DeletionPlan {
        val pf = load(); val p = pf.profiles.first { it.id == id }
        val gd = runCatching { p.gameDir(dirs) }.getOrNull()
        val shared = pf.profiles.any { it.id != id && runCatching { it.gameDir(dirs) }.getOrNull() == gd }
        return DeletionPlan(p, true, gd, gd?.let { FsUtil.sizeOf(it) } ?: 0, shared)
    }

    /** Deletes the profile configuration. The game directory is only removed when [deleteGameDir] is explicitly true,
     *  it is an isolated launcher-owned instance and no other profile uses it. */
    fun delete(id: String, deleteGameDir: Boolean) = synchronized(lock) {
        val plan = planDeletion(id)
        val pf = load()
        save(pf.copy(profiles = pf.profiles.filterNot { it.id == id }, selectedId = if (pf.selectedId == id) pf.profiles.firstOrNull { it.id != id }?.id else pf.selectedId))
        if (deleteGameDir && plan.profile.gameDirMode == GameDirMode.ISOLATED && !plan.sharedWithOthers && plan.gameDir != null)
            FsUtil.deleteTreeWithin(dirs.instances, plan.gameDir)
    }

    fun export(id: String): String {
        val p = load().profiles.first { it.id == id }
        val o = CsJson.encodeToJsonElement(Profile.serializer(), p.copy(lastRun = null, rendererMemo = null, lastPlayedAtMs = null)).jsonObject
        return CsJson.encodeToString(JsonObject.serializer(), JsonObject(mapOf("format" to JsonPrimitive("cslauncher-profile"), "formatVersion" to JsonPrimitive(1), "profile" to o)))
    }

    fun import(text: String): Profile {
        val root = parseJsonObject(text, "Profile export")
        require(root.str("format") == "cslauncher-profile") { "Not a CS Launcher Pro profile export" }
        require((root.int("formatVersion") ?: 0) in 1..1) { "Unsupported profile export version" }
        val p = CsJson.decodeFromJsonElement(Profile.serializer(), root.obj("profile") ?: error("Missing profile"))
        // New identity; custom directories from another device are not trusted.
        val imported = p.copy(id = UUID.randomUUID().toString(), createdAtMs = System.currentTimeMillis(),
            gameDirMode = if (p.gameDirMode == GameDirMode.CUSTOM) GameDirMode.ISOLATED else p.gameDirMode, customGameDir = null)
        upsert(imported); return imported
    }

    // ---- backups of the profile configuration (not game data) ----
    private val backupDir get() = dirs.backups.resolve("profiles")
    private fun backup() {
        if (!Files.isRegularFile(file)) return
        Files.createDirectories(backupDir)
        Files.copy(file, backupDir.resolve("profiles-${System.currentTimeMillis()}.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        listBackups().drop(15).forEach { Files.deleteIfExists(it) }
    }
    fun listBackups(): List<Path> = if (!Files.isDirectory(backupDir)) emptyList() else
        Files.list(backupDir).use { s -> s.filter { it.fileName.toString().startsWith("profiles-") }.collect(java.util.stream.Collectors.toList()) }.sortedByDescending { it.fileName.toString() }
    fun restoreBackup(backup: Path) = synchronized(lock) {
        require(FsUtil.isInside(backupDir, backup)) { "Not a profile backup" }
        val text = pro.cslauncher.core.util.FsUtil.readText(backup)
        CsJson.decodeFromJsonElement(ProfilesFile.serializer(), migrate(parseJsonObject(text, "backup"))) // validate first
        backup(); FsUtil.writeAtomic(file, text)
    }
}
