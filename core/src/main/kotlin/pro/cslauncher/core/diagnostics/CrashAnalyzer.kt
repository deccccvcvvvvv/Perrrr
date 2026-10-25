package pro.cslauncher.core.diagnostics

import pro.cslauncher.core.launch.Log4jXml
import pro.cslauncher.core.util.Redactor
import java.nio.file.Files
import java.nio.file.Path

enum class FailureKind(val title: String) {
    MISSING_RUNTIME("Java runtime missing"), WRONG_JAVA_VERSION("Wrong Java version"), INCOMPLETE_CLIENT("Incomplete game installation"),
    MISSING_LIBRARY("Missing library"), CORRUPT_FILE("Corrupt downloaded file"), MISSING_NATIVE("Missing native library"),
    UNSUPPORTED_RENDERER("Unsupported renderer"), GRAPHICS_INIT("Graphics initialisation failed"), MISSING_MOD_DEPENDENCY("Missing mod dependency"),
    INCOMPATIBLE_MOD("Incompatible mod"), INVALID_JVM_ARGUMENT("Invalid JVM argument"), INSUFFICIENT_STORAGE("Insufficient storage"),
    OUT_OF_MEMORY("Out of memory"), INCOMPATIBLE_SHADER("Incompatible shader pack"), AUTHENTICATION("Authentication failed"),
    DIRECTORY_PERMISSION("Game directory not writable"), PROCESS_KILLED("Game process terminated"), NATIVE_CRASH("Native crash"), UNKNOWN("Unknown failure")
}

/** Repairs the launcher may perform. Only launcher-owned, regenerable data is ever touched automatically. */
sealed class RepairAction(val description: String, val destructive: Boolean) {
    class RedownloadFiles(val reason: String) : RepairAction("Verify the installation and re-download only missing or corrupt files ($reason)", false)
    object RebuildNatives : RepairAction("Rebuild the launcher-generated natives directory", false)
    object RemoveTempFiles : RepairAction("Remove unfinished temporary downloads (*.part)", false)
    class SuggestRuntime(val major: Int) : RepairAction("Install or select Java $major for this profile", false)
    class SuggestRenderer(val reason: String) : RepairAction("Choose a different renderer ($reason)", false)
    class SuggestMemory(val mb: Int) : RepairAction("Change memory allocation to $mb MB", false)
    class InstallMod(val modId: String) : RepairAction("Install the missing mod '$modId'", false)
    class DisableMod(val hint: String) : RepairAction("Disable the incompatible mod ($hint) — requires your confirmation", true)
    class RemoveJvmArgument(val arg: String) : RepairAction("Remove the JVM argument '$arg' from the profile — requires your confirmation", true)
    object SignInAgain : RepairAction("Sign in to the account again", false)
    object FreeStorage : RepairAction("Free storage space (Storage Manager)", false)
}

data class Diagnosis(val kind: FailureKind, val confidence: Double, val explanation: String, val evidence: List<String>, val repairs: List<RepairAction>)

/** Rule-based, offline crash classification over game output, crash reports and JVM error logs. */
object CrashAnalyzer {
    private data class RuleDef(val kind: FailureKind, val pattern: Regex, val confidence: Double, val explain: (MatchResult) -> String, val repairs: (MatchResult) -> List<RepairAction>)

    private val rules = listOf(
        RuleDef(FailureKind.WRONG_JAVA_VERSION, Regex("UnsupportedClassVersionError.*class file version (\\d+)"), 0.95,
            { m -> "The game or a mod was compiled for Java ${m.groupValues[1].toInt() - 44}, newer than the selected runtime." },
            { m -> listOf(RepairAction.SuggestRuntime(m.groupValues[1].toInt() - 44)) }),
        RuleDef(FailureKind.WRONG_JAVA_VERSION, Regex("requires Java (\\d+)|Java (\\d+) or (?:newer|higher) is required"), 0.8,
            { m -> "A component requires Java ${m.groupValues.drop(1).first { it.isNotEmpty() }}." }, { m -> listOf(RepairAction.SuggestRuntime(m.groupValues.drop(1).first { it.isNotEmpty() }.toInt())) }),
        RuleDef(FailureKind.OUT_OF_MEMORY, Regex("java\\.lang\\.OutOfMemoryError(: (.+))?"), 0.95,
            { m -> "The Java heap ran out of memory (${m.groupValues[2].ifEmpty { "heap" }})." }, { listOf(RepairAction.SuggestMemory(0)) }),
        RuleDef(FailureKind.OUT_OF_MEMORY, Regex("Could not reserve enough space for .*object heap|Native memory allocation \\(mmap\\) failed"), 0.9,
            { "The JVM could not reserve the requested memory. The allocation is too large for this device." }, { listOf(RepairAction.SuggestMemory(-1)) }),
        RuleDef(FailureKind.INVALID_JVM_ARGUMENT, Regex("Unrecognized (?:VM )?option '([^']+)'|Unrecognized option: (\\S+)|Invalid maximum heap size: (\\S+)"), 0.95,
            { m -> "The JVM rejected the argument '${m.groupValues.drop(1).first { it.isNotEmpty() }}'." }, { m -> listOf(RepairAction.RemoveJvmArgument(m.groupValues.drop(1).first { it.isNotEmpty() })) }),
        RuleDef(FailureKind.MISSING_NATIVE, Regex("UnsatisfiedLinkError:? (.{0,160})|Failed to locate library: (\\S+)|dlopen failed: (.{0,160})"), 0.85,
            { m -> "A native library could not be loaded: ${m.groupValues.drop(1).first { it.isNotEmpty() }.trim()}" }, { listOf(RepairAction.RebuildNatives, RepairAction.SuggestRenderer("native library failed to load")) }),
        RuleDef(FailureKind.GRAPHICS_INIT, Regex("GLFW error (\\d+)|Failed to create (?:the )?(?:OpenGL )?context|No OpenGL context found|EGL_BAD_\\w+|eglCreateContext failed|OSMesaCreateContext failed|WGL: The driver does not appear to support OpenGL"), 0.85,
            { m -> "The renderer could not create a graphics context (${m.value.take(80)})." }, { listOf(RepairAction.SuggestRenderer("context creation failed")) }),
        RuleDef(FailureKind.UNSUPPORTED_RENDERER, Regex("OpenGL 3\\.2|GL_ARB_|requires OpenGL (\\d\\.\\d)|Unsupported OpenGL version|GLX: Failed to create context: GLXBadFBConfig"), 0.75,
            { "The game needs OpenGL features that the selected renderer does not provide." }, { listOf(RepairAction.SuggestRenderer("missing OpenGL features")) }),
        RuleDef(FailureKind.INCOMPATIBLE_SHADER, Regex("(?i)(iris|oculus|optifine).{0,80}(shader).{0,80}(fail|error|compile)|Shader compilation failed|Failed to (?:compile|link) program"), 0.7,
            { "A shader pack failed to compile on this renderer/driver." }, { listOf(RepairAction.SuggestRenderer("shader compilation failed")) }),
        RuleDef(FailureKind.MISSING_MOD_DEPENDENCY, Regex("requires (?:any )?(?:version(?: .{0,60}?)? of )?(?:mod )?'?([a-z0-9_\\-]+)'?,? which is missing|Mod ID: '([a-z0-9_\\-]+)',? Requested by|Missing or unsupported mandatory dependencies:\\s*Mod ID: '([a-z0-9_\\-]+)'"), 0.85,
            { m -> "A mod requires '${m.groupValues.drop(1).last { it.isNotEmpty() }}', which is not installed." }, { m -> listOf(RepairAction.InstallMod(m.groupValues.drop(1).last { it.isNotEmpty() })) }),
        RuleDef(FailureKind.INCOMPATIBLE_MOD, Regex("Incompatible mods? found|Mixin apply (?:for mod )?([a-z0-9_\\-]+)? ?failed|MixinApplyError|is incompatible with|Duplicate mods? found"), 0.75,
            { m -> "A mod is incompatible with this game version or with another mod${m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""}." },
            { m -> listOf(RepairAction.DisableMod(m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "see evidence")) }),
        RuleDef(FailureKind.CORRUPT_FILE, Regex("ZipException|invalid (?:LOC|CEN) header|zip END header not found|java\\.util\\.zip\\.ZipError|Invalid or corrupt jarfile|error in opening zip file"), 0.85,
            { "A downloaded archive is corrupt." }, { listOf(RepairAction.RedownloadFiles("corrupt archive"), RepairAction.RemoveTempFiles) }),
        RuleDef(FailureKind.MISSING_LIBRARY, Regex("ClassNotFoundException: (net\\.minecraft\\.|com\\.mojang\\.|org\\.lwjgl\\.|io\\.netty|com\\.google)\\S*|NoClassDefFoundError: (net/minecraft|com/mojang|org/lwjgl|io/netty|com/google)\\S*"), 0.8,
            { m -> "A required game class is missing (${m.value.substringAfter(": ").take(80)})." }, { listOf(RepairAction.RedownloadFiles("missing classes")) }),
        RuleDef(FailureKind.INCOMPLETE_CLIENT, Regex("Error: Could not find or load main class (\\S+)"), 0.9,
            { m -> "The main class ${m.groupValues[1]} was not found: the client jar or loader libraries are missing." }, { listOf(RepairAction.RedownloadFiles("main class missing")) }),
        RuleDef(FailureKind.INSUFFICIENT_STORAGE, Regex("No space left on device|ENOSPC|There is not enough space on the disk"), 0.95,
            { "The device ran out of storage." }, { listOf(RepairAction.FreeStorage, RepairAction.RemoveTempFiles) }),
        RuleDef(FailureKind.DIRECTORY_PERMISSION, Regex("AccessDeniedException|Permission denied|EACCES|Read-only file system"), 0.75,
            { "The game could not write to its directory." }, { emptyList() }),
        RuleDef(FailureKind.AUTHENTICATION, Regex("Invalid session|Failed to (?:login|verify username)|Not authenticated with Minecraft\\.net|Multiplayer is disabled"), 0.8,
            { "The account session is invalid or expired." }, { listOf(RepairAction.SignInAgain) }),
        RuleDef(FailureKind.NATIVE_CRASH, Regex("A fatal error has been detected by the Java Runtime Environment|SIGSEGV|SIGBUS|SIGABRT|Fatal signal \\d+"), 0.7,
            { "A native component crashed (driver, renderer or JVM)." }, { listOf(RepairAction.SuggestRenderer("native crash")) }),
    )

    /** Lines vanilla logs during every normal start on some hosts; they never explain a failure on their own. */
    private val benign = Regex("fliteWrapper|libflite|Narrator library|text2speech|Failed to open OpenAL device|Realms server|/player/attributes|Failed to fetch user properties")

    /** Analyses text and returns diagnoses ordered by confidence. Input is redacted before evidence is kept. */
    fun analyze(text: String, exitCode: Int? = null): List<Diagnosis> {
        val lines = text.lineSequence().map { Redactor.redact(Log4jXml.flattenText(it)) }.toList()
        val found = LinkedHashMap<FailureKind, Diagnosis>()
        for (r in rules) {
            for ((i, l) in lines.withIndex()) {
                if (benign.containsMatchIn(l)) continue
                val m = r.pattern.find(l) ?: continue
                if (found[r.kind]?.let { it.confidence >= r.confidence } == true) break
                val ev = lines.subList(maxOf(0, i - 1), minOf(lines.size, i + 3)).map { it.take(300) }
                found[r.kind] = Diagnosis(r.kind, r.confidence, r.explain(m), ev, r.repairs(m))
                break
            }
        }
        if (found.isEmpty()) {
            val kind = when (exitCode) { null, 0 -> null; 137, -9 -> FailureKind.PROCESS_KILLED; 134, 139 -> FailureKind.NATIVE_CRASH; else -> FailureKind.UNKNOWN }
            if (kind != null) {
                val tail = lines.takeLast(12)
                val explain = if (kind == FailureKind.PROCESS_KILLED) "The game was terminated (exit $exitCode). Android may have killed it for using too much memory."
                              else "The game exited with code $exitCode. No known error pattern matched; see the log excerpt."
                found[kind] = Diagnosis(kind, 0.3, explain, tail, if (kind == FailureKind.PROCESS_KILLED) listOf(RepairAction.SuggestMemory(-1)) else emptyList())
            }
        }
        return found.values.sortedByDescending { it.confidence }
    }

    /** Collects the newest crash report and the session log for analysis (redacted, size-bounded). */
    fun collectEvidence(gameDir: Path, sessionLog: Path?, maxBytes: Int = 512 * 1024): String = buildString {
        sessionLog?.takeIf { Files.isRegularFile(it) }?.let { append(tail(it, maxBytes)); append('\n') }
        val crashes = gameDir.resolve("crash-reports")
        if (Files.isDirectory(crashes)) Files.list(crashes).use { s -> s.filter { it.fileName.toString().endsWith(".txt") }.collect(java.util.stream.Collectors.toList()) }
            .maxByOrNull { Files.getLastModifiedTime(it) }?.let { if (sessionLog == null || Files.getLastModifiedTime(it) >= Files.getLastModifiedTime(sessionLog).let { t -> java.nio.file.attribute.FileTime.fromMillis(t.toMillis() - 3_600_000) }) append(tail(it, maxBytes / 2)) }
        Files.list(gameDir).use { s -> s.filter { it.fileName.toString().startsWith("hs_err_pid") }.collect(java.util.stream.Collectors.toList()) }.maxByOrNull { Files.getLastModifiedTime(it) }?.let { append('\n'); append(tail(it, 64 * 1024)) }
    }.let { Redactor.redact(it) }

    private fun tail(p: Path, max: Int): String {
        val size = Files.size(p)
        Files.newByteChannel(p).use { ch -> if (size > max) ch.position(size - max); val buf = java.nio.ByteBuffer.allocate(minOf(size, max.toLong()).toInt()); ch.read(buf); return String(buf.array(), 0, buf.position(), Charsets.UTF_8) }
    }
}
