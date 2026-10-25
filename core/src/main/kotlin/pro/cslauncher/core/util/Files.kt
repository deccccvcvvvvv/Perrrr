package pro.cslauncher.core.util

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

object FsUtil {
    /** Writes atomically: temp file in the same directory, then move. Prevents half-written configs. */
    fun writeAtomic(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.toAbsolutePath().parent)
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp-" + System.nanoTime())
        Files.write(tmp, bytes)
        try { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (e: AtomicMoveNotSupportedException) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) }
    }
    /** Android-safe replacements for Java 11 Files.readString/writeString (absent from Android's java.nio). */
    fun readText(p: Path): String = String(Files.readAllBytes(p), Charsets.UTF_8)
    fun writeText(p: Path, text: CharSequence, vararg options: java.nio.file.OpenOption): Path = Files.write(p, text.toString().toByteArray(Charsets.UTF_8), *options)

    /**
     * Free bytes on the volume holding [p] (or its nearest existing parent). Uses java.io.File because Android's
     * java.nio declares Files.getFileStore but throws at runtime.
     */
    fun usableSpace(p: Path): Long {
        var f: java.io.File? = p.toAbsolutePath().toFile()
        while (f != null && !f.exists()) f = f.parentFile
        return f?.usableSpace ?: 0L
    }

    fun writeAtomic(target: Path, text: String) = writeAtomic(target, text.toByteArray(Charsets.UTF_8))

    /** Total size of regular files below [dir] without following symlinks. */
    fun sizeOf(dir: Path): Long {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return 0
        if (Files.isRegularFile(dir, LinkOption.NOFOLLOW_LINKS)) return Files.size(dir)
        var total = 0L
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) total += attrs.size(); return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
        })
        return total
    }

    /**
     * Recursively deletes [target], which MUST be inside [allowedRoot]. Symlinks are removed, never followed.
     * This is the only recursive delete used by the launcher, so every deletion is bounded to launcher-owned trees.
     */
    fun deleteTreeWithin(allowedRoot: Path, target: Path) {
        val root = allowedRoot.toAbsolutePath().normalize()
        val t = target.toAbsolutePath().normalize()
        require(t.startsWith(root) && t != root) { "Refusing to delete $t outside of $root" }
        if (!Files.exists(t, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(t, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isSymbolicLink) { Files.delete(dir); return FileVisitResult.SKIP_SUBTREE }
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult { Files.delete(dir); return FileVisitResult.CONTINUE }
        })
    }

    fun isInside(root: Path, p: Path) = p.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())

    fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.').ifEmpty { "unnamed" }.take(120)
}
