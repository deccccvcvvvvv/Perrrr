package pro.cslauncher.core.content

import pro.cslauncher.core.install.SafeZip
import pro.cslauncher.core.util.*
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class WorldInfo(val dir: Path, val folderName: String, val levelName: String?, val lastPlayedMs: Long?, val gameMode: Int?, val hardcore: Boolean?,
                     val versionName: String?, val hasIcon: Boolean, val sizeBytes: Long?, val readError: String?)

class WorldInUseException(name: String) : IllegalStateException("World '$name' is open in the game. Close it first.")

class WorldManager(private val gameDir: Path, private val backupsRoot: Path) {
    val savesDir: Path get() = gameDir.resolve("saves")

    fun list(computeSize: Boolean = false): List<WorldInfo> = if (!Files.isDirectory(savesDir)) emptyList() else
        Files.list(savesDir).use { s -> s.filter { Files.isRegularFile(it.resolve("level.dat")) }.collect(java.util.stream.Collectors.toList()) }.map { read(it, computeSize) }.sortedByDescending { it.lastPlayedMs ?: 0 }

    fun read(dir: Path, computeSize: Boolean = false): WorldInfo = try {
        val data = Nbt.readFile(dir.resolve("level.dat").toFile()).second.compound("Data") ?: error("level.dat has no Data")
        WorldInfo(dir, dir.fileName.toString(), data.string("LevelName"), data.long("LastPlayed"), data.int("GameType"), data.int("hardcore")?.let { it != 0 },
            data.compound("Version")?.string("Name"), Files.isRegularFile(dir.resolve("icon.png")), if (computeSize) FsUtil.sizeOf(dir) else null, null)
    } catch (e: Exception) { WorldInfo(dir, dir.fileName.toString(), null, null, null, null, null, false, null, "level.dat unreadable: ${e.message}") }

    /** Minecraft holds an exclusive lock on session.lock while a world is open. */
    fun isInUse(dir: Path): Boolean {
        val lock = dir.resolve("session.lock"); if (!Files.exists(lock)) return false
        return try { FileChannel.open(lock, StandardOpenOption.WRITE).use { ch -> val l = ch.tryLock(); if (l == null) true else { l.release(); false } } }
               catch (e: java.nio.channels.OverlappingFileLockException) { true } catch (e: Exception) { false }
    }
    private fun guard(dir: Path) { require(FsUtil.isInside(savesDir, dir)) { "Not a world of this profile" }; if (isInUse(dir)) throw WorldInUseException(dir.fileName.toString()) }

    /** Renames the display name (level.dat LevelName) after backing up level.dat. Folder name is unchanged. */
    fun rename(dir: Path, newName: String) {
        guard(dir); require(newName.isNotBlank() && newName.length <= 64)
        val f = dir.resolve("level.dat")
        Files.copy(f, dir.resolve("level.dat_cs_backup"), StandardCopyOption.REPLACE_EXISTING)
        val (rootName, root) = Nbt.readFile(f.toFile())
        val data = root.compound("Data") ?: error("level.dat has no Data")
        data.v["LevelName"] = Tag.TString(newName)
        Nbt.writeFile(f.toFile(), rootName, root, gzip = true)
    }

    fun backup(dir: Path): Path {
        guard(dir)
        val out = backupsRoot.resolve("worlds").resolve("${FsUtil.safeFileName(dir.fileName.toString())}-${System.currentTimeMillis()}.zip")
        zipDirectory(dir, out, dir.fileName.toString()); return out
    }
    fun export(dir: Path, target: Path) { guard(dir); zipDirectory(dir, target, dir.fileName.toString()) }

    /** Imports a world archive. Validates paths and the presence of level.dat; never overwrites an existing world. */
    fun import(archive: Path): WorldInfo {
        val entries = SafeZip.validatedEntries(archive)
        val levelDat = entries.filter { it.endsWith("level.dat") && it.count { c -> c == '/' } <= 1 }.minByOrNull { it.length }
            ?: throw IllegalArgumentException("Archive does not contain a Minecraft world (no level.dat)")
        val prefix = levelDat.removeSuffix("level.dat")
        val base = FsUtil.safeFileName(prefix.trimEnd('/').ifEmpty { archive.fileName.toString().removeSuffix(".zip") })
        var name = base; var i = 2
        while (Files.exists(savesDir.resolve(name))) name = "$base ($i)".also { i++ }
        val target = savesDir.resolve(name)
        val staging = savesDir.resolve(".import-${System.nanoTime()}")
        try { SafeZip.extract(archive, staging, stripPrefix = prefix); Files.move(staging, target) }
        finally { if (Files.exists(staging)) FsUtil.deleteTreeWithin(savesDir, staging) }
        return read(target)
    }

    /** Restores a backup next to the existing world (never replaces it silently). */
    fun restore(backupZip: Path): WorldInfo = import(backupZip)

    fun delete(dir: Path) { guard(dir); FsUtil.deleteTreeWithin(savesDir, dir) }

    fun setIcon(dir: Path, png: ByteArray) {
        guard(dir); require(png.size > 8 && png[1] == 'P'.code.toByte() && png[2] == 'N'.code.toByte()) { "Icon must be a PNG image" }
        FsUtil.writeAtomic(dir.resolve("icon.png"), png)
    }

    // ---- datapacks ----
    fun datapacks(dir: Path): List<PackInfo> { val d = dir.resolve("datapacks"); return if (!Files.isDirectory(d)) emptyList() else
        Files.list(d).use { s -> s.filter { Files.isDirectory(it) || it.fileName.toString().endsWith(".zip") }.collect(java.util.stream.Collectors.toList()) }.map { PackInspector.inspect(it) } }
    fun addDatapack(dir: Path, source: Path): PackInfo {
        guard(dir); val info = PackInspector.inspect(source); require(info.valid) { "Invalid datapack: ${info.problem}" }
        val zipOk = Files.isDirectory(source) || ZipFile(source.toFile()).use { z -> z.entries().asSequence().any { it.name.startsWith("data/") } }
        require(zipOk) { "Datapack has no data/ folder" }
        val d = dir.resolve("datapacks"); Files.createDirectories(d)
        val t = d.resolve(FsUtil.safeFileName(source.fileName.toString())); require(!Files.exists(t)) { "Datapack already installed" }
        Files.copy(source, t); return PackInspector.inspect(t)
    }
    fun removeDatapack(dir: Path, pack: Path) { guard(dir); require(FsUtil.isInside(dir.resolve("datapacks"), pack)); if (Files.isDirectory(pack)) FsUtil.deleteTreeWithin(dir.resolve("datapacks"), pack) else Files.delete(pack) }

    companion object {
        fun zipDirectory(dir: Path, out: Path, rootName: String) {
            Files.createDirectories(out.parent)
            val tmp = out.resolveSibling(out.fileName.toString() + ".part")
            ZipOutputStream(Files.newOutputStream(tmp)).use { zos ->
                Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.fileName.toString() != "session.lock" }.collect(java.util.stream.Collectors.toList()) }.forEach { f ->
                    zos.putNextEntry(ZipEntry(rootName + "/" + dir.relativize(f).toString().replace('\\', '/'))); Files.copy(f, zos); zos.closeEntry()
                }
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
