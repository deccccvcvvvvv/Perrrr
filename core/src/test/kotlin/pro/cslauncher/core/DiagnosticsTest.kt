package pro.cslauncher.core

import pro.cslauncher.core.diagnostics.*
import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.media.*
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.renderer.*
import pro.cslauncher.core.settings.*
import pro.cslauncher.core.storage.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class CrashAnalyzerTest {
    private fun top(text: String, exit: Int? = null) = CrashAnalyzer.analyze(text, exit).first()

    @Test fun classifiesKnownFailures() {
        val cases = mapOf(
            "Exception in thread \"main\" java.lang.UnsupportedClassVersionError: net/minecraft/client/main/Main has been compiled by a more recent version of the Java Runtime (class file version 65.0)" to FailureKind.WRONG_JAVA_VERSION,
            "java.lang.OutOfMemoryError: Java heap space" to FailureKind.OUT_OF_MEMORY,
            "Error occurred during initialization of VM\nCould not reserve enough space for 4194304KB object heap" to FailureKind.OUT_OF_MEMORY,
            "Unrecognized VM option 'UseFooBar'\nError: Could not create the Java Virtual Machine." to FailureKind.INVALID_JVM_ARGUMENT,
            "java.lang.UnsatisfiedLinkError: dlopen failed: library \"liblwjgl.so\" not found" to FailureKind.MISSING_NATIVE,
            "[LWJGL] GLFW_PLATFORM_ERROR error\nGLFW error 65543: EGL: Failed to create context" to FailureKind.GRAPHICS_INIT,
            "Error: Could not find or load main class net.minecraft.client.main.Main" to FailureKind.INCOMPLETE_CLIENT,
            "java.util.zip.ZipException: invalid LOC header (bad signature)" to FailureKind.CORRUPT_FILE,
            "java.lang.NoClassDefFoundError: com/mojang/blaze3d/systems/RenderSystem" to FailureKind.MISSING_LIBRARY,
            "java.io.IOException: No space left on device" to FailureKind.INSUFFICIENT_STORAGE,
            "Mod 'Sodium Extra' (sodium-extra) 0.5.1 requires any version of sodium, which is missing!" to FailureKind.MISSING_MOD_DEPENDENCY,
            "org.spongepowered.asm.mixin.transformer.throwables.MixinTransformerError: MixinApplyError" to FailureKind.INCOMPATIBLE_MOD,
            "# A fatal error has been detected by the Java Runtime Environment:\n#  SIGSEGV (0xb) at pc=0x0000007f" to FailureKind.NATIVE_CRASH,
            "java.nio.file.AccessDeniedException: /storage/emulated/0/Android/data/x/files/game/options.txt" to FailureKind.DIRECTORY_PERMISSION,
            "Failed to verify username! Invalid session" to FailureKind.AUTHENTICATION,
        )
        for ((text, kind) in cases) assertEquals(kind, top(text).kind, text)
    }

    @Test fun extractsActionableDetails() {
        val j = top("UnsupportedClassVersionError: Foo (class file version 61.0)")
        assertEquals(17, (j.repairs.single() as RepairAction.SuggestRuntime).major)
        val a = top("Unrecognized option: -XX:+Bogus")
        val r = a.repairs.single() as RepairAction.RemoveJvmArgument
        assertEquals("-XX:+Bogus", r.arg); assertTrue(r.destructive, "removing a user argument requires confirmation")
        val d = top("Mod 'Sodium Extra' (sodium-extra) 0.5.1 requires any version of sodium, which is missing!")
        assertEquals("sodium", (d.repairs.single() as RepairAction.InstallMod).modId)
        for ((line, id) in listOf("Mod 'Iris' (iris) 1.6.4 requires version 0.5.0 or later of sodium, which is missing!" to "sodium",
                                  "\tMod ID: 'architectury', Requested by: 'rei', Expected range: '[9,)', Actual version: '[MISSING]'" to "architectury"))
            assertEquals(id, (top(line).repairs.single() as RepairAction.InstallMod).modId, line)
        assertTrue(CrashAnalyzer.analyze("Incompatible mods found!").first().repairs.all { it is RepairAction.DisableMod && it.destructive })
    }

    @Test fun exitCodeFallbackAndCleanExit() {
        assertEquals(FailureKind.PROCESS_KILLED, top("[12:00:00] [Render thread/INFO]: Loaded", 137).kind)
        assertEquals(FailureKind.UNKNOWN, top("nothing useful", 1).kind)
        assertTrue(CrashAnalyzer.analyze("all good", 0).isEmpty())
        assertTrue(CrashAnalyzer.analyze("all good").isEmpty())
    }

    @Test fun orderedByConfidenceAndRedacted() {
        val text = "Launching with --accessToken eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop\nGLFW error 65542: WGL: The driver does not appear to support OpenGL\njava.lang.OutOfMemoryError: Java heap space"
        val ds = CrashAnalyzer.analyze(text)
        assertEquals(FailureKind.OUT_OF_MEMORY, ds.first().kind)
        assertTrue(ds.any { it.kind == FailureKind.GRAPHICS_INIT })
        val all = ds.flatMap { it.evidence }.joinToString("\n")
        assertFalse(all.contains("eyJhbGciOiJIUzI1NiJ9"), "token leaked into evidence: $all")
        assertTrue(ds.flatMap { it.evidence }.all { it.length <= 300 })
    }

    @Test fun collectsNewestCrashReportAndHsErr() {
        val g = tempDir("game"); Files.createDirectories(g.resolve("crash-reports"))
        val old = g.resolve("crash-reports/crash-old.txt"); Files.writeString(old, "OLD REPORT")
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 86_400_000))
        Files.writeString(g.resolve("crash-reports/crash-new.txt"), "Description: Rendering overlay\njava.lang.OutOfMemoryError")
        Files.writeString(g.resolve("hs_err_pid123.log"), "SIGSEGV access_token=supersecretvalue")
        val log = tempDir("s").resolve("game.log"); Files.writeString(log, "x".repeat(2000) + "\nlast line")
        val ev = CrashAnalyzer.collectEvidence(g, log, maxBytes = 1000)
        assertTrue(ev.contains("last line")); assertTrue(ev.contains("Rendering overlay")); assertFalse(ev.contains("OLD REPORT"))
        assertTrue(ev.contains("SIGSEGV")); assertFalse(ev.contains("supersecretvalue"))
    }

    @Test fun repairEngineRefusesDestructiveActionsAndOnlyCleansTempFiles() = kotlinx.coroutines.runBlocking {
        val root = tempDir("root"); val dirs = LauncherDirs(root)
        val http = pro.cslauncher.core.net.UrlConnectionTransport()
        val dm = pro.cslauncher.core.download.DownloadManager(http)
        val inst = pro.cslauncher.core.install.Installer(dirs, http, dm, PlatformInfo.host())
        val engine = RepairEngine(dirs, inst, dm, PlatformInfo.host())
        assertFailsWith<IllegalArgumentException> { engine.execute(RepairAction.DisableMod("x"), "1.20.1", null) }
        val world = root.resolve("game/saves/W/level.dat"); Files.createDirectories(world.parent); Files.writeString(world, "keep")
        val part = root.resolve("libraries/a/b.jar.part"); Files.createDirectories(part.parent); Files.writeString(part, "partial")
        val userPart = root.resolve("game/saves/W/notes.part"); Files.writeString(userPart, "user file")
        val r = engine.execute(RepairAction.RemoveTempFiles, "1.20.1", null)
        assertTrue(r.success); assertFalse(Files.exists(part)); assertTrue(Files.exists(world), "user data untouched")
        assertTrue(Files.exists(userPart), "game directories are never scanned")
        assertTrue(Files.readString(dirs.config.resolve("repair-journal.log")).contains("partial"))
    }
}

class RendererSelectionTest {
    private val android = PlatformInfo.android("arm64-v8a", "13")
    private fun libs(vararg names: String): Map<String, Path> { val d = tempDir("libs"); return names.associateWith { n -> d.resolve(n).also { Files.write(it, ByteArray(4)) } } }
    private fun env(gl32: Boolean = true, vulkan: String? = "1.1.128", gles: Int = 32, l: Map<String, Path> = libs("libgl4es.so", "libOSMesa.so"), lwjgl3: Boolean = true) =
        RendererEnvironment(android, 33, gles, vulkan, l, if (gl32) "1.20.1" else "1.16.5", lwjgl3, gl32)

    @Test fun autoPicksZinkForModernAndGl4esForLegacy() {
        val reg = RendererRegistry()
        assertEquals("zink", reg.decide(env(gl32 = true), "auto", emptyMap(), null, emptySet()).backend?.id)
        assertEquals("gl4es", reg.decide(env(gl32 = false), "auto", emptyMap(), null, emptySet()).backend?.id)
    }

    @Test fun noFakeSupport() {
        val reg = RendererRegistry()
        val noVk = reg.decide(env(gl32 = true, vulkan = null), "auto", emptyMap(), null, emptySet())
        assertNull(noVk.backend, "1.17+ without Vulkan has no renderer (GL4ES only provides 2.1)")
        assertTrue(noVk.reason.contains("Vulkan")); assertEquals(RendererState.GAME_UNSUPPORTED, noVk.statuses["gl4es"]!!.state)
        val missing = reg.statuses(env(l = emptyMap()))["zink"]!!
        assertEquals(RendererState.MISSING_COMPONENT, missing.state); assertEquals(listOf("libOSMesa.so"), missing.missing)
        assertEquals(RendererState.DEVICE_UNSUPPORTED, reg.statuses(env(vulkan = "1.0.61"))["zink"]!!.state)
        assertEquals(RendererState.GAME_UNSUPPORTED, reg.statuses(env(lwjgl3 = false))["zink"]!!.state)
        assertEquals(RendererState.NOT_APPLICABLE, reg.statuses(env())["system-gl"]!!.state)
    }

    @Test fun explicitChoiceIsHonouredOrRejectedNeverSwapped() {
        val reg = RendererRegistry()
        val d = reg.decide(env(gl32 = true), "gl4es", emptyMap(), null, emptySet())
        assertNull(d.backend); assertTrue(d.reason.contains("unavailable"))
        assertNull(reg.decide(env(), "magic", emptyMap(), null, emptySet()).backend)
        assertEquals("gl4es", reg.decide(env(gl32 = false), "gl4es", emptyMap(), null, emptySet()).backend?.id)
    }

    @Test fun memoryAndCrashLoopProtection() {
        val reg = RendererRegistry(); val e = env(gl32 = false, l = libs("libgl4es.so", "libOSMesa.so"))
        val fpZink = reg.fingerprint(e, "zink")
        val remembered = reg.decide(e, "auto", emptyMap(), "zink" to fpZink, emptySet())
        assertTrue(remembered.fromMemory); assertEquals("zink", remembered.backend?.id)
        val stale = reg.decide(e, "auto", emptyMap(), "zink" to "old-fingerprint", emptySet())
        assertFalse(stale.fromMemory); assertEquals("gl4es", stale.backend?.id)
        val afterCrash = reg.decide(e, "auto", emptyMap(), null, setOf(reg.fingerprint(e, "gl4es")))
        assertEquals("zink", afterCrash.backend?.id, "a renderer that failed for this exact setup is skipped")
    }

    @Test fun backendConfiguration() {
        val e = env(gl32 = false)
        val g = Gl4esBackend().configure(e, mapOf("LIBGL_ES" to "9", "UNKNOWN" to "x"))
        assertEquals(ContextKind.EGL_GLES2, g.contextKind); assertEquals("21", g.env["LIBGL_GL"])
        assertFalse(g.env.containsKey("UNKNOWN"), "unknown options are dropped"); assertNotEquals("9", g.env["LIBGL_ES"], "invalid choice replaced by default")
        assertEquals(e.libraries["libgl4es.so"].toString(), g.jvmProps["org.lwjgl.opengl.libname"])
        val z = ZinkBackend().configure(env(), emptyMap())
        assertEquals(ContextKind.OSMESA, z.contextKind); assertEquals("zink", z.env["GALLIUM_DRIVER"]); assertFalse(z.env.containsKey("MESA_DEBUG"))
    }
}

class RecordingPlannerTest {
    private fun enc(mime: String = RecordingPlanner.AVC, hw: Boolean = true, maxW: Int = 4096, maxH: Int = 2160, align: Int = 2, fps: (Int, Int) -> Double? = { _, _ -> 60.0 }) =
        EncoderCapability(mime, "c2.test.${mime.substringAfter('/')}", hw, maxW, maxH, align, align, 64_000..40_000_000, fps)

    @Test fun sizesAreAlignedAndNeverUpscaled() {
        val p = RecordingPlanner.plan(RecordingSettings(resolution = RecordingResolution.P720), 2400, 1080, listOf(enc()), 33, true)
        assertEquals(720, p.height); assertEquals(1600, p.width); assertEquals(0, p.width % 16); assertEquals(0, p.height % 16)
        val n = RecordingPlanner.plan(RecordingSettings(resolution = RecordingResolution.P1080), 1280, 600, listOf(enc()), 33, true)
        assertTrue(n.height <= 600 && n.width <= 1280)
        val portrait = RecordingPlanner.plan(RecordingSettings(resolution = RecordingResolution.P480), 1080, 2400, listOf(enc()), 33, true)
        assertTrue(portrait.height > portrait.width); assertEquals(480, portrait.width)
    }

    @Test fun stepsDownToEncoderLimitsAndFps() {
        val p = RecordingPlanner.plan(RecordingSettings(resolution = RecordingResolution.P1080), 2400, 1080, listOf(enc(maxW = 1920, maxH = 1088)), 33, true)
        assertTrue(p.width <= 1920); assertTrue(p.warnings.any { it.contains("reduced") })
        val f = RecordingPlanner.plan(RecordingSettings(resolution = RecordingResolution.P1080, fps = 60), 1920, 1080, listOf(enc(fps = { w, _ -> if (w > 1300) 30.0 else 60.0 })), 33, true)
        assertEquals(30, f.fps); assertEquals(1072, f.height, "16-aligned")
        assertFailsWith<RecordingUnsupportedException> { RecordingPlanner.plan(RecordingSettings(), 1920, 1080, listOf(enc(fps = { _, _ -> null })), 33, true) }
        assertFailsWith<RecordingUnsupportedException> { RecordingPlanner.plan(RecordingSettings(), 1920, 1080, emptyList(), 33, true) }
    }

    @Test fun bitrateHevcAndAudioRules() {
        val bal = RecordingPlanner.plan(RecordingSettings(quality = RecordingQuality.BALANCED, fps = 30), 1280, 720, listOf(enc()), 33, true)
        assertEquals((1280L * 720 * 30 * 0.09).toInt(), bal.bitrate)
        val hevc = RecordingPlanner.plan(RecordingSettings(preferHevc = true, fps = 30), 1280, 720, listOf(enc(), enc(RecordingPlanner.HEVC)), 33, true)
        assertEquals(RecordingPlanner.HEVC, hevc.mime); assertTrue(hevc.bitrate < bal.bitrate)
        val swHevc = RecordingPlanner.plan(RecordingSettings(preferHevc = true), 1280, 720, listOf(enc(), enc(RecordingPlanner.HEVC, hw = false)), 33, true)
        assertEquals(RecordingPlanner.AVC, swHevc.mime)
        val sw = RecordingPlanner.plan(RecordingSettings(), 1280, 720, listOf(enc(hw = false)), 33, true); assertTrue(sw.warnings.any { it.contains("software") })
        assertEquals(500_000, RecordingPlanner.plan(RecordingSettings(quality = RecordingQuality.CUSTOM, customBitrateKbps = 10), 1280, 720, listOf(enc()), 33, true).bitrate)
        assertEquals(AudioSourceChoice.NONE, RecordingPlanner.plan(RecordingSettings(audio = AudioSourceChoice.GAME_AUDIO), 1280, 720, listOf(enc()), 28, true).audio.source)
        assertEquals(AudioSourceChoice.MICROPHONE, RecordingPlanner.plan(RecordingSettings(audio = AudioSourceChoice.GAME_AND_MICROPHONE), 1280, 720, listOf(enc()), 28, true).audio.source)
        assertEquals(AudioSourceChoice.NONE, RecordingPlanner.plan(RecordingSettings(audio = AudioSourceChoice.MICROPHONE), 1280, 720, listOf(enc()), 33, false).audio.source)
        assertEquals(AudioSourceChoice.GAME_AUDIO, RecordingPlanner.plan(RecordingSettings(audio = AudioSourceChoice.GAME_AUDIO), 1280, 720, listOf(enc()), 33, true).audio.source)
    }
}

class StorageTest {
    @Test fun clearsOnlyRegenerableDataAndKeepsRecentSessions() {
        val root = tempDir("st"); val dirs = LauncherDirs(root); val sa = StorageAnalyzer(dirs)
        repeat(8) { i -> val d = dirs.sessions.resolve("2026010${i}-x"); Files.createDirectories(d); Files.writeString(d.resolve("log.txt"), "l") }
        Files.createDirectories(dirs.cache); Files.write(dirs.cache.resolve("a.part"), ByteArray(100))
        val game = root.resolve("game"); Files.createDirectories(game.resolve("saves/W")); Files.write(game.resolve("saves/W/level.dat"), ByteArray(10))
        assertTrue(sa.clear(StorageCategory.CACHE) >= 100)
        sa.clear(StorageCategory.SESSIONS); assertEquals(5, Files.list(dirs.sessions).use { it.count() })
        assertTrue(Files.exists(dirs.sessions.resolve("20260107-x")), "newest kept")
        assertFailsWith<IllegalArgumentException> { sa.clear(StorageCategory.WORLDS) }
        assertEquals(10, sa.analyze(listOf(game)).first { it.category == StorageCategory.WORLDS }.bytes)
    }
}
