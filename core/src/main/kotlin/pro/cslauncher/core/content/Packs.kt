package pro.cslauncher.core.content

import kotlinx.serialization.json.*
import pro.cslauncher.core.install.SafeZip
import pro.cslauncher.core.util.*
import java.nio.file.*
import java.util.zip.ZipFile

data class PackInfo(val path: Path, val name: String, val description: String?, val packFormat: Int?, val hasIcon: Boolean, val valid: Boolean, val problem: String?, val sizeBytes: Long)

/** Pack-format history (resource packs). Used to warn, never to block: newer games keep loading older packs with a warning. */
object PackFormats {
    private val table = listOf(1 to "1.6.1–1.8.9", 2 to "1.9–1.10.2", 3 to "1.11–1.12.2", 4 to "1.13–1.14.4", 5 to "1.15–1.16.1", 6 to "1.16.2–1.16.5",
        7 to "1.17.x", 8 to "1.18.x", 9 to "1.19–1.19.2", 12 to "1.19.3", 13 to "1.19.4", 15 to "1.20–1.20.1", 18 to "1.20.2", 22 to "1.20.3–1.20.4", 32 to "1.20.5–1.20.6", 34 to "1.21–1.21.1")
    fun describe(format: Int?): String = format?.let { f -> table.firstOrNull { it.first == f }?.second?.let { "format $f ($it)" } ?: "format $f" } ?: "unknown format"
    /** Expected resource pack format for a release id, or null if not in the table. */
    fun expectedFor(mc: String): Int? {
        val known = mapOf("1.12" to 3, "1.13" to 4, "1.14" to 4, "1.15" to 5, "1.16.5" to 6, "1.16" to 5, "1.17" to 7, "1.18" to 8, "1.19.4" to 13, "1.19.3" to 12,
            "1.19" to 9, "1.20.1" to 15, "1.20.2" to 18, "1.20.4" to 22, "1.20.3" to 22, "1.20.6" to 32, "1.20.5" to 32, "1.20" to 15, "1.21" to 34)
        return known.entries.sortedByDescending { it.key.length }.firstOrNull { mc == it.key || mc.startsWith(it.key + ".") }?.value
    }
}

object PackInspector {
    fun inspect(p: Path, requiredDir: String? = null): PackInfo {
        val size = FsUtil.sizeOf(p)
        val name = p.fileName.toString()
        return try {
            val (meta, icon, hasDir) = if (Files.isDirectory(p)) Triple(
                p.resolve("pack.mcmeta").takeIf { Files.isRegularFile(it) }?.let { pro.cslauncher.core.util.FsUtil.readText(it) },
                Files.isRegularFile(p.resolve("pack.png")), requiredDir?.let { Files.isDirectory(p.resolve(it)) } ?: true)
            else ZipFile(p.toFile()).use { z ->
                SafeZip.validatedEntries(p)
                Triple(z.getEntry("pack.mcmeta")?.let { e -> z.getInputStream(e).use { it.readBytes().decodeToString() } }, z.getEntry("pack.png") != null,
                    requiredDir?.let { d -> z.entries().asSequence().any { it.name.startsWith("$d/") } } ?: true)
            }
            if (requiredDir != null && !hasDir) return PackInfo(p, name, null, null, icon, false, "Missing '$requiredDir/' folder", size)
            if (meta == null) return if (requiredDir != null) PackInfo(p, name, null, null, icon, true, null, size) else PackInfo(p, name, null, null, icon, false, "pack.mcmeta missing", size)
            val o = runCatching { CsJson.parseToJsonElement(meta).jsonObject.obj("pack") }.getOrNull() ?: return PackInfo(p, name, null, null, icon, false, "pack.mcmeta invalid", size)
            val desc = when (val d = o["description"]) { is JsonPrimitive -> d.content; is JsonObject -> d.str("text"); is JsonArray -> d.joinToString("") { (it as? JsonPrimitive)?.content ?: (it as? JsonObject)?.str("text") ?: "" }; else -> null }
            PackInfo(p, name, desc?.replace(Regex("§."), ""), o.int("pack_format"), icon, true, null, size)
        } catch (e: Exception) { PackInfo(p, name, null, null, false, false, "Unreadable: ${e.message}", size) }
    }

    fun iconBytes(p: Path): ByteArray? = runCatching {
        if (Files.isDirectory(p)) p.resolve("pack.png").takeIf { Files.isRegularFile(it) }?.let { Files.readAllBytes(it) }
        else ZipFile(p.toFile()).use { z -> z.getEntry("pack.png")?.let { e -> z.getInputStream(e).use { it.readBytes() } } }
    }.getOrNull()
}

/** Reads and edits Minecraft's options.txt (only keys it touches; everything else is preserved byte-for-byte). */
class OptionsFile(private val file: Path) {
    fun read(): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        if (Files.isRegularFile(file)) Files.readAllLines(file).forEach { l -> val i = l.indexOf(':'); if (i > 0) m[l.substring(0, i)] = l.substring(i + 1) }
        return m
    }
    fun write(updates: Map<String, String>) {
        val lines = if (Files.isRegularFile(file)) Files.readAllLines(file).toMutableList() else mutableListOf()
        val done = HashSet<String>()
        for (i in lines.indices) { val k = lines[i].substringBefore(':'); if (k in updates) { lines[i] = "$k:${updates[k]}"; done += k } }
        updates.filterKeys { it !in done }.forEach { (k, v) -> lines += "$k:$v" }
        Files.createDirectories(file.parent)
        FsUtil.writeAtomic(file, lines.joinToString("\n", postfix = "\n"))
    }
}

class ResourcePackManager(private val gameDir: Path) {
    val dir: Path get() = gameDir.resolve("resourcepacks")
    private val options get() = OptionsFile(gameDir.resolve("options.txt"))

    fun list(): List<PackInfo> = if (!Files.isDirectory(dir)) emptyList() else
        Files.list(dir).use { s -> s.filter { Files.isDirectory(it) || it.fileName.toString().endsWith(".zip") }.collect(java.util.stream.Collectors.toList()) }.map { PackInspector.inspect(it) }.sortedBy { it.name.lowercase() }

    /** Enabled packs in game order (bottom → top as stored by Minecraft). */
    fun enabled(): List<String> = parseList(options.read()["resourcePacks"]).filter { it.startsWith("file/") }.map { it.removePrefix("file/") }

    fun setEnabledOrder(names: List<String>) {
        val cur = parseList(options.read()["resourcePacks"])
        val builtins = cur.filterNot { it.startsWith("file/") }.ifEmpty { listOf("vanilla") }
        val list = builtins + names.map { "file/$it" }
        options.write(mapOf("resourcePacks" to list.joinToString(",", "[", "]") { "\"" + it.replace("\"", "\\\"") + "\"" }, "incompatibleResourcePacks" to "[]"))
    }

    fun import(source: Path): PackInfo {
        val info = PackInspector.inspect(source)
        require(info.valid) { "Invalid resource pack: ${info.problem}" }
        Files.createDirectories(dir)
        val t = dir.resolve(FsUtil.safeFileName(source.fileName.toString()))
        require(!Files.exists(t)) { "${t.fileName} already exists" }
        Files.copy(source, t); return PackInspector.inspect(t)
    }

    private fun parseList(s: String?): List<String> = s?.let { runCatching { CsJson.parseToJsonElement(it).jsonArray.map { e -> e.jsonPrimitive.content } }.getOrNull() } ?: emptyList()
}

enum class ShaderLoader { IRIS, OPTIFINE, NONE }

class ShaderPackManager(private val gameDir: Path) {
    val dir: Path get() = gameDir.resolve("shaderpacks")
    fun list(): List<PackInfo> = if (!Files.isDirectory(dir)) emptyList() else
        Files.list(dir).use { s -> s.filter { Files.isDirectory(it) || it.fileName.toString().endsWith(".zip") }.collect(java.util.stream.Collectors.toList()) }.map { PackInspector.inspect(it, "shaders") }.sortedBy { it.name.lowercase() }

    /** Which shader implementation is installed (shaders need Iris or OptiFine; vanilla cannot load them). */
    fun detectLoader(mods: List<ModInfo>): ShaderLoader = when {
        mods.any { it.enabled && (it.modId == "iris" || it.modId == "oculus") } -> ShaderLoader.IRIS
        mods.any { it.enabled && it.name.contains("optifine", true) } || mods.any { it.enabled && it.file.fileName.toString().contains("OptiFine", true) } -> ShaderLoader.OPTIFINE
        else -> ShaderLoader.NONE
    }

    fun active(loader: ShaderLoader): String? = when (loader) {
        ShaderLoader.IRIS -> readProps(gameDir.resolve("config/iris.properties"))["shaderPack"]?.takeIf { readProps(gameDir.resolve("config/iris.properties"))["enableShaders"] != "false" }
        ShaderLoader.OPTIFINE -> OptionsFile(gameDir.resolve("optionsshaders.txt")).read()["shaderPack"]?.takeIf { it != "OFF" }
        ShaderLoader.NONE -> null
    }

    fun setActive(loader: ShaderLoader, name: String?) {
        when (loader) {
            ShaderLoader.IRIS -> {
                val f = gameDir.resolve("config/iris.properties"); val p = readProps(f)
                if (name == null) p["enableShaders"] = "false" else { p["shaderPack"] = name; p["enableShaders"] = "true" }
                Files.createDirectories(f.parent); FsUtil.writeAtomic(f, p.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
            }
            ShaderLoader.OPTIFINE -> OptionsFile(gameDir.resolve("optionsshaders.txt")).write(mapOf("shaderPack" to (name ?: "OFF")))
            ShaderLoader.NONE -> throw IllegalStateException("Install Iris (Fabric/Quilt) or OptiFine to use shader packs")
        }
    }

    fun warnings(rendererId: String, loader: ShaderLoader): List<String> = buildList {
        if (loader == ShaderLoader.NONE) add("No shader loader installed. Shader packs need Iris (Fabric/Quilt) or OptiFine.")
        if (rendererId.startsWith("gl4es")) add("GL4ES emulates OpenGL 2.1; most shader packs need OpenGL 3.x+ and will fail. Zink is more likely to work.")
        if (rendererId == "zink") add("Shader support on Zink depends on the device's Vulkan driver; expect low performance.")
    }

    fun import(source: Path): PackInfo {
        val info = PackInspector.inspect(source, "shaders"); require(info.valid) { "Invalid shader pack: ${info.problem}" }
        Files.createDirectories(dir); val t = dir.resolve(FsUtil.safeFileName(source.fileName.toString())); require(!Files.exists(t)) { "${t.fileName} already exists" }
        Files.copy(source, t); return PackInspector.inspect(t, "shaders")
    }

    private fun readProps(f: Path): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        if (Files.isRegularFile(f)) Files.readAllLines(f).forEach { l -> if (!l.startsWith("#")) { val i = l.indexOf('='); if (i > 0) m[l.substring(0, i).trim()] = l.substring(i + 1).trim() } }
        return m
    }
}
