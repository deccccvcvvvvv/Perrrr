package pro.cslauncher.core.util

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object Hashing {
    fun digest(path: Path, algorithm: String): String =
        Files.newInputStream(path).use { digest(it, algorithm) }

    fun digest(input: InputStream, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return md.digest().toHex()
    }

    fun sha1(path: Path) = digest(path, "SHA-1")
    fun sha256(path: Path) = digest(path, "SHA-256")
    fun sha512(path: Path) = digest(path, "SHA-512")
    fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).toHex()
    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** Expected integrity data for a file. A checksum proves integrity, not server authenticity (TLS provides that). */
data class Integrity(val sha1: String? = null, val sha256: String? = null, val sha512: String? = null, val size: Long? = null) {
    val hasChecksum get() = sha1 != null || sha256 != null || sha512 != null

    /** Returns null when the file satisfies every known constraint, otherwise a human readable reason. */
    fun check(path: Path, fullHash: Boolean = true): String? {
        if (!Files.isRegularFile(path)) return "file missing"
        val actualSize = Files.size(path)
        if (size != null && size >= 0 && actualSize != size) return "size $actualSize != expected $size"
        if (actualSize == 0L && (size == null || size > 0)) return "file is empty"
        if (!fullHash) return null
        sha1?.let { val a = Hashing.sha1(path); if (!a.equals(it, true)) return "sha1 $a != expected $it" }
        sha256?.let { val a = Hashing.sha256(path); if (!a.equals(it, true)) return "sha256 mismatch" }
        sha512?.let { val a = Hashing.sha512(path); if (!a.equals(it, true)) return "sha512 mismatch" }
        return null
    }
}
