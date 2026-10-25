package pro.cslauncher.core.launch

import java.io.File

/**
 * Everything the in-process OpenJDK needs on Android that a desktop `java` binary would normally get from
 * its launcher, the OS image or /etc:
 *  - absolute-path preloading of the runtime's own libraries (the app's linker namespace does not search
 *    the runtime directory, and HotSpot later dlopens libjava/libnet/... by bare name),
 *  - a linker search path covering runtime, LWJGL natives and renderer libraries,
 *  - java.home / user.home / tmpdir / timezone (Android has no TZ database where the JDK expects it),
 *  - a resolver configuration file, because Android has no /etc/resolv.conf and the Android OpenJDK
 *    builds read `ext.net.resolvPath` instead.
 *
 * Pure function of its inputs so it can be unit-tested on the host.
 */
data class AndroidRuntimeEnv(
    val preload: List<Pair<String, Boolean>>,   // absolute path -> required
    val linkerPath: String,
    val env: Map<String, String>,
    val jvmOptions: List<String>,
) {
    companion object {
        /** Load order respects DT_NEEDED: libjvm <- libjava <- (libnet, libzip, libjimage) <- libnio; libawt <- headless <- fontmanager. */
        val RUNTIME_LIBS = listOf(
            "libjvm.so" to true, "libverify.so" to false, "libjava.so" to true, "libjimage.so" to false,
            "libzip.so" to true, "libnet.so" to true, "libnio.so" to true, "libawt.so" to false,
            "libawt_headless.so" to false, "libfreetype.so" to false, "libfontmanager.so" to false,
        )

        fun build(
            libjvm: String, javaMajor: Int, nativeDirs: List<String>, rendererLibraries: Map<String, String>,
            sessionTmp: String, gameDir: String, resolvConf: String, timeZoneId: String, androidRelease: String,
            existingJvmArgs: List<String>,
        ): AndroidRuntimeEnv {
            val jvmFile = File(libjvm)
            val libDir = jvmFile.parentFile.parentFile                  // .../lib or .../lib/aarch64 (Java 8)
            // Java 8: <home>/lib/<arch>/server/libjvm.so ; Java 9+: <home>/lib/server/libjvm.so
            val javaHome = if (javaMajor <= 8) libDir.parentFile.parentFile else libDir.parentFile
            val preload = RUNTIME_LIBS.map { (name, req) ->
                (if (name == "libjvm.so") jvmFile else File(libDir, name)).absolutePath to req
            }
            val rendererDirs = rendererLibraries.values.map { File(it).parent }.filterNotNull()
            val linker = (listOf(jvmFile.parent, libDir.absolutePath, File(libDir, "jli").absolutePath) + nativeDirs + rendererDirs)
                .distinct().joinToString(":")
            val env = linkedMapOf(
                "JAVA_HOME" to javaHome.absolutePath,
                "LD_LIBRARY_PATH" to linker,
                "TMPDIR" to sessionTmp,
                "HOME" to gameDir,
            )
            fun has(prefix: String) = existingJvmArgs.any { it.startsWith(prefix) }
            val o = mutableListOf<String>()
            fun add(k: String, v: String) { if (!has("-D$k=")) o += "-D$k=$v" }
            add("java.home", javaHome.absolutePath)
            add("user.home", gameDir)
            add("user.timezone", timeZoneId)
            add("os.name", "Linux")
            add("os.version", "Android-$androidRelease")
            add("ext.net.resolvPath", resolvConf)
            add("org.lwjgl.system.allocator", "system")       // jemalloc is not shipped
            add("org.lwjgl.vulkan.libname", "libvulkan.so")
            add("fml.earlyprogresswindow", "false")           // Forge's early window needs a desktop GL context
            add("loader.disable_forked_guis", "true")         // Fabric/Quilt: no separate GUI process on Android
            add("jna.boot.library.path", nativeDirs.firstOrNull() ?: libDir.absolutePath)
            add("java.awt.headless", "true")
            add("sun.jnu.encoding", "UTF-8")
            if (!has("-XX:ErrorFile")) o += "-XX:ErrorFile=$sessionTmp/../hs_err_pid%p.log"
            return AndroidRuntimeEnv(preload, linker, env, o)
        }

        fun resolvConf(dnsServers: List<String>): String {
            val servers = dnsServers.filter { it.isNotBlank() && !it.contains('%') }.ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
            return servers.distinct().joinToString("") { "nameserver $it\n" }
        }
    }
}
