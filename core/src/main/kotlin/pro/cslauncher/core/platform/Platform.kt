package pro.cslauncher.core.platform

/**
 * Describes the platform for Mojang rule evaluation and native selection.
 * Android reports itself as Linux to the JVM, so rules are evaluated as "linux" but with [isAndroid] = true,
 * which makes the installer refuse glibc desktop natives and require Android-built native components instead.
 */
data class PlatformInfo(
    val osName: String,          // Mojang names: "linux", "windows", "osx"
    val arch: Arch,
    val osVersion: String,
    val isAndroid: Boolean,
) {
    enum class Arch(val mojangName: String, val bits: Int, val androidAbi: String?) {
        X86("x86", 32, "x86"), X86_64("x86_64", 64, "x86_64"), ARM32("arm32", 32, "armeabi-v7a"), ARM64("arm64", 64, "arm64-v8a");
        companion object {
            fun fromJvm(arch: String): Arch = when (arch.lowercase()) {
                "amd64", "x86_64", "x64" -> X86_64
                "x86", "i386", "i486", "i586", "i686" -> X86
                "aarch64", "arm64" -> ARM64
                else -> if (arch.lowercase().startsWith("arm")) ARM32 else X86_64
            }
            fun fromAndroidAbi(abi: String): Arch = when (abi) {
                "arm64-v8a" -> ARM64; "armeabi-v7a" -> ARM32; "x86" -> X86; "x86_64" -> X86_64
                else -> throw IllegalArgumentException("Unsupported ABI $abi")
            }
        }
    }

    val is64Bit get() = arch.bits == 64

    companion object {
        fun host(): PlatformInfo {
            val os = System.getProperty("os.name").lowercase()
            val name = when { os.contains("win") -> "windows"; os.contains("mac") -> "osx"; else -> "linux" }
            return PlatformInfo(name, Arch.fromJvm(System.getProperty("os.arch")), System.getProperty("os.version"), false)
        }
        fun android(abi: String, release: String) = PlatformInfo("linux", Arch.fromAndroidAbi(abi), release, true)
    }
}
