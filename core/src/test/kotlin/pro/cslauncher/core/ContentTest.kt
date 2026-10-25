package pro.cslauncher.core

import pro.cslauncher.core.content.*
import pro.cslauncher.core.profile.LoaderType
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.*

class ContentTest {
    private fun levelDat(dir: Path, name: String, gzip: Boolean = true) {
        Files.createDirectories(dir)
        val data = Tag.TCompound(linkedMapOf("LevelName" to Tag.TString(name), "LastPlayed" to Tag.TLong(1_700_000_000_000), "GameType" to Tag.TInt(1),
            "hardcore" to Tag.TByte(0), "Version" to Tag.TCompound(linkedMapOf("Name" to Tag.TString("1.20.1")))))
        Nbt.writeFile(dir.resolve("level.dat").toFile(), "", Tag.TCompound(linkedMapOf("Data" to data)), gzip)
    }

    @Test fun nbtRoundTripAllTagTypes() {
        val root = Tag.TCompound(linkedMapOf(
            "b" to Tag.TByte(-1), "s" to Tag.TShort(300), "i" to Tag.TInt(Int.MIN_VALUE), "l" to Tag.TLong(Long.MAX_VALUE), "f" to Tag.TFloat(1.5f), "d" to Tag.TDouble(-2.25),
            "ba" to Tag.TByteArray(byteArrayOf(1, 2, 3)), "str" to Tag.TString("héllo ✓"), "list" to Tag.TList(3, listOf(Tag.TInt(1), Tag.TInt(2))),
            "empty" to Tag.TList(10, emptyList()), "ia" to Tag.TIntArray(intArrayOf(7, 8)), "la" to Tag.TLongArray(longArrayOf(9L)),
            "nested" to Tag.TCompound(linkedMapOf("x" to Tag.TString("y")))))
        for (gz in listOf(true, false)) {
            val f = tempDir("nbt").resolve("t.dat").toFile()
            Nbt.writeFile(f, "root", root, gz)
            val (name, back) = Nbt.readFile(f)
            assertEquals("root", name)
            assertEquals(root.v.keys.toList(), back.v.keys.toList())
            assertEquals(Tag.TString("héllo ✓"), back.v["str"]); assertEquals(Long.MAX_VALUE, back.long("l"))
            assertContentEquals(intArrayOf(7, 8), (back.v["ia"] as Tag.TIntArray).v)
            assertEquals(Tag.TList(3, listOf(Tag.TInt(1), Tag.TInt(2))), back.v["list"])
            assertEquals("y", back.compound("nested")!!.string("x"))
        }
        val bad = tempDir("nbt").resolve("bad.dat"); Files.write(bad, byteArrayOf(8, 0, 0))
        assertFailsWith<IllegalArgumentException> { Nbt.readFile(bad.toFile()) }
    }

    @Test fun worldsListRenameBackupRestoreAndInUse() {
        val game = tempDir("game"); val backups = tempDir("bk"); val wm = WorldManager(game, backups)
        val w = game.resolve("saves/MyWorld"); levelDat(w, "My World"); Files.writeString(w.resolve("region.mca"), "chunks")
        Files.createDirectories(game.resolve("saves/broken")); Files.writeString(game.resolve("saves/broken/level.dat"), "not nbt")
        val list = wm.list()
        assertEquals(2, list.size)
        val info = list.first { it.folderName == "MyWorld" }
        assertEquals("My World", info.levelName); assertEquals(1, info.gameMode); assertEquals(false, info.hardcore); assertEquals("1.20.1", info.versionName)
        assertNotNull(list.first { it.folderName == "broken" }.readError, "unreadable worlds are reported, not hidden or deleted")

        wm.rename(w, "Renamed"); assertEquals("Renamed", wm.read(w).levelName); assertTrue(Files.exists(w.resolve("level.dat_cs_backup")))
        val zip = wm.backup(w); assertTrue(Files.size(zip) > 0)
        val restored = wm.restore(zip)
        assertEquals("MyWorld (2)", restored.folderName, "restore never overwrites the existing world")
        assertEquals("chunks", Files.readString(restored.dir.resolve("region.mca")))
        assertEquals("chunks", Files.readString(w.resolve("region.mca")))

        // A world open in the game (session.lock held) cannot be modified.
        FileChannel.open(w.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            ch.lock().use {
                assertTrue(wm.isInUse(w))
                assertFailsWith<WorldInUseException> { wm.delete(w) }
                assertFailsWith<WorldInUseException> { wm.rename(w, "x") }
            }
        }
        assertFalse(wm.isInUse(w))
        assertFailsWith<IllegalArgumentException> { wm.delete(game.resolve("mods")) }
        assertTrue(Files.exists(w))
    }

    @Test fun worldImportIsSafe() {
        val game = tempDir("game"); val wm = WorldManager(game, tempDir("bk"))
        val src = tempDir("src"); levelDat(src.resolve("Nested"), "Imported")
        val zipPath = tempDir("z").resolve("world.zip"); WorldManager.zipDirectory(src.resolve("Nested"), zipPath, "Nested")
        assertEquals("Imported", wm.import(zipPath).levelName)
        val evil = tempDir("z").resolve("evil.zip"); Files.write(evil, zipBytes(mapOf("W/level.dat" to byteArrayOf(1), "../../escape.txt" to byteArrayOf(1))))
        assertFails { wm.import(evil) }
        assertFalse(Files.exists(game.resolve("../escape.txt").normalize()))
        val noWorld = tempDir("z").resolve("no.zip"); Files.write(noWorld, zipBytes(mapOf("readme.txt" to byteArrayOf(1))))
        assertFailsWith<IllegalArgumentException> { wm.import(noWorld) }
        assertEquals(listOf("Nested"), Files.list(wm.savesDir).use { s -> s.map { it.fileName.toString() }.toList() }, "no staging leftovers")
    }

    @Test fun serverAddressParsing() {
        assertEquals("play.example.com" to 25565, ServerAddress.parse(" play.example.com "))
        assertEquals("mc.example.org" to 25570, ServerAddress.parse("mc.example.org:25570"))
        assertEquals("192.168.1.10" to 25565, ServerAddress.parse("192.168.1.10"))
        assertEquals("::1" to 25566, ServerAddress.parse("[::1]:25566"))
        assertEquals("xn--bcher-kva.de" to 25565, ServerAddress.parse("bücher.de"))
        for (bad in listOf("", "host:0", "host:70000", "host:abc", "bad host", "-x.com", "a..b", "[::1")) assertNull(ServerAddress.parse(bad), bad)
    }

    @Test fun serversDatRoundTripWithBackupAndFavorites() {
        val game = tempDir("game"); val store = ServerListStore(game)
        assertEquals(emptyList(), store.load())
        val list = listOf(ServerEntry("Hypixel", "mc.hypixel.net", favorite = true), ServerEntry("Local", "192.168.0.5", 25570, acceptTextures = 1))
        store.save(list); assertEquals(list, store.load())
        store.save(list.take(1)); assertTrue(Files.exists(game.resolve("servers.dat_old")))
        val (_, root) = Nbt.readFile(game.resolve("servers.dat").toFile())
        assertEquals("mc.hypixel.net", ((root.v["servers"] as Tag.TList).v[0] as Tag.TCompound).string("ip"), "readable by the game itself")
    }

    private fun jar(dir: Path, name: String, entries: Map<String, String>): Path =
        dir.resolve(name).also { Files.write(it, zipBytes(entries.mapValues { e -> e.value.toByteArray() })) }

    @Test fun modInspectionAllFormats() {
        val d = tempDir("mods")
        val fabric = ModInspector.inspect(jar(d, "sodium.jar", mapOf("fabric.mod.json" to """{"schemaVersion":1,"id":"sodium","version":"0.5.3","name":"Sodium",
            "depends":{"minecraft":"1.20.1","fabricloader":">=0.12","java":">=17"},"breaks":{"optifabric":"*"}}""")))
        assertEquals("sodium", fabric.modId); assertEquals("1.20.1", fabric.minecraftRange); assertEquals(">=17", fabric.javaRange)
        assertEquals(listOf("fabricloader"), fabric.dependencies.map { it.modId }); assertEquals(listOf("optifabric"), fabric.breaks)
        val quilt = ModInspector.inspect(jar(d, "q.jar", mapOf("quilt.mod.json" to """{"quilt_loader":{"id":"qm","version":"1.0","depends":["quilt_loader",{"id":"minecraft","versions":">=1.20"},{"id":"opt","optional":true}],"metadata":{"name":"Q Mod"}}}""")))
        assertEquals("Q Mod", quilt.name); assertEquals(">=1.20", quilt.minecraftRange); assertFalse(quilt.dependencies.first { it.modId == "opt" }.required)
        val forge = ModInspector.inspect(jar(d, "f.jar", mapOf("META-INF/mods.toml" to """
            modLoader="javafml"
            loaderVersion="[47,)"
            [[mods]]
            modId="jei" # comment
            version="15.2.0"
            displayName="Just Enough Items"
            [[dependencies.jei]]
            modId="minecraft"
            mandatory=true
            versionRange="[1.20.1,1.21)"
            [[dependencies.jei]]
            modId="architectury"
            type="required"
            versionRange="*"
        """.trimIndent())))
        assertEquals("jei", forge.modId); assertEquals(setOf(LoaderType.FORGE), forge.loaders); assertEquals("[1.20.1,1.21)", forge.minecraftRange)
        assertEquals(listOf("architectury"), forge.dependencies.map { it.modId })
        val legacy = ModInspector.inspect(jar(d, "old.jar", mapOf("mcmod.info" to """[{"modid":"oldmod","name":"Old","mcversion":"1.7.10"}]""", "lib/native.dll" to "x")))
        assertEquals("oldmod", legacy.modId); assertTrue(legacy.hasNativeCode)
        assertNotNull(ModInspector.inspect(jar(d, "plain.jar", mapOf("a.txt" to "x"))).metadataError)
        val notZip = d.resolve("broken.jar"); Files.writeString(notZip, "nope")
        assertTrue(ModInspector.inspect(notZip).metadataError!!.startsWith("Unreadable"))
    }

    @Test fun versionPredicates() {
        val t = mapOf(
            ("1.20.1" to "1.20.1") to true, ("1.20.1" to "1.20.2") to false, (">=1.20" to "1.20.4") to true, ("<1.20" to "1.20") to false,
            ("1.20.x" to "1.20.6") to true, ("1.20.x" to "1.21") to false, ("~1.20.1" to "1.20.4") to true, ("~1.20.1" to "1.21") to false,
            ("^1.2" to "1.9") to true, (">=1.19 <1.21" to "1.20.1") to true, (">=1.19 <1.21" to "1.21") to false,
            ("1.19.4 || 1.20.1" to "1.20.1") to true, ("[1.20.1,1.21)" to "1.20.6") to true, ("[1.20.1,1.21)" to "1.21") to false,
            ("(,1.20]" to "1.20") to true, ("*" to "anything") to true, (">=17" to "21") to true, (">=17" to "8") to false,
        )
        for ((k, v) in t) assertEquals(v, ModInspector.matchesVersion(k.first, k.second), "${k.first} vs ${k.second}")
        assertNull(ModInspector.matchesVersion(">=1.20", "23w31a"), "snapshots cannot be ordered: uncertain, not false")
    }

    @Test fun modManagerToggleCompatAndTrash() {
        val game = tempDir("game"); val mm = ModManager(game); val src = tempDir("src")
        mm.import(jar(src, "sodium.jar", mapOf("fabric.mod.json" to """{"id":"sodium","version":"1","depends":{"minecraft":"1.20.1","needed":"*"},"breaks":{"bad":"*"}}""")))
        mm.import(jar(src, "bad.jar", mapOf("fabric.mod.json" to """{"id":"bad","version":"1"}""")))
        mm.import(jar(src, "jei.jar", mapOf("META-INF/mods.toml" to "[[mods]]\nmodId=\"jei\"\n")))
        assertFailsWith<IllegalArgumentException> { mm.import(src.resolve("bad.jar")) }
        val findings = mm.checkCompatibility("1.20.4", LoaderType.FABRIC, 17).map { it.message }
        assertTrue(findings.any { it.contains("requires Minecraft 1.20.1") }, findings.toString())
        assertTrue(findings.any { it.contains("missing dependency 'needed'") })
        assertTrue(findings.any { it.contains("incompatible with installed mod 'bad'") })
        assertTrue(findings.any { it.contains("FORGE mod but the profile uses FABRIC") })
        val bad = mm.list().first { it.modId == "bad" }
        val disabled = mm.setEnabled(bad, false); assertTrue(disabled.toString().endsWith("bad.jar.disabled"))
        assertFalse(mm.list().first { it.modId == "bad" }.enabled)
        assertFalse(mm.checkCompatibility("1.20.1", LoaderType.FABRIC, 17).any { it.message.contains("installed mod 'bad'") }, "disabled mods are ignored")
        mm.setEnabled(mm.list().first { it.modId == "bad" }, true); assertTrue(Files.exists(mm.dir.resolve("bad.jar")))
        val trash = tempDir("trash"); mm.remove(mm.list().first { it.modId == "bad" }, trash)
        assertEquals(1, Files.list(trash).use { it.count() }, "removed mods are recoverable")
    }

    @Test fun optionsFilePreservesUnknownLines() {
        val f = tempDir("o").resolve("options.txt")
        Files.writeString(f, "version:3465\nfov:0.0\nkey_key.jump:key.keyboard.space\nweird line without colon\n")
        val o = OptionsFile(f)
        assertEquals("0.0", o.read()["fov"])
        o.write(mapOf("fov" to "0.5", "renderDistance" to "6"))
        assertEquals(listOf("version:3465", "fov:0.5", "key_key.jump:key.keyboard.space", "weird line without colon", "renderDistance:6"), Files.readAllLines(f))
    }

    @Test fun resourceAndShaderPacks() {
        val game = tempDir("game"); val src = tempDir("src")
        val rp = src.resolve("Faithful.zip"); Files.write(rp, zipBytes(mapOf("pack.mcmeta" to """{"pack":{"pack_format":15,"description":"§aSharp §rtextures"}}""".toByteArray(), "pack.png" to byteArrayOf(1))))
        val rpm = ResourcePackManager(game)
        val info = rpm.import(rp); assertEquals(15, info.packFormat); assertEquals("Sharp textures", info.description); assertTrue(info.hasIcon)
        assertEquals(15, PackFormats.expectedFor("1.20.1")); assertEquals(8, PackFormats.expectedFor("1.18.2")); assertEquals(6, PackFormats.expectedFor("1.16.5"))
        Files.writeString(game.resolve("options.txt"), "resourcePacks:[\"vanilla\",\"fabric\"]\nfov:0.0\n")
        rpm.setEnabledOrder(listOf("Faithful.zip")); assertEquals(listOf("Faithful.zip"), rpm.enabled())
        assertTrue(Files.readString(game.resolve("options.txt")).contains("resourcePacks:[\"vanilla\",\"fabric\",\"file/Faithful.zip\"]"))
        val invalid = src.resolve("bad.zip"); Files.write(invalid, zipBytes(mapOf("x.txt" to byteArrayOf(1))))
        assertFailsWith<IllegalArgumentException> { rpm.import(invalid) }

        val spm = ShaderPackManager(game)
        val sp = src.resolve("BSL.zip"); Files.write(sp, zipBytes(mapOf("shaders/composite.fsh" to byteArrayOf(1))))
        assertTrue(spm.import(sp).valid)
        assertFailsWith<IllegalArgumentException> { spm.import(invalid) }
        val iris = ModInfo(game.resolve("mods/iris.jar"), true, setOf(LoaderType.FABRIC), "iris", "Iris", "1", null, null, null, emptyList(), emptyList(), false, 1)
        assertEquals(ShaderLoader.IRIS, spm.detectLoader(listOf(iris)))
        assertEquals(ShaderLoader.NONE, spm.detectLoader(listOf(iris.copy(enabled = false))))
        spm.setActive(ShaderLoader.IRIS, "BSL.zip"); assertEquals("BSL.zip", spm.active(ShaderLoader.IRIS))
        spm.setActive(ShaderLoader.IRIS, null); assertNull(spm.active(ShaderLoader.IRIS))
        assertFailsWith<IllegalStateException> { spm.setActive(ShaderLoader.NONE, "BSL.zip") }
        assertTrue(spm.warnings("gl4es", ShaderLoader.IRIS).any { it.contains("2.1") })
    }
}
