package pro.cslauncher.core.runtime

import kotlinx.serialization.Serializable
import pro.cslauncher.core.install.*
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.platform.PlatformInfo.Arch
import pro.cslauncher.core.util.*
import java.nio.file.Files
import java.nio.file.Path

enum class RuntimeSource { BUNDLED, IMPORTED, DOWNLOADED, HOST }

data class JavaRuntime(
    val id: String, val home: Path, val major: Int, val version: String, val arch: Arch,
    val vendor: String?, val source: RuntimeSource, val isAndroidBuild: Boolean,
) {
    /** Desktop launch executable (not used on Android: app-data executables are blocked; libjvm is loaded via JNI). */
    val javaExecutable: Path get() = home.resolve("bin").resolve(if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")

    /** Locates libjvm for in-process launching. Java 8 builds use lib/<arch>/server, 9+ use lib/server. */
    fun libjvm(): Path? {
        val archDirs = when (arch) { Arch.ARM64 -> listOf("aarch64", "arm64"); Arch.ARM32 -> listOf("aarch32", "arm"); Arch.X86 -> listOf("i386", "x86"); Arch.X86_64 -> listOf("amd64", "x86_64") }
        val candidates = listOf("lib/server", "lib/client", "jre/lib/server") + archDirs.flatMap { listOf("lib/$it/server", "jre/lib/$it/server", "lib/$it/client", "jre/lib/$it/client") }
        return candidates.map { home.resolve(it).resolve("libjvm.so") }.firstOrNull { Files.isRegularFile(it) }
    }
    val is64Bit get() = arch.bits == 64
}

object RuntimeProbe {
    /** Reads the standard JDK "release" file. Returns null if [home] is not a Java runtime. */
    fun probe(home: Path, source: RuntimeSource, isAndroidBuild: Boolean): JavaRuntime? {
        val release = home.resolve("release")
        if (!Files.isRegularFile(release)) return null
        val props = Files.readAllLines(release).mapNotNull { l ->
            val i = l.indexOf('='); if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim().trim('"')
        }.toMap()
        val ver = props["JAVA_VERSION"] ?: return null
        val major = parseMajor(ver) ?: return null
        val arch = props["OS_ARCH"]?.let { Arch.fromJvm(it) } ?: return null
        return JavaRuntime(home.fileName.toString(), home, major, ver, arch, props["IMPLEMENTOR"], source, isAndroidBuild)
    }
    fun parseMajor(v: String): Int? {
        val s = v.trim()
        if (s.startsWith("1.")) return s.substring(2).takeWhile { it.isDigit() }.toIntOrNull()
        return s.takeWhile { it.isDigit() }.toIntOrNull()
    }
}

enum class Compatibility { EXACT, COMPATIBLE, RISKY, INCOMPATIBLE }

data class RuntimeChoice(val runtime: JavaRuntime?, val compatibility: Compatibility, val reason: String, val requiredMajor: Int)

/**
 * Chooses a runtime for a game version. Policy:
 *  - exact major preferred;
 *  - LWJGL2-era versions (Java 8) only accept Java 8 (newer JVMs break LWJGL2/launchwrapper);
 *  - Java 8 LWJGL3 versions (1.13-1.16.5) may use 17 with a warning;
 *  - Java 16 -> 17 accepted; 17 -> 17 or 21; 21 -> 21+ ; never older than required.
 */
object RuntimeSelector {
    fun evaluate(required: Int, usesLwjgl3: Boolean, rt: JavaRuntime, platform: PlatformInfo): Pair<Compatibility, String> {
        if (rt.arch != platform.arch) {
            val ok32on64 = platform.arch == Arch.ARM64 && rt.arch == Arch.ARM32
            if (!ok32on64) return Compatibility.INCOMPATIBLE to "Runtime is ${rt.arch.mojangName}, device is ${platform.arch.mojangName}"
        }
        if (platform.isAndroid && !rt.isAndroidBuild) return Compatibility.INCOMPATIBLE to "Not an Android (bionic) build of Java"
        if (rt.major < required) return Compatibility.INCOMPATIBLE to "Requires Java $required, runtime is Java ${rt.major}"
        if (rt.major == required) return Compatibility.EXACT to "Java ${rt.major} matches the version requirement"
        return when {
            required <= 8 && !usesLwjgl3 -> Compatibility.INCOMPATIBLE to "LWJGL 2 era versions need Java 8"
            required <= 8 -> Compatibility.RISKY to "Requires Java 8; Java ${rt.major} usually works for vanilla but some mods fail"
            required == 16 && rt.major == 17 -> Compatibility.COMPATIBLE to "Java 17 is compatible with Java 16 requirements"
            rt.major - required <= 4 -> Compatibility.COMPATIBLE to "Newer Java ${rt.major} is normally compatible"
            else -> Compatibility.RISKY to "Java ${rt.major} is much newer than required Java $required"
        }
    }

    fun select(required: Int, usesLwjgl3: Boolean, runtimes: List<JavaRuntime>, platform: PlatformInfo, preferredId: String? = null): RuntimeChoice {
        if (preferredId != null) {
            val rt = runtimes.firstOrNull { it.id == preferredId }
                ?: return RuntimeChoice(null, Compatibility.INCOMPATIBLE, "Selected runtime '$preferredId' is not installed", required)
            val (c, why) = evaluate(required, usesLwjgl3, rt, platform)
            return RuntimeChoice(rt.takeIf { c != Compatibility.INCOMPATIBLE }, c, why, required)
        }
        val scored = runtimes.map { it to evaluate(required, usesLwjgl3, it, platform) }.filter { it.second.first != Compatibility.INCOMPATIBLE }
        val best = scored.minWithOrNull(compareBy({ it.second.first.ordinal }, { it.first.major - required }, { if (it.first.arch == platform.arch) 0 else 1 }))
            ?: return RuntimeChoice(null, Compatibility.INCOMPATIBLE,
                if (runtimes.isEmpty()) "No Java runtime is installed. Install Java $required in Runtime Manager."
                else "No installed runtime satisfies Java $required (installed: ${runtimes.joinToString { "Java ${it.major} ${it.arch.mojangName}" }})", required)
        return RuntimeChoice(best.first, best.second.first, best.second.second, required)
    }

    /** Java requirement when metadata predates the "javaVersion" field. */
    fun requiredMajorFor(javaMajor: Int?): Int = javaMajor ?: 8
}

@Serializable data class RuntimeInstallRecord(val id: String, val source: String, val sha256: String?, val installedAtMs: Long, val androidBuild: Boolean)

/** Manages runtimes under [LauncherDirs.runtimeRoot]. */
class RuntimeManager(private val dirs: LauncherDirs, private val platform: PlatformInfo, private val extraHomes: List<Path> = emptyList()) {
    fun list(): List<JavaRuntime> {
        val found = mutableListOf<JavaRuntime>()
        if (Files.isDirectory(dirs.runtimeRoot)) Files.list(dirs.runtimeRoot).use { s ->
            s.filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }.forEach { home ->
                val rec = readRecord(home)
                RuntimeProbe.probe(home, if (rec?.source == "bundled") RuntimeSource.BUNDLED else RuntimeSource.IMPORTED, rec?.androidBuild ?: platform.isAndroid)?.let { found += it }
            }
        }
        extraHomes.forEach { h -> RuntimeProbe.probe(h, RuntimeSource.HOST, platform.isAndroid)?.let { found += it.copy(id = "host-" + it.major) } }
        return found.sortedBy { it.major }
    }

    private fun readRecord(home: Path): RuntimeInstallRecord? = runCatching {
        CsJson.decodeFromString(RuntimeInstallRecord.serializer(), pro.cslauncher.core.util.FsUtil.readText(home.resolve(".cs-runtime.json")))
    }.getOrNull()

    /**
     * Installs a runtime archive (.tar, .tar.gz or .zip). The archive is verified against [expectedSha256] when given,
     * extracted into a staging directory, probed, and only then moved into place.
     */
    fun install(archive: Path, id: String, expectedSha256: String?, source: String, androidBuild: Boolean = platform.isAndroid): JavaRuntime {
        LauncherDirs.checkId(id)
        if (expectedSha256 != null) {
            val actual = Hashing.sha256(archive)
            require(actual.equals(expectedSha256, true)) { "Runtime archive checksum mismatch (expected $expectedSha256, got $actual)" }
        }
        val staging = dirs.runtimeRoot.resolve(".staging-$id")
        if (Files.exists(staging)) FsUtil.deleteTreeWithin(dirs.runtimeRoot, staging)
        val name = archive.fileName.toString().lowercase()
        if (name.endsWith(".zip")) SafeZip.extract(archive, staging) else SafeTar.extract(archive, staging)
        // Accept archives with a single top-level directory.
        val home = findHome(staging) ?: run { FsUtil.deleteTreeWithin(dirs.runtimeRoot, staging); throw IllegalArgumentException("Archive does not contain a Java runtime (no 'release' file)") }
        val probed = RuntimeProbe.probe(home, RuntimeSource.IMPORTED, androidBuild)!!
        if (probed.arch != platform.arch && !(platform.arch == PlatformInfo.Arch.ARM64 && probed.arch == PlatformInfo.Arch.ARM32))
            { FsUtil.deleteTreeWithin(dirs.runtimeRoot, staging); throw IllegalArgumentException("Runtime architecture ${probed.arch.mojangName} does not match device ${platform.arch.mojangName}") }
        val target = dirs.runtimeRoot.resolve(id)
        if (Files.exists(target)) FsUtil.deleteTreeWithin(dirs.runtimeRoot, target)
        Files.move(home, target)
        if (Files.exists(staging)) FsUtil.deleteTreeWithin(dirs.runtimeRoot, staging)
        FsUtil.writeAtomic(target.resolve(".cs-runtime.json"), CsJson.encodeToString(RuntimeInstallRecord.serializer(),
            RuntimeInstallRecord(id, source, expectedSha256, System.currentTimeMillis(), androidBuild)))
        return RuntimeProbe.probe(target, RuntimeSource.IMPORTED, androidBuild)!!
    }

    private fun findHome(dir: Path): Path? {
        if (Files.isRegularFile(dir.resolve("release"))) return dir
        return Files.list(dir).use { s -> s.filter { Files.isDirectory(it) }.collect(java.util.stream.Collectors.toList()) }.firstNotNullOfOrNull { sub -> if (Files.isRegularFile(sub.resolve("release"))) sub else null }
    }

    /** Removes a launcher-managed runtime. Refuses when a profile still references it. */
    fun remove(id: String, referencedBy: List<String>) {
        require(referencedBy.isEmpty()) { "Runtime $id is used by profiles: ${referencedBy.joinToString()}" }
        FsUtil.deleteTreeWithin(dirs.runtimeRoot, dirs.runtimeRoot.resolve(LauncherDirs.checkId(id)))
    }
}
