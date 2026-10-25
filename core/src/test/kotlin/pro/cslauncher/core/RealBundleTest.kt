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
        assumeTrue("set -PbundleDir to run", root.isNotBlank() && File(root, "bundled/arm64-v8a/index.json").isFile)
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
        for (r in idx.runtimes) {
            pv.ensureRuntime(r.major) {}
            val rt = rm.list().single { it.major == r.major }
            val jvm = rt.libjvm(); assertNotNull(jvm); assertTrue(Files.size(jvm) > 1_000_000, "libjvm.so looks truncated")
            println("installed ${rt.id} ${rt.version} at ${rt.home}")
        }
    }
}
