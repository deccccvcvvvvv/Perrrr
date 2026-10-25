package pro.cslauncher.core

import pro.cslauncher.core.install.*
import java.nio.file.Files
import kotlin.test.*

class SafeArchiveTest {
    @Test fun rejectsTraversalAndAbsolutePaths() {
        for (bad in listOf("../evil.txt", "a/../../evil", "/etc/passwd", "C:/win.ini", "a\\..\\..\\x")) {
            val z = tempDir().resolve("bad.zip"); Files.write(z, zipBytes(mapOf(bad to "x".toByteArray())))
            val out = tempDir()
            assertFailsWith<UnsafeArchiveException>(bad) { SafeZip.extract(z, out) }
        }
    }
    @Test fun extractsNormalArchiveAndSkipsDuplicates() {
        val z = tempDir().resolve("ok.zip"); Files.write(z, zipBytes(mapOf("a/b.txt" to "1".toByteArray(), "c.txt" to "2".toByteArray())))
        val out = tempDir(); val r = SafeZip.extract(z, out)
        assertEquals(2, r.files); assertEquals("1", Files.readString(out.resolve("a/b.txt")))
    }
    @Test fun refusesToWriteThroughSymlinks() {
        val out = tempDir(); val outside = tempDir()
        Files.createSymbolicLink(out.resolve("link"), outside)
        val z = tempDir().resolve("l.zip"); Files.write(z, zipBytes(mapOf("link/pwned.txt" to "x".toByteArray())))
        assertFailsWith<UnsafeArchiveException> { SafeZip.extract(z, out) }
        assertFalse(Files.exists(outside.resolve("pwned.txt")))
    }
    @Test fun zipBombLimit() {
        val z = tempDir().resolve("big.zip"); Files.write(z, zipBytes(mapOf("big" to ByteArray(2_000_000))))
        assertFailsWith<UnsafeArchiveException> { SafeZip.extract(z, tempDir(), maxTotalBytes = 1_000_000) }
    }
    @Test fun tarExtractionWithStrip() {
        // Build a tiny ustar archive by hand: one directory + one file.
        fun header(name: String, size: Int, type: Char): ByteArray {
            val h = ByteArray(512); name.toByteArray().copyInto(h, 0)
            "0000644".toByteArray().copyInto(h, 100); String.format("%011o", size).toByteArray().copyInto(h, 124)
            h[156] = type.code.toByte(); "ustar".toByteArray().copyInto(h, 257)
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            val sum = h.sumOf { it.toInt() and 0xff }; String.format("%06o", sum).toByteArray().copyInto(h, 148); h[154] = 0
            return h
        }
        val content = "JAVA_VERSION=\"17.0.9\"\nOS_ARCH=\"aarch64\"\n".toByteArray()
        val bos = java.io.ByteArrayOutputStream()
        bos.write(header("jdk-17/", 0, '5')); bos.write(header("jdk-17/release", content.size, '0')); bos.write(content); bos.write(ByteArray(512 - content.size)); bos.write(ByteArray(1024))
        val tar = tempDir().resolve("rt.tar"); Files.write(tar, bos.toByteArray())
        val out = tempDir(); SafeTar.extract(tar, out, stripComponents = 1)
        assertEquals(content.decodeToString(), Files.readString(out.resolve("release")))
        val evil = java.io.ByteArrayOutputStream(); evil.write(header("../escape", 1, '0')); evil.write(ByteArray(512)); evil.write(ByteArray(1024))
        val et = tempDir().resolve("e.tar"); Files.write(et, evil.toByteArray())
        assertFailsWith<UnsafeArchiveException> { SafeTar.extract(et, tempDir()) }
    }
}
