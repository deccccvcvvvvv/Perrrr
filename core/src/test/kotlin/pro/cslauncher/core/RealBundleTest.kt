package pro.cslauncher.core

import org.junit.Assume.assumeTrue
import pro.cslauncher.core.component.ComponentManager
import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.runtime.*
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.*

/**
 * Runs the real provisioning path against a bundle produced by tools/natives (real JRE .tar.xz and component zips).
 * Opt-in: ./gradlew :core:test -PbundleDir=<dir containing bundled/>
 */
class RealBundleTest {
    @Test fun provisionsFromRealBundle() {
        val root = System.getProperty("cs.bundleDir").orEmpty()
        assumeTrue("set -PbundleDir to run", root.isNotBlank())
        assertTrue(File(root, "bundled/arm64-v8a/index.json").isFile, "no bundle at $root (pass an absolute path)")
        val source = object : BundleSource {
            override fun index(abi: String) = File(root, "bundled/$abi/index.json").takeIf { it.isFile }?.let { AutoProvisioner.parseIndex(it.readText()) }
            override fun open(abi: String, file: String): InputStream = File(root, "bundled/$abi/$file").inputStream()
        }
        val android = PlatformInfo.android("arm64-v8a", "14")
        val dirs = LauncherDirs(tempDir()); dirs.createBase()
        val rm = RuntimeManager(dirs, android); val cm = ComponentManager(dirs)
        val pv = AutoProvisioner(dirs, android, rm, cm, source, null, null)
        val idx = source.index("arm64-v8a")!!
        pv.ensureComponents {}
        idx.components.forEach { c -> val ic = cm.list().single { it.manifest.id == c.id }; assertEquals(c.version, ic.manifest.version); assertTrue(cm.verify(ic).isEmpty()) }
        assertTrue(cm.availableLibraries("arm64-v8a").containsKey("libmobileglues.so") || idx.components.none { it.id == "mobileglues" })
        // LWJGL selection per game: 1.20.1 (3.3.1) and 1.21.x (3.3.3) -> 3.3 build; 26.x (3.4.x) -> 3.4 build with the
        // SDL3/shader toolchain natives
        if (idx.components.any { it.id == "lwjgl34-android" }) {
            assertEquals("lwjgl3-android", cm.lwjgl("arm64-v8a", "3.3.1")!!.manifest.id)
            val l34 = cm.lwjgl("arm64-v8a", "3.4.3")!!
            assertEquals("lwjgl34-android", l34.manifest.id); assertEquals("3.4.3", l34.lwjglVersion)
            val libs = l34.libraries().keys
            for (n in listOf("liblwjgl.so", "liblwjgl_opengl.so", "liblwjgl_stb.so", "libfreetype.so", "libopenal.so", "libshaderc.so", "libspirv-cross.so"))
                assertTrue(n in libs, "$n missing from lwjgl34-android")
            for (j in listOf("lwjgl-sdl-3.4.3.jar", "lwjgl-shaderc-3.4.3.jar", "lwjgl-spvc-3.4.3.jar", "lwjgl-glfw-3.4.3.jar"))
                assertTrue(l34.jars().any { it.fileName.toString() == j && Files.isRegularFile(it) }, "$j missing from lwjgl34-android")
            println("lwjgl34-android ${l34.manifest.version}: ${libs.sorted()}")
        }
        for (r in idx.runtimes) {
            pv.ensureRuntime(r.major) {}
            val rt = rm.list().single { it.major == r.major }
            val jvm = rt.libjvm(); assertNotNull(jvm); assertTrue(Files.size(jvm) > 1_000_000, "libjvm.so looks truncated")
            println("installed ${rt.id} ${rt.version} at ${rt.home}")
        }
    }
}
