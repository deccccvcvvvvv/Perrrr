package pro.cslauncher.core

import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import pro.cslauncher.core.component.ComponentManager
import pro.cslauncher.core.controls.LayoutIds
import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.install.SafeTar
import pro.cslauncher.core.launch.AndroidRuntimeEnv
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.renderer.*
import pro.cslauncher.core.runtime.*
import pro.cslauncher.core.util.Hashing
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ProvisioningTest {
    private val android = PlatformInfo.android("arm64-v8a", "14")

    // ---- minimal ustar writer for test archives ----
    private fun tar(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, data) in entries) {
            val h = ByteArray(512)
            fun put(off: Int, s: String) { s.toByteArray().copyInto(h, off) }
            put(0, name); put(100, "0000644\u0000"); put(108, "0000000\u0000"); put(116, "0000000\u0000")
            put(124, String.format("%011o", data.size) + "\u0000"); put(136, "00000000000\u0000")
            h[156] = '0'.code.toByte(); put(257, "ustar\u0000"); put(263, "00")
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            val sum = h.sumOf { it.toInt() and 0xff }
            put(148, String.format("%06o", sum) + "\u0000 ")
            out.write(h); out.write(data); out.write(ByteArray((512 - data.size % 512) % 512))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }
    private fun xz(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> XZOutputStream(o, LZMA2Options(1)).use { it.write(b) } }.toByteArray()

    private fun fakeJre(major: Int) = xz(tar(mapOf(
        "./release" to "JAVA_VERSION=\"$major.0.1\"\nOS_ARCH=\"aarch64\"\nOS_NAME=\"Linux\"\n".toByteArray(),
        "./lib/server/libjvm.so" to "not really an ELF".toByteArray(),
        "./lib/libjava.so" to "x".toByteArray(),
    )))

    private fun componentZip(id: String, version: String, lwjgl: String = "3.3.3"): ByteArray {
        val lib = "fake native".toByteArray()
        val manifest = """{"formatVersion":1,"id":"$id","name":"$id","version":"$version","kind":"lwjgl","abi":"arm64-v8a","license":"BSD","source":"test",
            "provides":["lwjgl:$lwjgl"],"classpathJars":[],"libraryDir":"lib","files":[{"path":"lib/liblwjgl.so","sha256":"${Hashing.sha256(lib)}","size":${lib.size}}]}"""
        return ByteArrayOutputStream().also { o -> ZipOutputStream(o).use { z ->
            z.putNextEntry(ZipEntry("cs-component.json")); z.write(manifest.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("lib/liblwjgl.so")); z.write(lib); z.closeEntry()
        } }.toByteArray()
    }

    private class MapBundle(val index: BundleIndex, val files: Map<String, ByteArray>) : BundleSource {
        var opens = 0
        override fun index(abi: String) = index.takeIf { it.abi == abi }
        override fun open(abi: String, file: String): InputStream { opens++; return ByteArrayInputStream(files.getValue(file)) }
    }

    @Test fun safeTarExtractsXz() {
        val dir = tempDir(); val archive = dir.resolve("a.tar.xz")
        Files.write(archive, xz(tar(mapOf("hello/world.txt" to "hi".toByteArray()))))
        SafeTar.extract(archive, dir.resolve("out"))
        assertEquals("hi", Files.readAllLines(dir.resolve("out/hello/world.txt")).single())
    }

    @Test fun bundledRuntimeIsVerifiedAndInstalledOnce() {
        val dirs = LauncherDirs(tempDir()); dirs.createBase()
        val jre = fakeJre(21)
        val bundle = MapBundle(BundleIndex(abi = "arm64-v8a", runtimes = listOf(BundledRuntime("jre21", 21, "21.0.1", "runtimes/jre21.tar.xz", Hashing.sha256(jre), jre.size.toLong(), "test"))),
            mapOf("runtimes/jre21.tar.xz" to jre))
        val rm = RuntimeManager(dirs, android)
        val pv = AutoProvisioner(dirs, android, rm, ComponentManager(dirs), bundle, null, null)
        val messages = mutableListOf<String>()
        pv.ensureRuntime(21) { messages += it }
        val rt = rm.list().single()
        assertEquals(21, rt.major); assertNotNull(rt.libjvm()); assertTrue(messages.any { "Java 21" in it })
        pv.ensureRuntime(21) {}                     // already installed: no second copy
        assertEquals(1, bundle.opens)
    }

    @Test fun corruptBundledRuntimeIsRejected() {
        val dirs = LauncherDirs(tempDir()); dirs.createBase()
        val jre = fakeJre(17)
        val bundle = MapBundle(BundleIndex(abi = "arm64-v8a", runtimes = listOf(BundledRuntime("jre17", 17, "17", "r.tar.xz", "0".repeat(64), 1, "test"))), mapOf("r.tar.xz" to jre))
        val rm = RuntimeManager(dirs, android)
        assertFails { AutoProvisioner(dirs, android, rm, ComponentManager(dirs), bundle, null, null).ensureRuntime(17) {} }
        assertTrue(rm.list().isEmpty())
    }

    @Test fun targetMajorPicksSmallestSufficientRuntime() {
        val pv = AutoProvisioner(LauncherDirs(tempDir()), android, RuntimeManager(LauncherDirs(tempDir()), android), ComponentManager(LauncherDirs(tempDir())), null, null, null)
        val offered = listOf(8, 17, 21, 25)
        assertEquals(8, pv.targetMajor(8, offered)); assertEquals(17, pv.targetMajor(16, offered))
        assertEquals(21, pv.targetMajor(21, offered)); assertEquals(25, pv.targetMajor(22, offered)); assertNull(pv.targetMajor(26, offered))
        assertEquals("arm64", pv.releaseArch())
    }

    @Test fun bundledComponentsInstallAndUpgrade() {
        val dirs = LauncherDirs(tempDir()); dirs.createBase()
        val cm = ComponentManager(dirs)
        fun bundleOf(v: String): MapBundle { val z = componentZip("lwjgl3-android", v)
            return MapBundle(BundleIndex(abi = "arm64-v8a", components = listOf(BundledComponent("lwjgl3-android", v, "components/l.zip", Hashing.sha256(z), z.size.toLong()))), mapOf("components/l.zip" to z)) }
        val b1 = bundleOf("3.3.3-r1")
        AutoProvisioner(dirs, android, RuntimeManager(dirs, android), cm, b1, null, null).ensureComponents {}
        assertEquals("3.3.3-r1", cm.lwjgl("arm64-v8a")!!.manifest.version)
        AutoProvisioner(dirs, android, RuntimeManager(dirs, android), cm, b1, null, null).ensureComponents {}
        assertEquals(1, b1.opens)                    // same version: untouched
        AutoProvisioner(dirs, android, RuntimeManager(dirs, android), cm, bundleOf("3.3.3-r2"), null, null).ensureComponents {}
        assertEquals("3.3.3-r2", cm.lwjgl("arm64-v8a")!!.manifest.version)
    }

    @Test fun lwjglComponentFollowsTheGamesLwjgl() {
        val dirs = LauncherDirs(tempDir()); dirs.createBase()
        val cm = ComponentManager(dirs)
        val z33 = componentZip("lwjgl3-android", "3.3.3-r1", "3.3.3"); val z34 = componentZip("lwjgl34-android", "3.4.3-r1", "3.4.3")
        val b = MapBundle(BundleIndex(abi = "arm64-v8a", components = listOf(
            BundledComponent("lwjgl3-android", "3.3.3-r1", "components/a.zip", Hashing.sha256(z33), z33.size.toLong()),
            BundledComponent("lwjgl34-android", "3.4.3-r1", "components/b.zip", Hashing.sha256(z34), z34.size.toLong()))),
            mapOf("components/a.zip" to z33, "components/b.zip" to z34))
        AutoProvisioner(dirs, android, RuntimeManager(dirs, android), cm, b, null, null).ensureComponents {}
        assertEquals(2, cm.list().size)                                        // both kept side by side
        assertEquals("lwjgl3-android", cm.lwjgl("arm64-v8a", "3.2.2")!!.manifest.id)    // 1.16
        assertEquals("lwjgl3-android", cm.lwjgl("arm64-v8a", "3.3.1")!!.manifest.id)    // 1.20.1
        assertEquals("lwjgl3-android", cm.lwjgl("arm64-v8a", "3.3.3")!!.manifest.id)    // 1.21.x
        assertEquals("lwjgl34-android", cm.lwjgl("arm64-v8a", "3.4.1")!!.manifest.id)   // 26.1 / 26.2 (GLFW)
        assertEquals("lwjgl34-android", cm.lwjgl("arm64-v8a", "3.4.3")!!.manifest.id)   // 26.3 (SDL3)
        assertEquals("lwjgl34-android", cm.lwjgl("arm64-v8a", "3.5.0")!!.manifest.id)   // newest; LwjglCompat explains
        assertEquals("lwjgl3-android", cm.lwjgl("arm64-v8a")!!.manifest.id)
        assertNull(cm.lwjgl("x86_64", "3.3.3"))
    }

    @Test fun sdlGamesUseMobileGluesNotZink() {
        val dir = tempDir(); fun lib(n: String): Path = Files.write(dir.resolve(n), byteArrayOf(1))
        val libs = mapOf("libmobileglues.so" to lib("libmobileglues.so"), "libgl4es.so" to lib("libgl4es.so"), "libOSMesa.so" to lib("libOSMesa.so"))
        val reg = RendererRegistry()
        val env = RendererEnvironment(android, 34, 32, "1.3.0", libs, "26.3", true, true, gameUsesSdl = true)
        val st = reg.statuses(env)
        assertEquals(RendererState.GAME_UNSUPPORTED, st.getValue("zink").state); assertTrue("MobileGlues" in st.getValue("zink").reason)
        assertEquals(RendererState.GAME_UNSUPPORTED, st.getValue("gl4es").state)
        assertEquals("mobileglues", reg.decide(env, "auto", emptyMap(), null, emptySet()).backend?.id)
        assertNull(reg.decide(env, "zink", emptyMap(), null, emptySet()).backend)          // explicit choice refused with a reason
        // GLFW-based 26.1/26.2 keep Zink available
        assertEquals(RendererState.AVAILABLE, reg.statuses(env.copy(gameVersionId = "26.1", gameUsesSdl = false)).getValue("zink").state)
    }

    @Test fun androidRuntimeEnvironment() {
        val e = AndroidRuntimeEnv.build("/data/rt/jre21/lib/server/libjvm.so", 21, listOf("/data/comp/lwjgl/lib"), mapOf("gl" to "/data/comp/mg/lib/libmobileglues.so"),
            "/s/tmp", "/g", "/s/resolv.conf", "Asia/Kolkata", "14", listOf("-Xmx2g"))
        assertEquals("/data/rt/jre21/lib/server/libjvm.so", e.preload.first().first)
        assertTrue(e.preload.any { it.first == "/data/rt/jre21/lib/libjava.so" && it.second })
        assertEquals("/data/rt/jre21", e.env["JAVA_HOME"])
        assertTrue(e.linkerPath.split(':').containsAll(listOf("/data/rt/jre21/lib", "/data/comp/lwjgl/lib", "/data/comp/mg/lib")))
        assertTrue("-Djava.home=/data/rt/jre21" in e.jvmOptions); assertTrue("-Dext.net.resolvPath=/s/resolv.conf" in e.jvmOptions)
        assertTrue("-Duser.timezone=Asia/Kolkata" in e.jvmOptions)
        // Java 8 layout: lib/aarch64/server/libjvm.so
        val e8 = AndroidRuntimeEnv.build("/data/rt/jre8/lib/aarch64/server/libjvm.so", 8, emptyList(), emptyMap(), "/t", "/g", "/r", "UTC", "14", emptyList())
        assertEquals("/data/rt/jre8", e8.env["JAVA_HOME"]); assertTrue(e8.preload.any { it.first == "/data/rt/jre8/lib/aarch64/libjava.so" })
        // user-provided properties win
        val eu = AndroidRuntimeEnv.build("/r/lib/server/libjvm.so", 17, emptyList(), emptyMap(), "/t", "/g", "/r", "UTC", "14", listOf("-Duser.timezone=Europe/Berlin"))
        assertTrue(eu.jvmOptions.none { it.startsWith("-Duser.timezone") })
        assertEquals("nameserver 8.8.8.8\nnameserver 1.1.1.1\n", AndroidRuntimeEnv.resolvConf(emptyList()))
        assertEquals("nameserver 192.168.1.1\n", AndroidRuntimeEnv.resolvConf(listOf("192.168.1.1", "fe80::1%wlan0")))
    }

    @Test fun mobileGluesIsPreferredForModernVersions() {
        val dir = tempDir(); fun lib(n: String): Path = Files.write(dir.resolve(n), byteArrayOf(1))
        val libs = mapOf("libmobileglues.so" to lib("libmobileglues.so"), "libgl4es.so" to lib("libgl4es.so"))
        val reg = RendererRegistry()
        val modern = reg.decide(RendererEnvironment(android, 34, 32, "1.1.0", libs, "1.21.1", true, true), "auto", emptyMap(), null, emptySet())
        assertEquals("mobileglues", modern.backend?.id); assertEquals(ContextKind.EGL_DESKTOP, modern.config?.contextKind)
        assertEquals(modern.config?.glLibrary, modern.config?.eglLibrary)
        val old = reg.decide(RendererEnvironment(android, 34, 32, "1.1.0", libs, "1.16.5", true, false), "auto", emptyMap(), null, emptySet())
        assertEquals("gl4es", old.backend?.id)
        val es2 = reg.statuses(RendererEnvironment(android, 34, 20, null, libs, "1.21.1", true, true))
        assertEquals(RendererState.DEVICE_UNSUPPORTED, es2.getValue("mobileglues").state)
    }

    @Test fun layoutIdsAreSafeAndUnique() {
        assertEquals("my-pvp-layout", LayoutIds.fromName("My PvP layout!", emptySet()))
        assertEquals("layout-2", LayoutIds.fromName("", setOf("layout")))
    }

    @Test fun lwjglCompatibilityGate() {
        fun c(a: String, v: String) = pro.cslauncher.core.meta.MavenCoordinate("org.lwjgl", a, v, null, "jar")
        assertNull(pro.cslauncher.core.component.LwjglCompat.problem(listOf(c("lwjgl", "3.3.3"), c("lwjgl-glfw", "3.3.3")), "3.3.3"))
        assertNull(pro.cslauncher.core.component.LwjglCompat.problem(listOf(c("lwjgl", "3.3.1"), c("lwjgl-glfw", "3.3.1")), "3.3.3"))
        assertNull(pro.cslauncher.core.component.LwjglCompat.problem(listOf(c("lwjgl", "3.2.2")), "3.3.3"))
        assertTrue("SDL3" in pro.cslauncher.core.component.LwjglCompat.problem(listOf(c("lwjgl", "3.4.3"), c("lwjgl-sdl", "3.4.3")), "3.3.3")!!)
        assertNotNull(pro.cslauncher.core.component.LwjglCompat.problem(listOf(c("lwjgl", "3.4.0")), "3.3.3"))
        // with the LWJGL 3.4 Android build: 26.1/26.2 (GLFW) and 26.3 (SDL3) are accepted
        assertNull(pro.cslauncher.core.component.LwjglCompat.problem(listOf(c("lwjgl", "3.4.1"), c("lwjgl-glfw", "3.4.1")), "3.4.3"))
        assertNull(pro.cslauncher.core.component.LwjglCompat.problem(listOf(c("lwjgl", "3.4.3"), c("lwjgl-sdl", "3.4.3")), "3.4.3"))
        assertNotNull(pro.cslauncher.core.component.LwjglCompat.problem(listOf(c("lwjgl", "3.5.0"), c("lwjgl-sdl", "3.5.0")), "3.4.3"))
        assertTrue(pro.cslauncher.core.component.LwjglCompat.usesSdl(listOf(c("lwjgl-sdl", "3.4.3"))))
        assertEquals("3.4.3", pro.cslauncher.core.component.LwjglCompat.gameVersion(listOf(c("lwjgl-sdl", "3.4.3"), c("lwjgl", "3.4.3"))))
    }
}
