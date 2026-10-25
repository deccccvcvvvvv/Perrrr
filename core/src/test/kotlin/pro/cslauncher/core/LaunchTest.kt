package pro.cslauncher.core

import kotlinx.coroutines.test.runTest
import pro.cslauncher.core.account.*
import pro.cslauncher.core.component.*
import pro.cslauncher.core.download.DownloadManager
import pro.cslauncher.core.install.*
import pro.cslauncher.core.launch.*
import pro.cslauncher.core.meta.VersionMetadata
import pro.cslauncher.core.net.UrlConnectionTransport
import pro.cslauncher.core.perf.DeviceFacts
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.profile.*
import pro.cslauncher.core.renderer.*
import pro.cslauncher.core.runtime.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LaunchTest {
    private val linux = PlatformInfo("linux", PlatformInfo.Arch.X86_64, "6.1", false)
    private val android = PlatformInfo.android("arm64-v8a", "14")
    private val http = UrlConnectionTransport()

    private fun fakeRuntime(dir: Path, major: Int, arch: String = "x86_64", android: Boolean = false): JavaRuntime {
        Files.createDirectories(dir.resolve("lib/server")); Files.writeString(dir.resolve("release"), "JAVA_VERSION=\"$major.0.1\"\nOS_ARCH=\"$arch\"\nIMPLEMENTOR=\"Test\"\n")
        Files.write(dir.resolve("lib/server/libjvm.so"), ByteArray(4))
        return RuntimeProbe.probe(dir, RuntimeSource.IMPORTED, android)!!
    }

    private val meta = VersionMetadata.parse(MetadataTest().modern)
    private val offline = LaunchSession("Steve", "0123456789abcdef0123456789abcdef", "0", "legacy", "0", "0", true)
    private val msa = LaunchSession("Alex", "fedcba9876543210fedcba9876543210", "SECRET-TOKEN-123", "msa", "2535", "cid", false)

    @Test fun desktopPlanFromMetadata() {
        val dirs = LauncherDirs(tempDir())
        val rv = Installer(dirs, http, DownloadManager(http), linux).resolveLibraries(meta, listOf("1.20.1"), "1.20.1")
        val rt = fakeRuntime(tempDir(), 17)
        Files.createDirectories(dirs.logConfigs); Files.writeString(dirs.logConfigs.resolve("client-1.12.xml"), "<x/>")
        val profile = Profile(name = "Test", versionId = "1.20.1", jvmArgs = "-XX:+UseG1GC")
        val plan = LaunchPlanner(dirs, linux).plan(LaunchInput(profile, rv, rt, msa, SystemGlBackend().configure(env(linux), emptyMap()), 2048, 1280, 720, tempDir()))
        assertEquals("net.minecraft.client.main.Main", plan.mainClass)
        assertTrue("-Xmx2048m" in plan.jvmArgs); assertTrue("-XX:+UseG1GC" in plan.jvmArgs)
        assertTrue(plan.jvmArgs.any { it.startsWith("-Dlog4j.configurationFile=") && it.endsWith("client-1.12.xml") })
        assertFalse(plan.jvmArgs.contains("-XstartOnFirstThread"), "osx-only argument must be filtered")
        assertFalse(plan.jvmArgs.contains("-Xss1M"), "x86-only argument must be filtered on x86_64")
        val cp = plan.jvmArgs[plan.jvmArgs.indexOf("-cp") + 1].split(File.pathSeparator)
        assertTrue(cp.last().endsWith("1.20.1.jar"), "client jar is last on the classpath")
        assertTrue(cp.any { it.contains("brigadier") }); assertTrue(cp.none { it.contains("java-objc-bridge") })
        assertEquals(listOf("--username", "Alex", "--width", "1280"), plan.gameArgs)
        assertFalse(plan.toString().contains("SECRET-TOKEN-123"), "token never stored in the plan")
        assertFalse(plan.redactedCommandLine().contains("SECRET"))
    }

    @Test fun legacyArgumentsAndTokenPlaceholder() {
        val dirs = LauncherDirs(tempDir())
        val lm = VersionMetadata.parse(MetadataTest().legacy)
        val rv = Installer(dirs, http, DownloadManager(http), linux).resolveLibraries(lm, listOf("1.8.9"), "1.8.9")
        val plan = LaunchPlanner(dirs, linux).plan(LaunchInput(Profile(name = "L", versionId = "1.8.9"), rv, fakeRuntime(tempDir(), 8), msa, SystemGlBackend().configure(env(linux), emptyMap()), 1024, 0, 0, tempDir()))
        assertTrue(plan.jvmArgs.contains("-cp")); assertTrue(plan.jvmArgs.any { it.startsWith("-Djava.library.path=") })
        val i = plan.gameArgs.indexOf("--accessToken"); assertEquals(LaunchPlan.TOKEN_PLACEHOLDER, plan.gameArgs[i + 1])
        assertEquals("SECRET-TOKEN-123", plan.withSecret("SECRET-TOKEN-123").gameArgs[i + 1])
        assertEquals("msa", plan.gameArgs[plan.gameArgs.indexOf("--userType") + 1])
    }

    @Test fun androidPlanSubstitutesLwjglAndAddsBridge() {
        val dirs = LauncherDirs(tempDir())
        val rv = Installer(dirs, http, DownloadManager(http), android).resolveLibraries(meta, listOf("1.20.1"), "1.20.1")
        val compDir = tempDir(); Files.createDirectories(compDir.resolve("lib"))
        val comp = InstalledComponent(ComponentManifest(1, "lwjgl-android", "LWJGL", "3.3.3", "lwjgl", "arm64-v8a", "BSD-3-Clause", "test", listOf("lwjgl:3.3.3"), listOf("jars/lwjgl.jar"), "lib", emptyList()), compDir)
        val gl4es = tempDir().resolve("libgl4es.so").also { Files.write(it, ByteArray(1)) }
        val renv = env(android, mapOf("libgl4es.so" to gl4es), needsGl32 = false)
        val plan = LaunchPlanner(dirs, android).plan(LaunchInput(Profile(name = "A", versionId = "1.20.1"), rv, fakeRuntime(tempDir(), 17, "aarch64", true), offline,
            Gl4esBackend().configure(renv, emptyMap()), 1536, 1920, 1080, tempDir(), comp, tempDir().resolve("libcsglfw.so")))
        val cp = plan.classpath
        assertTrue(cp.none { it.contains("org/lwjgl/lwjgl/3.3.1") }, "Mojang LWJGL replaced"); assertTrue(cp.any { it.endsWith("jars/lwjgl.jar") })
        assertTrue(plan.jvmArgs.any { it.startsWith("-Dorg.lwjgl.glfw.libname=") && it.endsWith("libcsglfw.so") })
        assertTrue(plan.jvmArgs.any { it == "-Dorg.lwjgl.opengl.libname=$gl4es" })
        assertEquals("21", plan.env["LIBGL_GL"]); assertEquals("EGL_GLES2", plan.rendererContext)
        assertTrue(plan.jvmArgs.none { it.startsWith("-Dorg.lwjgl.sdl.libname=") || it.startsWith("-Dorg.lwjgl.shaderc.libname=") }, "LWJGL 3.3: no 3.4 properties")
        val n = JvmOptionNormalizer.normalize(plan.jvmArgs)
        assertTrue(n.options.any { it.startsWith("-Djava.class.path=") }); assertFalse("-cp" in n.options)
    }

    @Test fun androidPlanForLwjgl34AddsSdlBridgeAndShaderToolchain() {
        val dirs = LauncherDirs(tempDir())
        val rv = Installer(dirs, http, DownloadManager(http), android).resolveLibraries(meta, listOf("1.20.1"), "1.20.1")
        val compDir = tempDir(); Files.createDirectories(compDir.resolve("lib"))
        val comp = InstalledComponent(ComponentManifest(1, "lwjgl34-android", "LWJGL", "3.4.3-r1", "lwjgl", "arm64-v8a", "BSD-3-Clause", "test",
            listOf("lwjgl:3.4.3", "lib:libshaderc.so", "lib:libspirv-cross.so"), listOf("jars/lwjgl.jar"), "lib", emptyList()), compDir)
        val mg = tempDir().resolve("libmobileglues.so").also { Files.write(it, ByteArray(1)) }
        val renv = env(android, mapOf("libmobileglues.so" to mg), needsGl32 = true)
        val shim = tempDir().resolve("libcsglfw.so")
        val plan = LaunchPlanner(dirs, android).plan(LaunchInput(Profile(name = "B", versionId = "26.3"), rv, fakeRuntime(tempDir(), 25, "aarch64", true), offline,
            MobileGluesBackend().configure(renv, emptyMap()), 2048, 1920, 1080, tempDir(), comp, shim))
        val lib = compDir.resolve("lib").toAbsolutePath()
        assertTrue("-Dorg.lwjgl.sdl.libname=${shim.toAbsolutePath()}" in plan.jvmArgs, "SDL3 calls go to the bridge in libcsglfw.so")
        assertTrue("-Dorg.lwjgl.glfw.libname=${shim.toAbsolutePath()}" in plan.jvmArgs, "26.1/26.2 still use GLFW")
        assertTrue("-Dorg.lwjgl.shaderc.libname=${lib.resolve("libshaderc.so")}" in plan.jvmArgs)
        assertTrue("-Dorg.lwjgl.spvc.libname=${lib.resolve("libspirv-cross.so")}" in plan.jvmArgs)
        val vk = plan.jvmArgs.single { it.startsWith("-Dorg.lwjgl.vulkan.libname=") }.substringAfter('=')
        assertFalse(Files.exists(java.nio.file.Paths.get(vk)), "Vulkan deliberately unavailable -> Minecraft stays on OpenGL")
        // AndroidRuntimeEnv must not override it with the system loader
        val e = AndroidRuntimeEnv.build("/r/lib/server/libjvm.so", 25, listOf(lib.toString()), emptyMap(), "/t", "/g", "/r", "UTC", "14", plan.jvmArgs)
        assertTrue(e.jvmOptions.none { it.startsWith("-Dorg.lwjgl.vulkan.libname=") })
    }

    @Test fun invalidUserJvmArgsAreRejected() {
        val dirs = LauncherDirs(tempDir())
        val rv = Installer(dirs, http, DownloadManager(http), linux).resolveLibraries(meta, listOf("1.20.1"), "1.20.1")
        assertFailsWith<LaunchPlanException> {
            LaunchPlanner(dirs, linux).plan(LaunchInput(Profile(name = "x", versionId = "1.20.1", jvmArgs = "-cp evil.jar"), rv, fakeRuntime(tempDir(), 17), offline, SystemGlBackend().configure(env(linux), emptyMap()), 1024, 0, 0, tempDir()))
        }
    }

    @Test fun normalizerHandlesModuleOptions() {
        val r = JvmOptionNormalizer.normalize(listOf("-cp", "a:b", "--add-opens", "java.base/java.lang=ALL-UNNAMED", "-XstartOnFirstThread", "-Xmx1g"))
        assertEquals(listOf("-Djava.class.path=a:b", "--add-opens=java.base/java.lang=ALL-UNNAMED", "-Xmx1g"), r.options)
        assertEquals(listOf("-XstartOnFirstThread"), r.dropped)
    }

    @Test fun stateMachineTransitionsAndFailureStage() {
        val sm = LaunchStateMachine()
        sm.advance(LaunchState.VALIDATING); sm.advance(LaunchState.PREPARING_RUNTIME)
        assertFailsWith<IllegalLaunchTransition> { sm.advance(LaunchState.VALIDATING) }
        sm.fail("no runtime")
        assertEquals(LaunchState.FAILED, sm.state); assertEquals(LaunchState.PREPARING_RUNTIME, sm.status.value.failedAt)
        sm.advance(LaunchState.VALIDATING) // retry allowed after failure
        listOf(LaunchState.PREPARING_RUNTIME, LaunchState.PREPARING_RENDERER, LaunchState.INITIALIZING_GAME, LaunchState.LOADING_GAME, LaunchState.RUNNING, LaunchState.EXITED).forEach { sm.advance(it) }
        assertEquals(LaunchState.EXITED, sm.state)
    }

    @Test fun launchLocksPreventDuplicates() {
        assertTrue(LaunchLocks.tryAcquire("p1")); assertFalse(LaunchLocks.tryAcquire("p1")); LaunchLocks.release("p1"); assertTrue(LaunchLocks.tryAcquire("p1")); LaunchLocks.release("p1")
    }

    @Test fun startupMilestonesFromRealLogLines() {
        val seen = mutableListOf<Milestone>()
        val m = StartupMonitor { ms, _ -> seen += ms }
        listOf("[12:00:00] [main/INFO]: Setting user: Steve", "[Render thread/INFO]: Backend library: LWJGL version 3.3.1 SNAPSHOT",
            "[Render thread/INFO]: Reloading ResourceManager: vanilla", "[Render thread/INFO]: Sound engine started",
            "[Render thread/INFO]: Created: 1024x512x4 minecraft:textures/atlas/blocks.png-atlas").forEach(m::onLine)
        assertEquals(listOf(Milestone.JVM_STARTED, Milestone.MAIN_ENTERED, Milestone.GAME_INIT, Milestone.RENDER_INIT, Milestone.READY), seen)
        assertEquals(LaunchState.RUNNING, m.stateFor())
        val c = StartupMonitor(); c.onLine("Error: Could not find or load main class net.minecraft.client.main.Main")
        assertTrue(Milestone.CRASH_DETECTED in c.milestones)
    }

    @Test fun preflightReportsSpecificCorrectiveActions() = runTest {
        val dirs = LauncherDirs(tempDir()); dirs.createBase()
        val inst = Installer(dirs, http, DownloadManager(http), linux)
        val coord = LaunchCoordinator(dirs, linux, inst, RuntimeManager(dirs, linux), RendererRegistry(), null)
        val req = LaunchRequest(Profile(name = "P", versionId = "1.20.1"), { offline }, { env(linux) }, DeviceFacts(8192, true, 0, 8, false), 854, 480)
        val e = assertFailsWith<LaunchBlockedException> { coord.prepare(req, LaunchStateMachine()) }
        assertEquals(FixAction.INSTALL_VERSION, e.issues.first().fix)
        // Install metadata only: verification must request a repair, and the missing runtime is reported next.
        Files.createDirectories(dirs.versionDir("1.20.1")); Files.writeString(dirs.versionJson("1.20.1"), MetadataTest().modern)
        val sm = LaunchStateMachine()
        val e2 = assertFailsWith<LaunchBlockedException> { coord.prepare(req, sm) }
        assertTrue(e2.issues.any { it.fix == FixAction.REPAIR_INSTALLATION })
        assertEquals(LaunchState.VALIDATING, sm.status.value.failedAt)
        val noAccount = req.copy(session = { throw AuthException("expired") })
        assertTrue(assertFailsWith<LaunchBlockedException> { coord.prepare(noAccount, LaunchStateMachine()) }.issues.any { it.fix == FixAction.SIGN_IN })
    }

    companion object {
        fun env(p: PlatformInfo, libs: Map<String, Path> = emptyMap(), needsGl32: Boolean = true, vulkan: String? = "1.1.0", gles: Int = 32) =
            RendererEnvironment(p, 34, gles, vulkan, libs, "1.20.1", true, needsGl32)
    }
}
