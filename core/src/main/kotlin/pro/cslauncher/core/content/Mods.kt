package pro.cslauncher.core.content

import kotlinx.serialization.json.*
import pro.cslauncher.core.profile.LoaderType
import pro.cslauncher.core.util.*
import java.nio.file.*
import java.util.zip.ZipFile

data class ModDependency(val modId: String, val versionRange: String, val required: Boolean)

data class ModInfo(
    val file: Path, val enabled: Boolean, val loaders: Set<LoaderType>, val modId: String?, val name: String, val version: String?,
    val description: String?, val minecraftRange: String?, val javaRange: String?, val dependencies: List<ModDependency>,
    val breaks: List<String>, val hasNativeCode: Boolean, val sizeBytes: Long, val metadataError: String? = null,
)

enum class Certainty { CERTAIN, LIKELY, UNKNOWN }
data class CompatFinding(val file: Path, val severity: String, val message: String, val certainty: Certainty)

/** Reads mod metadata (Fabric, Quilt, Forge/NeoForge mods.toml, legacy mcmod.info) without loading any code. */
object ModInspector {
    fun inspect(file: Path): ModInfo {
        val enabled = !file.fileName.toString().endsWith(".disabled")
        val size = runCatching { Files.size(file) }.getOrDefault(0)
        val base = ModInfo(file, enabled, emptySet(), null, file.fileName.toString().removeSuffix(".disabled").removeSuffix(".jar"), null, null, null, null, emptyList(), emptyList(), false, size)
        return try {
            ZipFile(file.toFile()).use { z ->
                val natives = z.entries().asSequence().any { val n = it.name.lowercase(); n.endsWith(".so") || n.endsWith(".dll") || n.endsWith(".dylib") }
                fun text(name: String) = z.getEntry(name)?.let { e -> z.getInputStream(e).use { it.readBytes().decodeToString() } }
                text("fabric.mod.json")?.let { return fabric(base, it, natives) }
                text("quilt.mod.json")?.let { return quilt(base, it, natives) }
                (text("META-INF/neoforge.mods.toml")?.let { it to LoaderType.NEOFORGE } ?: text("META-INF/mods.toml")?.let { it to LoaderType.FORGE })
                    ?.let { (t, l) -> return toml(base, t, l, natives) }
                text("mcmod.info")?.let { return mcmod(base, it, natives) }
                base.copy(hasNativeCode = natives, metadataError = "No recognised mod metadata")
            }
        } catch (e: Exception) { base.copy(metadataError = "Unreadable archive: ${e.message}") }
    }

    private fun depMap(o: JsonObject?, required: Boolean) = o?.map { (k, v) ->
        ModDependency(k, when (v) { is JsonPrimitive -> v.content; is JsonArray -> v.joinToString(" || ") { it.jsonPrimitive.content }; else -> "*" }, required)
    } ?: emptyList()

    private fun fabric(b: ModInfo, t: String, natives: Boolean): ModInfo {
        val o = CsJson.parseToJsonElement(t.replace("\n", " ")).jsonObject
        val deps = depMap(o.obj("depends"), true) + depMap(o.obj("recommends"), false)
        return b.copy(loaders = setOf(LoaderType.FABRIC, LoaderType.QUILT), modId = o.str("id"), name = o.str("name") ?: b.name, version = o.str("version"),
            description = o.str("description"), minecraftRange = deps.firstOrNull { it.modId == "minecraft" }?.versionRange,
            javaRange = deps.firstOrNull { it.modId == "java" }?.versionRange, dependencies = deps.filter { it.modId !in setOf("minecraft", "java") },
            breaks = o.obj("breaks")?.keys?.toList() ?: emptyList(), hasNativeCode = natives)
    }

    private fun quilt(b: ModInfo, t: String, natives: Boolean): ModInfo {
        val ql = CsJson.parseToJsonElement(t).jsonObject.obj("quilt_loader") ?: return b.copy(metadataError = "quilt.mod.json without quilt_loader")
        val deps = ql.arr("depends")?.mapNotNull { el ->
            when (el) { is JsonPrimitive -> ModDependency(el.content, "*", true); is JsonObject -> ModDependency(el.str("id") ?: return@mapNotNull null, el["versions"]?.toString()?.trim('"') ?: "*", el.bool("optional") != true); else -> null }
        } ?: emptyList()
        val meta = ql.obj("metadata")
        return b.copy(loaders = setOf(LoaderType.QUILT), modId = ql.str("id"), name = meta?.str("name") ?: b.name, version = ql.str("version"),
            description = meta?.str("description"), minecraftRange = deps.firstOrNull { it.modId == "minecraft" }?.versionRange,
            dependencies = deps.filter { it.modId != "minecraft" && it.modId != "java" }, hasNativeCode = natives)
    }

    /** Small TOML subset reader sufficient for mods.toml ([[mods]] and [[dependencies.x]] tables with key = "value"). */
    private fun toml(b: ModInfo, t: String, loader: LoaderType, natives: Boolean): ModInfo {
        var section = ""; val mods = mutableListOf<MutableMap<String, String>>(); val deps = mutableListOf<MutableMap<String, String>>()
        var cur: MutableMap<String, String>? = null
        for (raw in t.lines()) {
            val line = raw.substringBefore(" #").trim(); if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[[")) { section = line.trim('[', ']').trim(); cur = mutableMapOf(); if (section == "mods") mods += cur else if (section.startsWith("dependencies")) deps += cur.also { it["__owner"] = section.substringAfter('.') }; continue }
            if (line.startsWith("[")) { section = line.trim('[', ']'); cur = null; continue }
            val i = line.indexOf('='); if (i < 0 || cur == null) continue
            cur[line.substring(0, i).trim()] = line.substring(i + 1).trim().trim('"', '\'')
        }
        val m = mods.firstOrNull() ?: return b.copy(loaders = setOf(loader), metadataError = "mods.toml has no [[mods]] entry")
        val d = deps.map { ModDependency(it["modId"] ?: "?", it["versionRange"] ?: "*", (it["mandatory"] ?: it["type"]?.let { t2 -> (t2 == "required").toString() } ?: "true") == "true") }
        return b.copy(loaders = setOf(loader), modId = m["modId"], name = m["displayName"] ?: b.name, version = m["version"]?.takeUnless { it.contains("\${") },
            description = m["description"], minecraftRange = d.firstOrNull { it.modId == "minecraft" }?.versionRange,
            dependencies = d.filter { it.modId !in setOf("minecraft", "forge", "neoforge", "java") }, hasNativeCode = natives)
    }

    private fun mcmod(b: ModInfo, t: String, natives: Boolean): ModInfo {
        val el = runCatching { CsJson.parseToJsonElement(t) }.getOrNull() ?: return b.copy(loaders = setOf(LoaderType.FORGE), metadataError = "Invalid mcmod.info")
        val o = when (el) { is JsonArray -> el.firstOrNull()?.jsonObject; is JsonObject -> el.arr("modList")?.firstOrNull()?.jsonObject; else -> null } ?: return b.copy(loaders = setOf(LoaderType.FORGE))
        return b.copy(loaders = setOf(LoaderType.FORGE), modId = o.str("modid"), name = o.str("name") ?: b.name, version = o.str("version"), description = o.str("description"), minecraftRange = o.str("mcversion"), hasNativeCode = natives)
    }

    /** Simplified version predicate check. Returns null when the predicate cannot be evaluated (reported as uncertain). */
    fun matchesVersion(predicate: String, version: String): Boolean? {
        val p = predicate.trim()
        if (p == "*" || p.isEmpty()) return true
        if (p.contains("||")) return p.split("||").map { matchesVersion(it, version) }.let { r -> if (r.any { it == true }) true else if (r.all { it == false }) false else null }
        if (p.startsWith("[") || p.startsWith("(")) return mavenRange(p, version)
        val parts = p.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size > 1) return parts.map { matchesVersion(it, version) }.let { r -> if (r.all { it == true }) true else if (r.any { it == false }) false else null }
        val op = Regex("^(>=|<=|>|<|=|~|\\^)?(.+)$").find(p) ?: return null
        val (o, v) = op.destructured
        if (v.endsWith(".x")) return version == v.removeSuffix(".x") || version.startsWith(v.removeSuffix("x"))
        val c = compareVersions(version, v) ?: return null
        return when (o) { ">=" -> c >= 0; "<=" -> c <= 0; ">" -> c > 0; "<" -> c < 0; "~" -> c >= 0 && version.split('.').take(2) == v.split('.').take(2)
            "^" -> c >= 0 && version.split('.').first() == v.split('.').first(); else -> c == 0 }
    }
    private fun mavenRange(r: String, version: String): Boolean? {
        val lo = r.first(); val hi = r.last(); val inner = r.substring(1, r.length - 1).split(',').map { it.trim() }
        if (inner.size == 1) return compareVersions(version, inner[0])?.let { it == 0 }
        val a = inner[0].takeIf { it.isNotEmpty() }?.let { compareVersions(version, it) ?: return null }
        val b = inner[1].takeIf { it.isNotEmpty() }?.let { compareVersions(version, it) ?: return null }
        return (a == null || if (lo == '[') a >= 0 else a > 0) && (b == null || if (hi == ']') b <= 0 else b < 0)
    }
    /** Numeric dotted comparison; null for snapshot-style ids that cannot be ordered reliably. */
    fun compareVersions(a: String, b: String): Int? {
        // The numeric part must be followed by the end or a pre-release/build suffix ("1.20.1-pre1"); "23w31a" is not orderable.
        val re = Regex("^(\\d+(?:\\.\\d+)*)(?:$|[-+])")
        val x = re.find(a.trim())?.groupValues?.get(1) ?: return null; val y = re.find(b.trim())?.groupValues?.get(1) ?: return null
        val xs = x.split('.').map { it.toInt() }; val ys = y.split('.').map { it.toInt() }
        for (i in 0 until maxOf(xs.size, ys.size)) { val c = xs.getOrElse(i) { 0 }.compareTo(ys.getOrElse(i) { 0 }); if (c != 0) return c }
        return 0
    }
}

/** Manages <gameDir>/mods. Enabling/disabling uses the ".disabled" suffix convention that all loaders ignore. */
class ModManager(private val gameDir: Path) {
    val dir: Path get() = gameDir.resolve("mods")
    fun list(): List<ModInfo> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { s -> s.filter { val n = it.fileName.toString(); Files.isRegularFile(it) && (n.endsWith(".jar") || n.endsWith(".jar.disabled")) }.collect(java.util.stream.Collectors.toList()) }
            .map { ModInspector.inspect(it) }.sortedBy { it.name.lowercase() }
    }
    fun import(source: Path, overwrite: Boolean = false): ModInfo {
        val info = ModInspector.inspect(source)
        require(info.metadataError == null || !info.metadataError.startsWith("Unreadable")) { "Not a valid mod archive: ${info.metadataError}" }
        Files.createDirectories(dir)
        val target = dir.resolve(FsUtil.safeFileName(source.fileName.toString()).let { if (it.endsWith(".jar")) it else "$it.jar" })
        require(overwrite || !Files.exists(target)) { "${target.fileName} already exists" }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        return ModInspector.inspect(target)
    }
    fun setEnabled(mod: ModInfo, enabled: Boolean): Path {
        require(FsUtil.isInside(dir, mod.file))
        val name = mod.file.fileName.toString()
        val target = if (enabled) mod.file.resolveSibling(name.removeSuffix(".disabled")) else mod.file.resolveSibling("$name.disabled".replace(".disabled.disabled", ".disabled"))
        return if (target == mod.file) target else Files.move(mod.file, target)
    }
    /** Removal moves the file into a launcher trash folder so it can be restored. */
    fun remove(mod: ModInfo, trash: Path): Path {
        require(FsUtil.isInside(dir, mod.file)); Files.createDirectories(trash)
        return Files.move(mod.file, trash.resolve("${System.currentTimeMillis()}-${mod.file.fileName}"))
    }

    fun checkCompatibility(mcVersion: String, loader: LoaderType?, javaMajor: Int): List<CompatFinding> {
        val mods = list().filter { it.enabled }
        val ids = mods.mapNotNull { it.modId }.toSet() + setOf("fabricloader", "fabric", "quilt_loader", "forge", "neoforge", "minecraft", "java")
        val out = mutableListOf<CompatFinding>()
        for (m in mods) {
            m.metadataError?.let { out += CompatFinding(m.file, "warning", it, Certainty.UNKNOWN) }
            if (loader != null && loader != LoaderType.VANILLA && m.loaders.isNotEmpty() && loader !in m.loaders)
                out += CompatFinding(m.file, "error", "${m.name} is a ${m.loaders.joinToString("/")} mod but the profile uses $loader", Certainty.CERTAIN)
            if (loader == null || loader == LoaderType.VANILLA) out += CompatFinding(m.file, "error", "Mods are ignored without a mod loader", Certainty.CERTAIN)
            m.minecraftRange?.let { r ->
                when (ModInspector.matchesVersion(r, mcVersion)) {
                    false -> out += CompatFinding(m.file, "error", "${m.name} requires Minecraft $r", Certainty.LIKELY)
                    null -> out += CompatFinding(m.file, "info", "${m.name} declares Minecraft '$r' which could not be evaluated", Certainty.UNKNOWN)
                    true -> {}
                }
            }
            m.javaRange?.let { r -> if (ModInspector.matchesVersion(r, javaMajor.toString()) == false) out += CompatFinding(m.file, "error", "${m.name} requires Java $r", Certainty.LIKELY) }
            m.dependencies.filter { it.required && it.modId !in ids }.forEach { out += CompatFinding(m.file, "error", "${m.name} needs missing dependency '${it.modId}' (${it.versionRange})", Certainty.LIKELY) }
            m.breaks.filter { it in ids }.forEach { out += CompatFinding(m.file, "error", "${m.name} is incompatible with installed mod '$it'", Certainty.CERTAIN) }
            if (m.hasNativeCode) out += CompatFinding(m.file, "warning", "${m.name} contains desktop native code that may not load on Android", Certainty.LIKELY)
        }
        mods.groupBy { it.modId }.filter { it.key != null && it.value.size > 1 }.forEach { (id, l) -> out += CompatFinding(l.first().file, "error", "Duplicate mod '$id' (${l.size} files)", Certainty.CERTAIN) }
        return out
    }
}
