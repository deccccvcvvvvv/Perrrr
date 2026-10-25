package pro.cslauncher.core.install

import pro.cslauncher.core.util.CsLog
import java.io.IOException
import java.nio.file.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class UnsafeArchiveException(message: String) : IOException(message)

data class ExtractResult(val files: Int, val bytes: Long, val skippedDuplicates: Int)

/** Archive extraction hardened against path traversal, absolute paths, symlinks, duplicates and zip bombs. */
object SafeZip {
    fun safeTarget(destDir: Path, entryName: String): Path {
        val name = entryName.replace('\\', '/')
        if (name.startsWith("/") || name.contains("\u0000") || Regex("^[A-Za-z]:").containsMatchIn(name))
            throw UnsafeArchiveException("Absolute path in archive: $entryName")
        if (name.split('/').any { it == ".." }) throw UnsafeArchiveException("Path traversal in archive: $entryName")
        val root = destDir.toAbsolutePath().normalize()
        val target = root.resolve(name).normalize()
        if (!target.startsWith(root)) throw UnsafeArchiveException("Entry escapes destination: $entryName")
        return target
    }

    // java.util.zip does not expose Unix mode bits, and this extractor never creates links: a "symlink" entry is
    // written as a plain file containing its target text (inert). What matters is never writing *through* an
    // existing link, which is enforced below for every target path and its parents.
    private fun assertNoLinkInPath(root: Path, target: Path) {
        var p: Path? = target
        val r = root.toAbsolutePath().normalize()
        while (p != null && p.startsWith(r) && p != r) {
            if (Files.isSymbolicLink(p)) throw UnsafeArchiveException("Refusing to write through symbolic link $p")
            p = p.parent
        }
    }

    fun extract(
        zip: Path, destDir: Path,
        filter: (String) -> Boolean = { true },
        stripPrefix: String = "",
        maxEntries: Int = 200_000,
        maxTotalBytes: Long = 8L * 1024 * 1024 * 1024,
    ): ExtractResult {
        Files.createDirectories(destDir)
        var files = 0; var bytes = 0L; var dups = 0
        val seen = HashSet<String>()
        ZipFile(zip.toFile()).use { zf ->
            val entries = zf.entries().toList()
            if (entries.size > maxEntries) throw UnsafeArchiveException("Archive has too many entries (${entries.size})")
            for (e in entries) {
                var name = e.name.replace('\\', '/')
                if (stripPrefix.isNotEmpty()) { if (!name.startsWith(stripPrefix)) continue; name = name.removePrefix(stripPrefix) }
                if (name.isEmpty() || !filter(name)) continue
                val target = safeTarget(destDir, name)
                assertNoLinkInPath(destDir, target)
                if (e.isDirectory) { Files.createDirectories(target); continue }
                if (!seen.add(name)) { dups++; CsLog.w("SafeZip", "Duplicate entry skipped: $name"); continue }
                Files.createDirectories(target.parent)
                zf.getInputStream(e).use { input ->
                    Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            bytes += n
                            if (bytes > maxTotalBytes) throw UnsafeArchiveException("Archive expands beyond ${maxTotalBytes / 1048576} MB")
                            out.write(buf, 0, n)
                        }
                    }
                }
                files++
            }
        }
        return ExtractResult(files, bytes, dups)
    }

    /** Lists entry names after validating every path, without extracting. */
    fun validatedEntries(zip: Path): List<String> = ZipFile(zip.toFile()).use { zf ->
        zf.entries().toList().map { e -> safeTarget(Paths.get("/check"), e.name); e.name.replace('\\', '/') }
    }
}
