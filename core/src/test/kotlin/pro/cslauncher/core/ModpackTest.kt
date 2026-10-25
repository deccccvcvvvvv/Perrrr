package pro.cslauncher.core

import kotlinx.coroutines.runBlocking
import pro.cslauncher.core.content.*
import pro.cslauncher.core.download.DownloadManager
import pro.cslauncher.core.install.UnsafeArchiveException
import pro.cslauncher.core.net.UrlConnectionTransport
import pro.cslauncher.core.profile.LoaderType
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class ModpackTest {
    private fun hex(algo: String, b: ByteArray) = MessageDigest.getInstance(algo).digest(b).joinToString("") { "%02x".format(it) }

    private fun index(files: String, deps: String = """"minecraft":"1.20.1","fabric-loader":"0.15.11"""") =
        """{"formatVersion":1,"game":"minecraft","versionId":"2.0","name":"Test Pack","summary":"s","files":[$files],"dependencies":{$deps}}"""

    private fun fileJson(path: String, data: ByteArray, url: String, env: String? = null) =
        """{"path":"$path","hashes":{"sha1":"${hex("SHA-1", data)}","sha512":"${hex("SHA-512", data)}"},"downloads":["$url"],"fileSize":${data.size}""" +
            (env?.let { ""","env":{"client":"$it","server":"required"}""" } ?: "") + "}"

    @Test fun parsesIndexAndLoader() {
        val d = "x".toByteArray()
        val i = Mrpack.parse(index(fileJson("mods/a.jar", d, "https://cdn.modrinth.com/data/a.jar")))
        assertEquals("Test Pack", i.name); assertEquals("1.20.1", i.minecraftVersion)
        assertEquals(LoaderType.FABRIC, i.loader); assertEquals("0.15.11", i.loaderVersion); assertEquals(1, i.files.size)
        assertEquals(LoaderType.QUILT, Mrpack.parse(index("", """"minecraft":"1.20.1","quilt-loader":"0.26.0"""")).loader)
        assertEquals(LoaderType.NEOFORGE, Mrpack.parse(index("", """"minecraft":"1.21.1","neoforge":"21.1.1"""")).loader)
        assertEquals(LoaderType.VANILLA, Mrpack.parse(index("", """"minecraft":"1.21.1"""")).loader)
    }

    @Test fun rejectsBadIndexes() {
        assertFailsWith<ModpackException> { Mrpack.parse("""{"formatVersion":2,"game":"minecraft","dependencies":{"minecraft":"1"}}""") }
        assertFailsWith<ModpackException> { Mrpack.parse(index("", """"fabric-loader":"1"""")) }
        for (bad in listOf("../evil.jar", "/etc/x", "mods/../../x", "C:/x", "mods//a.jar", "./a")) {
            assertFailsWith<ModpackException>(bad) { Mrpack.parse(index(fileJson(bad, "x".toByteArray(), "https://cdn.modrinth.com/a"))) }
        }
        assertFailsWith<ModpackException> { Mrpack.parse(index("""{"path":"mods/a.jar","hashes":{"sha1":"zz"},"downloads":[]}""")) }
    }

    @Test fun urlAllowListIsEnforced() {
        assertEquals("https://cdn.modrinth.com/data/x.jar", Mrpack.pickUrl(listOf("http://cdn.modrinth.com/data/x.jar", "https://cdn.modrinth.com/data/x.jar")))
        assertNull(Mrpack.pickUrl(listOf("https://evil.example/x.jar", "https://cdn.modrinth.com.evil.example/x")))
        assertNull(Mrpack.pickUrl(listOf("https://user@cdn.modrinth.com/x")))
        assertNotNull(Mrpack.pickUrl(listOf("https://github.com/o/r/releases/download/v1/x.jar")))
        val i = Mrpack.parse(index(fileJson("mods/a.jar", "x".toByteArray(), "https://evil.example/a.jar")))
        assertFailsWith<ModpackException> { ModpackInstaller(DownloadManager(UrlConnectionTransport())).plan(i, tempDir()) }
    }

    @Test fun forgePacksAreRefusedClearly() {
        val i = Mrpack.parse(index("", """"minecraft":"1.20.1","forge":"47.2.0""""))
        val e = assertFailsWith<ModpackException> { ModpackInstaller(DownloadManager(UrlConnectionTransport())).checkLoaderSupported(i) }
        assertTrue(e.message!!.contains("Forge"))
    }

    @Test fun installsFilesVerifiesHashesSkipsServerOnlyAndAppliesOverrides() = runBlocking {
        LocalHttp().use { srv ->
            val modA = "mod-a-bytes".toByteArray(); val modB = "mod-b-bytes".toByteArray(); val server = "server".toByteArray()
            val ua = srv.put("/a.jar", modA); val ub = srv.put("/b.jar", modB); val us = srv.put("/s.jar", server)
            val idx = index(listOf(fileJson("mods/a.jar", modA, ua), fileJson("mods/b.jar", modB, ub, "optional"), fileJson("mods/server.jar", server, us, "unsupported")).joinToString(","))
            val pack = tempDir("mp").resolve("p.mrpack")
            Files.write(pack, zipBytes(linkedMapOf(
                Mrpack.INDEX to idx.toByteArray(),
                "overrides/config/a.toml" to "common".toByteArray(),
                "overrides/options.txt" to "fov:70".toByteArray(),
                "client-overrides/options.txt" to "fov:90".toByteArray(),
                "server-overrides/server.properties" to "x".toByteArray(),
            )))
            val game = tempDir("game")
            val inst = ModpackInstaller(DownloadManager(UrlConnectionTransport(), retryBaseDelayMs = 1)) { it.firstOrNull() }
            val r = inst.installFiles(pack, game)
            assertEquals(2, r.downloaded); assertEquals(listOf("mods/server.jar (server-only)"), r.skipped)
            assertContentEquals(modA, Files.readAllBytes(game.resolve("mods/a.jar")))
            assertContentEquals(modB, Files.readAllBytes(game.resolve("mods/b.jar")))
            assertFalse(Files.exists(game.resolve("mods/server.jar")))
            assertEquals("fov:90", String(Files.readAllBytes(game.resolve("options.txt"))), "client-overrides win")
            assertEquals("common", String(Files.readAllBytes(game.resolve("config/a.toml"))))
            assertFalse(Files.exists(game.resolve("server.properties")))
            assertTrue(Files.readString(game.resolve("cs-modpack.json")).contains("Test Pack"))

            // Corrupted download is rejected by hash verification.
            srv.corrupt += "/a.jar"
            val game2 = tempDir("game2")
            val inst2 = ModpackInstaller(DownloadManager(UrlConnectionTransport(), maxAttempts = 1, retryBaseDelayMs = 1)) { it.firstOrNull() }
            assertFailsWith<ModpackException> { inst2.installFiles(pack, game2) }
            assertFalse(Files.exists(game2.resolve("mods/a.jar")))
        }
    }

    @Test fun overridesCannotEscapeTheGameDirectory() {
        val pack = tempDir("mp").resolve("evil.mrpack")
        Files.write(pack, zipBytes(linkedMapOf(Mrpack.INDEX to index("").toByteArray(), "overrides/../../evil.txt" to "x".toByteArray())))
        val game = tempDir("g").resolve("inst")
        assertFailsWith<UnsafeArchiveException> { Mrpack.extractOverrides(pack, game) }
        assertFalse(Files.exists(game.parent.resolve("evil.txt")))
    }

    @Test fun modIconsAreDetectedAndRead() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        val dir = tempDir("mods")
        val fabric = dir.resolve("f.jar"); Files.write(fabric, zipBytes(mapOf(
            "fabric.mod.json" to """{"schemaVersion":1,"id":"f","version":"1","icon":"assets/f/icon.png"}""".toByteArray(), "assets/f/icon.png" to png)))
        val fabricMap = dir.resolve("fm.jar"); Files.write(fabricMap, zipBytes(mapOf(
            "fabric.mod.json" to """{"schemaVersion":1,"id":"fm","version":"1","icon":{"16":"s.png","128":"l.png","512":"xl.png"}}""".toByteArray(), "l.png" to png)))
        val quilt = dir.resolve("q.jar"); Files.write(quilt, zipBytes(mapOf(
            "quilt.mod.json" to """{"quilt_loader":{"id":"q","version":"1","metadata":{"name":"Q","icon":"q.png"}}}""".toByteArray())))
        val forge = dir.resolve("fo.jar"); Files.write(forge, zipBytes(mapOf(
            "META-INF/mods.toml" to "modLoader=\"javafml\"\nlogoFile=\"logo.png\"\n[[mods]]\nmodId=\"fo\"\nversion=\"1\"\n".toByteArray(), "logo.png" to png)))
        val f = ModInspector.inspect(fabric); assertEquals("assets/f/icon.png", f.iconPath); assertContentEquals(png, ModInspector.readIcon(fabric, f.iconPath))
        assertEquals("l.png", ModInspector.inspect(fabricMap).iconPath)
        assertEquals("q.png", ModInspector.inspect(quilt).iconPath)
        assertNull(ModInspector.readIcon(quilt, "q.png"), "declared but missing icon")
        val fo = ModInspector.inspect(forge); assertEquals("logo.png", fo.iconPath); assertNotNull(ModInspector.readIcon(forge, fo.iconPath))
        assertNull(ModInspector.readIcon(forge, "../logo.png"))
        assertNull(ModInspector.readIcon(fabric, f.iconPath, maxBytes = 3), "oversized icons are ignored")
    }

    @Test fun modpackKindMapsToModrinthType() {
        assertEquals("modpack", ProjectKind.MODPACK.modrinth)
    }
}
