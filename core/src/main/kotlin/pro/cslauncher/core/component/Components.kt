package pro.cslauncher.core.component

import kotlinx.serialization.Serializable
import pro.cslauncher.core.install.*
import pro.cslauncher.core.util.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Android native components (LWJGL 3 natives built for bionic, renderer libraries such as GL4ES / Mesa OSMesa+Zink /
 * ANGLE) are distributed as "component packs": a zip with `cs-component.json` and files listed with SHA-256.
 * Nothing is activated unless every listed file verifies. Licenses are recorded and shown in the UI.
 */
@Serializable data class ComponentFile(val path: String, val sha256: String, val size: Long)
@Serializable data class ComponentManifest(
    val formatVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    val kind: String,                     // "lwjgl", "renderer", "bridge"
    val abi: String,                      // e.g. "arm64-v8a"
    val license: String,
    val source: String,                   // where the binaries were built from
    val provides: List<String> = emptyList(), // e.g. ["lwjgl:3.3.3", "lib:libgl4es.so"]
    val classpathJars: List<String> = emptyList(),
    val libraryDir: String = "lib",
    val files: List<ComponentFile>,
)

data class InstalledComponent(val manifest: ComponentManifest, val dir: Path) {
    val libDir: Path get() = dir.resolve(manifest.libraryDir)
    fun libraries(): Map<String, Path> = if (!Files.isDirectory(libDir)) emptyMap() else
        Files.list(libDir).use { s -> s.filter { it.fileName.toString().endsWith(".so") }.collect(java.util.stream.Collectors.toList()) }.associateBy { it.fileName.toString() }
    val lwjglVersion get() = manifest.provides.firstOrNull { it.startsWith("lwjgl:") }?.removePrefix("lwjgl:")
    fun jars(): List<Path> = manifest.classpathJars.map { dir.resolve(it) }
}

class ComponentManager(private val dirs: LauncherDirs) {
    companion object { const val MANIFEST = "cs-component.json"; const val FORMAT = 1 }

    fun list(): List<InstalledComponent> = if (!Files.isDirectory(dirs.componentRoot)) emptyList() else
        Files.list(dirs.componentRoot).use { s -> s.filter { Files.isRegularFile(it.resolve(MANIFEST)) }.collect(java.util.stream.Collectors.toList()) }.mapNotNull { d ->
            runCatching { InstalledComponent(CsJson.decodeFromString(ComponentManifest.serializer(), pro.cslauncher.core.util.FsUtil.readText(d.resolve(MANIFEST))), d) }.getOrNull()
        }

    /** All native libraries available from installed components, by file name. */
    fun availableLibraries(abi: String): Map<String, Path> = list().filter { it.manifest.abi == abi }.flatMap { it.libraries().entries }.associate { it.key to it.value }

    /**
     * The LWJGL component to run a game built against [gameLwjgl] (e.g. "3.3.3", "3.4.1"): the oldest installed build
     * that is at least as new in major.minor (3.2/3.3 games -> 3.3.3, 26.x on 3.4 -> 3.4.3). Without a suitable one the
     * newest is returned and [LwjglCompat] explains the mismatch.
     */
    fun lwjgl(abi: String, gameLwjgl: String? = null): InstalledComponent? {
        val all = list().filter { it.manifest.abi == abi && it.lwjglVersion != null }.sortedBy { LwjglCompat.order(it.lwjglVersion!!) }
        if (gameLwjgl == null) return all.firstOrNull()
        val need = LwjglCompat.majorMinor(gameLwjgl)
        return all.firstOrNull { LwjglCompat.majorMinor(it.lwjglVersion!!) >= need } ?: all.lastOrNull()
    }

    fun verify(c: InstalledComponent): List<String> = c.manifest.files.mapNotNull { f ->
        val p = SafeZip.safeTarget(c.dir, f.path)
        Integrity(sha256 = f.sha256, size = f.size).check(p)?.let { "${f.path}: $it" }
    }

    /** Imports a component pack. Fails closed: staging is deleted when anything does not verify. */
    fun import(pack: Path, expectedAbi: String): InstalledComponent {
        val staging = dirs.componentRoot.resolve(".staging-${System.nanoTime()}")
        try {
            SafeZip.extract(pack, staging, maxTotalBytes = 1L shl 30)
            val mf = staging.resolve(MANIFEST)
            require(Files.isRegularFile(mf)) { "Not a CS Launcher Pro component pack ($MANIFEST missing)" }
            val m = CsJson.decodeFromString(ComponentManifest.serializer(), pro.cslauncher.core.util.FsUtil.readText(mf))
            require(m.formatVersion == FORMAT) { "Unsupported component format ${m.formatVersion}" }
            require(m.abi == expectedAbi) { "Component is built for ${m.abi}, this device needs $expectedAbi" }
            LauncherDirs.checkId(m.id)
            val c = InstalledComponent(m, staging)
            val problems = verify(c)
            require(problems.isEmpty()) { "Component verification failed: ${problems.take(3).joinToString()}" }
            // Reject executables or files that are not declared (no hidden payloads).
            val declared = m.files.map { it.path }.toSet() + MANIFEST
            Files.walk(staging).use { s -> s.filter { Files.isRegularFile(it) }.collect(java.util.stream.Collectors.toList()) }.forEach {
                val rel = staging.relativize(it).toString().replace('\\', '/')
                require(rel in declared) { "Undeclared file in component: $rel" }
            }
            val target = dirs.componentRoot.resolve(m.id)
            if (Files.exists(target)) FsUtil.deleteTreeWithin(dirs.componentRoot, target)
            Files.move(staging, target)
            return InstalledComponent(m, target)
        } finally {
            if (Files.exists(staging)) FsUtil.deleteTreeWithin(dirs.componentRoot, staging)
        }
    }

    fun remove(id: String) = FsUtil.deleteTreeWithin(dirs.componentRoot, dirs.componentRoot.resolve(LauncherDirs.checkId(id)))
}

/**
 * Checks whether the bundled Android LWJGL build can stand in for the LWJGL the game was built against.
 * Returns a user-facing reason when it cannot (e.g. Minecraft 26.x moved to LWJGL 3.4 with SDL3 instead of GLFW).
 */
object LwjglCompat {
    /** First LWJGL release whose Android build in this launcher includes the SDL3 bridge (libcsglfw.so + SDL natives). */
    const val SDL_BRIDGE_MIN = "3.4"

    fun majorMinor(v: String): Int = v.split('.', '-').take(2).map { it.toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 0 } shl 16) + it.getOrElse(1) { 0 } }
    /** Full numeric ordering key (major, minor, patch). */
    fun order(v: String): Long = v.split('.', '-').take(3).map { it.toLongOrNull() ?: 0L }.let { (it.getOrElse(0) { 0L } shl 32) + (it.getOrElse(1) { 0L } shl 16) + it.getOrElse(2) { 0L } }

    /** The game's LWJGL version from its library list (null for LWJGL 2 / unknown). */
    fun gameVersion(gameLibraries: List<pro.cslauncher.core.meta.MavenCoordinate>): String? =
        gameLibraries.firstOrNull { it.group == "org.lwjgl" && it.artifact == "lwjgl" }?.version

    /** Minecraft 26.3+ creates its window with SDL3 instead of GLFW. */
    fun usesSdl(gameLibraries: List<pro.cslauncher.core.meta.MavenCoordinate>): Boolean =
        gameLibraries.any { it.group == "org.lwjgl" && it.artifact == "lwjgl-sdl" }

    fun problem(gameLibraries: List<pro.cslauncher.core.meta.MavenCoordinate>, componentVersion: String?): String? {
        val lw = gameLibraries.filter { it.group == "org.lwjgl" }
        if (lw.isEmpty() || componentVersion == null) return null
        if (usesSdl(lw) && majorMinor(componentVersion) < majorMinor(SDL_BRIDGE_MIN))
            return "This Minecraft version uses LWJGL ${lw.first { it.artifact == "lwjgl-sdl" }.version} with SDL3 windowing. The LWJGL 3.4 Android build is not available for this device's CPU (bundled: LWJGL $componentVersion); use Minecraft 1.21.11 or older."
        val game = gameVersion(lw) ?: return null
        return if (majorMinor(game) > majorMinor(componentVersion)) "This Minecraft version needs LWJGL $game; the bundled Android LWJGL for this device's CPU is $componentVersion." else null
    }
}
