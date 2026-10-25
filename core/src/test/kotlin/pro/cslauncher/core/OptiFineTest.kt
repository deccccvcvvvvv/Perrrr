package pro.cslauncher.core

import kotlinx.coroutines.runBlocking
import pro.cslauncher.core.content.GDiff
import pro.cslauncher.core.content.OptiFineException
import pro.cslauncher.core.content.OptiFineInstaller
import pro.cslauncher.core.download.DownloadManager
import pro.cslauncher.core.install.Installer
import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.net.UrlConnectionTransport
import pro.cslauncher.core.platform.PlatformInfo
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import kotlin.test.*

class OptiFineTest {
    /** Tiny GDIFF writer used only to build test patches. */
    private class GDiffWriter {
        private val bos = ByteArrayOutputStream()
        private val out = DataOutputStream(bos).apply { write(byteArrayOf(0xD1.toByte(), 0xFF.toByte(), 0xD1.toByte(), 0xFF.toByte(), 4)) }
        fun data(b: ByteArray) = apply {
            when {
                b.size <= 246 -> out.writeByte(b.size)
                b.size <= 0xFFFF -> { out.writeByte(247); out.writeShort(b.size) }
                else -> { out.writeByte(248); out.writeInt(b.size) }
            }
            out.write(b)
        }
        fun copy(offset: Long, len: Int, op: Int) = apply {
            out.writeByte(op)
            when (op) {
                249 -> { out.writeShort(offset.toInt()); out.writeByte(len) }
                250 -> { out.writeShort(offset.toInt()); out.writeShort(len) }
                251 -> { out.writeShort(offset.toInt()); out.writeInt(len) }
                252 -> { out.writeInt(offset.toInt()); out.writeByte(len) }
                253 -> { out.writeInt(offset.toInt()); out.writeShort(len) }
                254 -> { out.writeInt(offset.toInt()); out.writeInt(len) }
                255 -> { out.writeLong(offset); out.writeInt(len) }
            }
        }
        fun end(): ByteArray { out.writeByte(0); return bos.toByteArray() }
    }

    @Test fun gdiffAppliesEveryOpcode() {
        val base = ByteArray(70_000) { (it % 251).toByte() }
        val big = ByteArray(300) { 7 }
        val huge = ByteArray(70_000) { 3 }
        val patch = GDiffWriter().data("AB".toByteArray()).data(big).data(huge)
            .copy(10, 5, 249).copy(20, 300, 250).copy(30, 66_000, 251)
            .copy(66_000, 4, 252).copy(66_001, 1000, 253).copy(1, 69_000, 254).copy(2, 3, 255).end()
        val r = GDiff.apply(base, patch)
        val expected = "AB".toByteArray() + big + huge + base.copyOfRange(10, 15) + base.copyOfRange(20, 320) +
            base.copyOfRange(30, 66_030) + base.copyOfRange(66_000, 66_004) + base.copyOfRange(66_001, 67_001) +
            base.copyOfRange(1, 69_001) + base.copyOfRange(2, 5)
        assertContentEquals(expected, r)
    }

    @Test fun gdiffRejectsBadInput() {
        assertFailsWith<OptiFineException> { GDiff.apply(ByteArray(4), byteArrayOf(1, 2, 3, 4, 4)) }
        val outOfRange = GDiffWriter().copy(5, 10, 249).end()
        assertFailsWith<OptiFineException> { GDiff.apply(ByteArray(8), outOfRange) }
        val truncated = GDiffWriter().data(ByteArray(20)).end().copyOf(12)
        assertFailsWith<OptiFineException> { GDiff.apply(ByteArray(8), truncated) }
    }

    @Test fun configParsingAndPatternMapping() {
        val cfg = OptiFineInstaller.parseConfig(
            "# comment\r\nsrg/a/B.class=xyz.class\r\nsrg/a/B\\\$C.class=xyz\$a.class\r\nnotch/([a-z\$0-9]+.class)=\$1\r\nassets/x/[0-9]+.png=*\r\n".byteInputStream())
        val pats = cfg.keys.map { Regex(it) }
        assertEquals("xyz.class", OptiFineInstaller.patchBase("srg/a/B.class", pats, cfg))
        assertEquals("xyz\$a.class", OptiFineInstaller.patchBase("srg/a/B\$C.class", pats, cfg))
        assertEquals("abc\$1.class", OptiFineInstaller.patchBase("notch/abc\$1.class", pats, cfg))
        assertEquals("assets/x/12.png", OptiFineInstaller.patchBase("/assets/x/12.png", pats, cfg))
        assertNull(OptiFineInstaller.patchBase("other/Thing.class", pats, cfg))
        assertFailsWith<OptiFineException> { OptiFineInstaller.parseConfig("a=b=c".byteInputStream()) }
    }

    @Test fun versionStringFromConfigClass() {
        val bytes = byteArrayOf(0, 1, 2) + "xxOptiFine_1.20.1_HD_U_I6".toByteArray() + byteArrayOf(1, 0)
        assertEquals("OptiFine_1.20.1_HD_U_I6", OptiFineInstaller.versionFromBytes(bytes))
        assertNull(OptiFineInstaller.versionFromBytes("nothing here".toByteArray()))
    }

    private fun syntheticSetup(md5Override: String? = null): Triple<LauncherDirs, Path, ByteArray> {
        val root = tempDir("of"); val dirs = LauncherDirs(root)
        val vanillaClass = "VANILLA-CLASS-BYTES-0123456789".toByteArray()
        val vanilla = zipBytes(mapOf("abc.class" to vanillaClass, "net/minecraft/client/main/Main.class" to "main".toByteArray()))
        Files.createDirectories(dirs.versionDir("1.20.1"))
        Files.write(dirs.versionJar("1.20.1"), vanilla)
        Files.writeString(dirs.versionJson("1.20.1"), """{"id":"1.20.1","type":"release","mainClass":"net.minecraft.client.main.Main",
            "arguments":{"game":["--username","${'$'}{auth_player_name}"],"jvm":[]},"libraries":[],
            "downloads":{"client":{"url":"https://example.invalid/c.jar","sha1":"${sha1(vanilla)}","size":${vanilla.size}}}}""")
        val patched = "OPTIFINE-".toByteArray() + vanillaClass.copyOfRange(0, 13)
        val patch = GDiffWriter().data("OPTIFINE-".toByteArray()).copy(0, 13, 249).end()
        val ofJar = zipBytes(linkedMapOf(
            "notch/net/optifine/Config.class" to (byteArrayOf(0xCA.toByte(), 0xFE.toByte()) + "OptiFine_1.20.1_HD_U_I6".toByteArray() + byteArrayOf(1)),
            "patch2.cfg" to "notch/([a-z\$0-9]+.class)=\$1\n".toByteArray(),
            "patch/notch/abc.class.xdelta" to patch,
            "patch/notch/abc.class.md5" to (md5Override ?: OptiFineInstaller.md5Hex(patched)).toByteArray(),
            "launchwrapper-of.txt" to "2.3\n".toByteArray(),
            "launchwrapper-of-2.3.jar" to zipBytes(mapOf("net/minecraft/launchwrapper/Launch.class" to "lw".toByteArray())),
            "optifine/OptiFineTweaker.class" to "tweaker".toByteArray(),
        ))
        val of = root.resolve("OptiFine_1.20.1_HD_U_I6.jar"); Files.write(of, ofJar)
        return Triple(dirs, of, patched)
    }

    @Test fun installsSyntheticOptiFineAndProfileResolvesWithoutDownloads() = runBlocking {
        val (dirs, of, patched) = syntheticSetup()
        val inst = OptiFineInstaller(dirs)
        val info = inst.inspect(of)
        assertEquals("1.20.1", info.minecraftVersion); assertEquals("HD_U_I6", info.edition); assertEquals("2.3", info.launchwrapperVersion)
        assertEquals("1.20.1-OptiFine_HD_U_I6", info.versionId)
        val res = inst.install(of)
        assertEquals(1, res.patched)
        val lib = dirs.libraryFile(info.libraryPath)
        ZipFile(lib.toFile()).use { z ->
            assertContentEquals(patched, z.getInputStream(z.getEntry("notch/abc.class")).readBytes())
            assertNotNull(z.getEntry("optifine/OptiFineTweaker.class"))
            assertNotNull(z.getEntry("patch2.cfg"))
            assertNull(z.getEntry("patch/notch/abc.class.md5"))
            assertNull(z.getEntry("patch/notch/abc.class.xdelta"))
        }
        assertTrue(Files.isRegularFile(dirs.libraryFile("optifine/launchwrapper-of/2.3/launchwrapper-of-2.3.jar")))

        val http = UrlConnectionTransport()
        val installer = Installer(dirs, http, DownloadManager(http), PlatformInfo.android("arm64-v8a", "14"))
        val rv = installer.resolve(res.versionId, null)
        assertEquals("net.minecraft.launchwrapper.Launch", rv.meta.mainClass)
        assertEquals("1.20.1", rv.jarVersionId, "client jar comes from vanilla")
        assertTrue(rv.libraries.any { it.file == lib })
        assertTrue(installer.libraryTasks(rv).isEmpty(), "local OptiFine libraries are never downloaded")
        val game = rv.meta.gameArguments.flatMap { it.values }
        assertTrue(game.containsAll(listOf("--tweakClass", "optifine.OptiFineTweaker")), "game args: $game")
        assertTrue(game.contains("--username"), "inherits vanilla arguments")
    }

    @Test fun checksumMismatchFailsCleanly() {
        val (dirs, of, _) = syntheticSetup(md5Override = "00000000000000000000000000000000")
        val inst = OptiFineInstaller(dirs)
        val e = assertFailsWith<OptiFineException> { inst.install(of) }
        assertTrue(e.message!!.contains("checksum"))
        val info = inst.inspect(of)
        assertFalse(Files.exists(dirs.libraryFile(info.libraryPath)))
        assertFalse(Files.exists(dirs.libraryFile(info.libraryPath + ".part")))
        assertFalse(Files.exists(dirs.versionJson(info.versionId)))
    }

    @Test fun refusesWhenVanillaMissingOrNotOptiFine() {
        val (dirs, of, _) = syntheticSetup()
        Files.delete(dirs.versionJar("1.20.1"))
        assertTrue(assertFailsWith<OptiFineException> { OptiFineInstaller(dirs).install(of) }.message!!.contains("not installed"))
        val notOf = dirs.root.resolve("x.jar"); Files.write(notOf, zipBytes(mapOf("a.txt" to "x".toByteArray())))
        assertFailsWith<OptiFineException> { OptiFineInstaller(dirs).inspect(notOf) }
    }

    /**
     * Byte-level comparison against the reference patcher's output for a real OptiFine build.
     * Runs only with -PoptifineDir=<dir> containing OptiFine.jar, vanilla.jar and ref.jar
     * (ref.jar = output of `java -cp OptiFine.jar optifine.Patcher vanilla.jar OptiFine.jar ref.jar`).
     */
    @Test fun matchesReferencePatcherOnRealJar() {
        val dir = System.getProperty("cs.optifineDir").orEmpty()
        if (dir.isBlank()) return
        val d = Paths.get(dir)
        val out = tempDir("ofreal").resolve("out.jar")
        val (patched, _) = OptiFineInstaller(tempDir("ofd").let { LauncherDirs(it) }).patch(d.resolve("vanilla.jar"), d.resolve("OptiFine.jar"), out)
        assertTrue(patched > 1000)
        ZipFile(d.resolve("ref.jar").toFile()).use { ref -> ZipFile(out.toFile()).use { mine ->
            val refNames = ref.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            val myNames = mine.entries().asSequence().map { it.name }.toList()
            assertEquals(refNames, myNames, "same entries in the same order")
            for (n in refNames) assertContentEquals(ref.getInputStream(ref.getEntry(n)).readBytes(), mine.getInputStream(mine.getEntry(n)).readBytes(), n)
        } }
    }
}
