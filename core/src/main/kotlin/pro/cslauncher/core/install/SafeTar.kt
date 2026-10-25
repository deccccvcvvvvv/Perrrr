package pro.cslauncher.core.install

import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream

/** Minimal, hardened ustar/GNU/pax tar reader (used for Java runtime archives, .tar or .tar.gz). */
object SafeTar {
    fun extract(archive: Path, destDir: Path, stripComponents: Int = 0, maxTotalBytes: Long = 4L shl 30): ExtractResult {
        Files.createDirectories(destDir)
        val root = destDir.toAbsolutePath().normalize()
        val raw = BufferedInputStream(Files.newInputStream(archive))
        raw.mark(4)
        val magic = ByteArray(2).also { raw.read(it) }
        raw.reset()
        val input: InputStream = if (magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()) GZIPInputStream(raw, 65536) else raw
        var files = 0; var total = 0L
        val links = mutableListOf<Pair<Path, String>>()
        input.use { ins ->
            val header = ByteArray(512)
            var longName: String? = null; var paxPath: String? = null; var longLink: String? = null
            while (true) {
                if (!readFully(ins, header)) break
                if (header.all { it == 0.toByte() }) break
                val size = parseOctal(header, 124, 12)
                val type = header[156].toInt().toChar()
                var name = longName ?: paxPath ?: run {
                    val prefix = cstr(header, 345, 155); val n = cstr(header, 0, 100)
                    if (prefix.isNotEmpty()) "$prefix/$n" else n
                }
                val linkName = longLink ?: cstr(header, 157, 100)
                longName = null; paxPath = null; longLink = null
                val mode = parseOctal(header, 100, 8).toInt()
                when (type) {
                    'L' -> { longName = String(readBytes(ins, size), Charsets.UTF_8).trimEnd('\u0000'); continue }
                    'K' -> { longLink = String(readBytes(ins, size), Charsets.UTF_8).trimEnd('\u0000'); continue }
                    'x' -> { paxPath = parsePax(readBytes(ins, size)); continue }
                    'g' -> { skip(ins, padded(size)); continue }
                }
                val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
                if (parts.size <= stripComponents) { skip(ins, padded(size)); continue }
                name = parts.drop(stripComponents).joinToString("/")
                val target = SafeZip.safeTarget(root, name)
                when (type) {
                    '5' -> Files.createDirectories(target)
                    '2' -> links += target to linkName
                    '0', '\u0000', '7' -> {
                        total += size
                        if (total > maxTotalBytes) throw UnsafeArchiveException("Archive too large")
                        Files.createDirectories(target.parent)
                        if (Files.isSymbolicLink(target)) throw UnsafeArchiveException("Refusing to overwrite link $target")
                        Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { out ->
                            var left = size; val buf = ByteArray(65536)
                            while (left > 0) { val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) throw java.io.EOFException("Truncated tar"); out.write(buf, 0, n); left -= n }
                        }
                        skip(ins, padded(size) - size)
                        if (mode and 0b001_001_001 != 0) runCatching {
                            Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(target) + setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE))
                        }.recoverCatching { target.toFile().setExecutable(true) }
                        files++
                    }
                    else -> skip(ins, padded(size)) // hard links, devices, fifos: ignored
                }
            }
        }
        // Symlinks are only created if they resolve inside the destination.
        for ((link, dest) in links) {
            val resolved = link.parent.resolve(dest).normalize()
            if (!resolved.startsWith(root) || dest.startsWith("/")) continue
            runCatching { Files.createDirectories(link.parent); Files.deleteIfExists(link); Files.createSymbolicLink(link, Paths.get(dest)) }
                .onFailure { if (Files.isRegularFile(resolved)) Files.copy(resolved, link, StandardCopyOption.REPLACE_EXISTING) }
        }
        return ExtractResult(files, total, 0)
    }

    private fun padded(size: Long) = (size + 511) / 512 * 512
    private fun readFully(i: InputStream, b: ByteArray): Boolean {
        var off = 0
        while (off < b.size) { val n = i.read(b, off, b.size - off); if (n < 0) return off != 0 && throw java.io.EOFException("Truncated tar header"); off += n }
        return true
    }
    private fun readBytes(i: InputStream, size: Long): ByteArray {
        require(size in 0..(1 shl 20)) { "Tar metadata block too large" }
        val b = ByteArray(size.toInt()); readFully(i, b); skip(i, padded(size) - size); return b
    }
    private fun skip(i: InputStream, n: Long) { var left = n; while (left > 0) { val s = i.skip(left); if (s <= 0) { if (i.read() < 0) return; left-- } else left -= s } }
    private fun cstr(b: ByteArray, off: Int, len: Int): String { var end = off; while (end < off + len && b[end] != 0.toByte()) end++; return String(b, off, end - off, Charsets.UTF_8) }
    private fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
        if (b[off].toInt() and 0x80 != 0) { var v = 0L; for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xff); return v }
        val s = cstr(b, off, len).trim().trim('\u0000'); return if (s.isEmpty()) 0 else s.toLong(8)
    }
    private fun parsePax(data: ByteArray): String? {
        val text = String(data, Charsets.UTF_8)
        return text.lineSequence().mapNotNull { line -> val sp = line.indexOf(' '); if (sp < 0) null else line.substring(sp + 1) }
            .firstOrNull { it.startsWith("path=") }?.removePrefix("path=")
    }
}
