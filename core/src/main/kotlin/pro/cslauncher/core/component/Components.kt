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

    fun lwjgl(abi: String): InstalledComponent? = list().firstOrNull { it.manifest.abi == abi && it.lwjglVersion != null }

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
