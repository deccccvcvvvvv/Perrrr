package pro.cslauncher.core.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.FsUtil
import pro.cslauncher.core.util.Hashing
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class OptiFineException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Applier for the GDIFF delta format (W3C NOTE-gdiff-19970901), which is what OptiFine ships its
 * class patches in. Opcodes: 0 = end, 1..246 = inline data of that length, 247/248 = data with a
 * u16/i32 length, 249..255 = copy from the base with varying offset/length widths (big-endian).
 */
object GDiff {
    private val MAGIC = intArrayOf(0xD1, 0xFF, 0xD1, 0xFF)

    fun apply(base: ByteArray, patch: ByteArray): ByteArray {
        val input = DataInputStream(patch.inputStream())
        for (m in MAGIC) if (input.readUnsignedByte() != m) throw OptiFineException("Not a GDIFF patch (bad magic)")
        val version = input.readUnsignedByte()
        if (version != 4) throw OptiFineException("Unsupported GDIFF version $version")
        val out = ByteArrayOutputStream(base.size + patch.size)
        fun data(n: Int) {
            if (n < 0) throw OptiFineException("Negative GDIFF data length")
            val buf = ByteArray(n); input.readFully(buf); out.write(buf)
        }
        fun copy(offset: Long, length: Int) {
            if (offset < 0 || length < 0 || offset + length > base.size) throw OptiFineException("GDIFF copy out of range ($offset+$length > ${base.size})")
            out.write(base, offset.toInt(), length)
        }
        try {
            // Like the reference applier, keep reading until the stream is exhausted; opcode 0 is a no-op terminator.
            while (input.available() > 0) {
                when (val op = input.readUnsignedByte()) {
                    0 -> {}
                    in 1..246 -> data(op)
                    247 -> data(input.readUnsignedShort())
                    248 -> data(input.readInt())
                    249 -> copy(input.readUnsignedShort().toLong(), input.readUnsignedByte())
                    250 -> copy(input.readUnsignedShort().toLong(), input.readUnsignedShort())
                    251 -> copy(input.readUnsignedShort().toLong(), input.readInt())
                    252 -> copy(input.readInt().toLong() and 0xFFFFFFFFL, input.readUnsignedByte())
                    253 -> copy(input.readInt().toLong() and 0xFFFFFFFFL, input.readUnsignedShort())
                    254 -> copy(input.readInt().toLong() and 0xFFFFFFFFL, input.readInt())
                    255 -> copy(input.readLong(), input.readInt())
                }
            }
        } catch (e: EOFException) {
            throw OptiFineException("Truncated GDIFF patch", e)
        }
        return out.toByteArray()
    }
}

/** What we learned from a user-supplied OptiFine jar. */
data class OptiFineJarInfo(
    /** e.g. "OptiFine_1.21.11_HD_U_J9" (preview builds look like "OptiFine_26.2_HD_U_K2_pre5"). */
    val fullVersion: String,
    val minecraftVersion: String,
    /** e.g. "HD_U_J9". */
    val edition: String,
    /** Version of the embedded launchwrapper-of jar, or null when the jar targets the stock launchwrapper. */
    val launchwrapperVersion: String?,
    val patchCount: Int,
) {
    val versionId get() = "$minecraftVersion-OptiFine_$edition"
    val libraryName get() = "optifine:OptiFine:${minecraftVersion}_$edition"
    val libraryPath get() = "optifine/OptiFine/${minecraftVersion}_$edition/OptiFine-${minecraftVersion}_$edition.jar"
    val isPreview get() = edition.contains("pre", ignoreCase = true)
}

data class OptiFineInstallResult(val versionId: String, val info: OptiFineJarInfo, val patched: Int, val copied: Int)

/**
 * Headless OptiFine installation compatible with the layout the official installer produces
 * (libraries/optifine/OptiFine/<mc>_<ed>/OptiFine-<mc>_<ed>.jar + launchwrapper + a
 * versions/<mc>-OptiFine_<ed>/ profile inheriting from vanilla). The OptiFine jar must be obtained
 * by the user from optifine.net; nothing here downloads or redistributes it.
 */
class OptiFineInstaller(private val dirs: LauncherDirs) {

    fun inspect(jar: Path): OptiFineJarInfo = ZipFile(jar.toFile()).use { zip -> inspect(zip) }

    private fun inspect(zip: ZipFile): OptiFineJarInfo {
        val cfgEntry = CONFIG_CLASSES.firstNotNullOfOrNull { zip.getEntry(it) }
            ?: throw OptiFineException("This file is not an OptiFine installer jar (no OptiFine Config class found)")
        val full = zip.getInputStream(cfgEntry).use { versionFromBytes(it.readBytes()) }
            ?: throw OptiFineException("Could not read the OptiFine version from the jar")
        val parts = full.split('_')
        if (parts.size < 3) throw OptiFineException("Unexpected OptiFine version string '$full'")
        val mc = parts[1]
        val edition = parts.drop(2).joinToString("_")
        LauncherDirs.checkId("$mc-OptiFine_$edition")
        val lw = zip.getEntry("launchwrapper-of.txt")?.let { e ->
            val v = zip.getInputStream(e).use { String(it.readBytes(), Charsets.US_ASCII).trim() }
            if (!LW_VERSION.matches(v)) throw OptiFineException("Invalid launchwrapper version '$v' in the OptiFine jar")
            v
        }
        val patches = zip.entries().asSequence().count { it.name.startsWith(PREFIX_PATCH) && it.name.endsWith(SUFFIX_DELTA) }
        if (patches == 0) throw OptiFineException("The OptiFine jar contains no patches; is it a mod-only (extracted) jar?")
        return OptiFineJarInfo(full, mc, edition, lw, patches)
    }

    /**
     * Patches [vanillaJar] with the deltas from [optifineJar] into [dest]. Mirrors the reference
     * behaviour: every patch/<name>.xdelta becomes <name> (verified against patch/<name>.md5),
     * patch/<name>.md5 entries are dropped and every other entry is copied unchanged.
     */
    fun patch(vanillaJar: Path, optifineJar: Path, dest: Path, onProgress: (Int, Int) -> Unit = { _, _ -> }): Pair<Int, Int> {
        Files.createDirectories(dest.parent)
        val tmp = dest.resolveSibling(dest.fileName.toString() + ".part")
        var patched = 0; var copied = 0
        try {
            ZipFile(optifineJar.toFile()).use { diff ->
                ZipFile(vanillaJar.toFile()).use { base ->
                    val cfg = readConfig(diff)
                    val patterns = cfg.keys.map { Regex(it) }
                    val total = diff.size()
                    ZipOutputStream(Files.newOutputStream(tmp)).use { out ->
                        val seen = HashSet<String>()
                        var i = 0
                        for (entry in diff.entries()) {
                            i++
                            if (i % 200 == 0) onProgress(i, total)
                            val name = entry.name
                            if (entry.isDirectory) continue
                            if (name.startsWith(PREFIX_PATCH) && name.endsWith(SUFFIX_MD5)) continue
                            val bytes = diff.getInputStream(entry).use { it.readBytes() }
                            if (name.startsWith(PREFIX_PATCH) && name.endsWith(SUFFIX_DELTA)) {
                                val target = name.removePrefix(PREFIX_PATCH).removeSuffix(SUFFIX_DELTA)
                                val baseName = patchBase(target, patterns, cfg)
                                    ?: throw OptiFineException("No patch base for $target")
                                val baseEntry = base.getEntry(baseName)
                                    ?: throw OptiFineException("The Minecraft jar is missing $baseName. Repair the vanilla version and try again.")
                                val baseBytes = base.getInputStream(baseEntry).use { it.readBytes() }
                                val result = GDiff.apply(baseBytes, bytes)
                                diff.getEntry(PREFIX_PATCH + target + SUFFIX_MD5)?.let { md5Entry ->
                                    val expected = diff.getInputStream(md5Entry).use { String(it.readBytes(), Charsets.US_ASCII) }.trim()
                                    val actual = md5Hex(result)
                                    if (!expected.equals(actual, ignoreCase = true)) throw OptiFineException(
                                        "Patched $target does not match OptiFine's checksum. This OptiFine build needs the exact vanilla jar it was made for.")
                                }
                                if (seen.add(target)) { out.putNextEntry(ZipEntry(target)); out.write(result); out.closeEntry() }
                                patched++
                            } else {
                                if (seen.add(name)) { out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() }
                                copied++
                            }
                        }
                    }
                    onProgress(total, total)
                }
            }
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(tmp) }
            throw if (e is OptiFineException) e else OptiFineException("OptiFine patching failed: ${e.message}", e)
        }
        return patched to copied
    }

    /**
     * Full installation. The vanilla version [OptiFineJarInfo.minecraftVersion] must already be installed
     * (its client jar present); callers install it first through the normal installer.
     */
    fun install(optifineJar: Path, onProgress: (Int, Int) -> Unit = { _, _ -> }): OptiFineInstallResult {
        val info = inspect(optifineJar)
        val mc = info.minecraftVersion
        val vanillaJar = dirs.versionJar(mc)
        if (!Files.isRegularFile(vanillaJar) || !Files.isRegularFile(dirs.versionJson(mc)))
            throw OptiFineException("Minecraft $mc is not installed yet. Install it first, then add OptiFine.")
        val libFile = dirs.libraryFile(info.libraryPath)
        val (patched, copied) = patch(vanillaJar, optifineJar, libFile, onProgress)

        val libs = mutableListOf<JsonObject>()
        libs += localLibrary(info.libraryName, info.libraryPath, libFile)
        if (info.launchwrapperVersion != null) {
            val v = info.launchwrapperVersion
            val path = "optifine/launchwrapper-of/$v/launchwrapper-of-$v.jar"
            val f = dirs.libraryFile(path)
            ZipFile(optifineJar.toFile()).use { zip ->
                val e = zip.getEntry("launchwrapper-of-$v.jar") ?: throw OptiFineException("launchwrapper-of-$v.jar is missing from the OptiFine jar")
                Files.createDirectories(f.parent)
                FsUtil.writeAtomic(f, zip.getInputStream(e).use { it.readBytes() })
            }
            libs += localLibrary("optifine:launchwrapper-of:$v", path, f)
        } else {
            // Older OptiFine builds use Mojang's launchwrapper (Java 8 era). Downloadable from Mojang's library host.
            libs += buildJsonObject {
                put("name", "net.minecraft:launchwrapper:1.12")
                putJsonObject("downloads") { putJsonObject("artifact") {
                    put("path", "net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar")
                    put("url", "https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar")
                    put("sha1", "111e7bea9c968cdb3d06ef4632bf7ff0824d0f36")
                    put("size", 32999)
                } }
            }
        }
        val parentJson = FsUtil.readText(dirs.versionJson(mc))
        val legacyArgs = Regex("\"minecraftArguments\"\\s*:\\s*\"([^\"]*)\"").find(parentJson)?.groupValues?.get(1)
        val now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val json = buildJsonObject {
            put("id", info.versionId)
            put("inheritsFrom", mc)
            put("time", now); put("releaseTime", now)
            put("type", "release")
            put("mainClass", "net.minecraft.launchwrapper.Launch")
            if (legacyArgs != null) put("minecraftArguments", "$legacyArgs --tweakClass optifine.OptiFineTweaker")
            else putJsonObject("arguments") { putJsonArray("game") { add(JsonPrimitive("--tweakClass")); add(JsonPrimitive("optifine.OptiFineTweaker")) } }
            put("libraries", JsonArray(libs))
            putJsonObject("csLauncher") { put("optifine", info.fullVersion) }
        }
        val versionJson = dirs.versionJson(info.versionId)
        Files.createDirectories(versionJson.parent)
        FsUtil.writeAtomic(versionJson, json.toString())
        CsLog.i("OptiFine", "Installed ${info.fullVersion} as ${info.versionId} ($patched patched, $copied copied)")
        return OptiFineInstallResult(info.versionId, info, patched, copied)
    }

    private fun localLibrary(name: String, path: String, file: Path) = buildJsonObject {
        put("name", name)
        // Empty URL: generated locally, never downloaded. sha1/size let Verify detect corruption.
        putJsonObject("downloads") { putJsonObject("artifact") {
            put("path", path); put("url", ""); put("sha1", Hashing.sha1(file)); put("size", Files.size(file))
        } }
    }

    companion object {
        const val PREFIX_PATCH = "patch/"
        const val SUFFIX_DELTA = ".xdelta"
        const val SUFFIX_MD5 = ".md5"
        private val CONFIG_FILES = listOf("patch.cfg", "patch2.cfg", "patch3.cfg")
        private val CONFIG_CLASSES = listOf("net/optifine/Config.class", "notch/net/optifine/Config.class", "Config.class", "VersionThread.class")
        private val LW_VERSION = Regex("[0-9.]+")

        /** Extracts "OptiFine_<mc>_<edition>" from a class file's constant pool (printable ASCII run). */
        fun versionFromBytes(bytes: ByteArray): String? {
            val needle = "OptiFine_".toByteArray(Charsets.US_ASCII)
            outer@ for (i in 0..bytes.size - needle.size) {
                for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
                var end = i
                while (end < bytes.size && bytes[end] in 32..122) end++
                return String(bytes, i, end - i, Charsets.US_ASCII)
            }
            return null
        }

        /** patch.cfg, patch2.cfg, patch3.cfg merged in that order (later files override keys, order of first insertion kept). */
        fun readConfig(zip: ZipFile): LinkedHashMap<String, String> {
            val map = LinkedHashMap<String, String>()
            for (f in CONFIG_FILES) {
                val e = zip.getEntry(f) ?: continue
                zip.getInputStream(e).use { parseConfig(it) }.forEach { (k, v) -> map[k] = v }
            }
            return map
        }

        fun parseConfig(input: InputStream): LinkedHashMap<String, String> {
            val map = LinkedHashMap<String, String>()
            String(input.readBytes(), Charsets.US_ASCII).lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                val parts = line.split('=').filter { it.isNotEmpty() }
                if (parts.size != 2) throw OptiFineException("Invalid OptiFine patch configuration line: $line")
                map[parts[0].trim()] = parts[1].trim()
            }
            return map
        }

        fun patchBase(name: String, patterns: List<Regex>, cfg: Map<String, String>): String? {
            val n = name.removePrefix("/")
            for (p in patterns) {
                val m = p.matchEntire(n) ?: continue
                var base = cfg[p.pattern] ?: return null
                if (base.trim() == "*") return n
                for (g in 1 until m.groupValues.size) base = base.replace("$$g", m.groupValues[g])
                return base
            }
            return null
        }

        fun md5Hex(b: ByteArray): String = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
    }
}
