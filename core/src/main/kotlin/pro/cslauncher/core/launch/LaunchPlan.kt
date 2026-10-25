package pro.cslauncher.core.launch

import kotlinx.serialization.Serializable
import pro.cslauncher.core.account.LaunchSession
import pro.cslauncher.core.component.InstalledComponent
import pro.cslauncher.core.component.LwjglCompat
import pro.cslauncher.core.install.*
import pro.cslauncher.core.meta.LaunchFeatures
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.profile.JvmArgs
import pro.cslauncher.core.profile.Profile
import pro.cslauncher.core.renderer.RendererConfig
import pro.cslauncher.core.runtime.JavaRuntime
import pro.cslauncher.core.util.Redactor
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Immutable, serialisable launch configuration. Created once per launch; profile edits during startup do not
 * affect it. The access token is NOT stored: [TOKEN_PLACEHOLDER] is substituted in memory via [withSecret].
 */
@Serializable
data class LaunchPlan(
    val planVersion: Int = 1,
    val profileId: String,
    val profileName: String,
    val versionId: String,
    val mainClass: String,
    val javaHome: String,
    val javaMajor: Int,
    val javaExecutable: String,
    val libjvm: String?,
    val classpath: List<String>,
    val nativeDirs: List<String>,
    val jvmArgs: List<String>,
    val gameArgs: List<String>,
    val workingDir: String,
    val env: Map<String, String>,
    val rendererId: String,
    val rendererContext: String,
    val rendererLibraries: Map<String, String>,
    val controlLayoutId: String,
    val sessionDir: String,
    val logFile: String,
    val width: Int,
    val height: Int,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    companion object { const val TOKEN_PLACEHOLDER = "@@CS_ACCESS_TOKEN@@" }
    fun withSecret(accessToken: String) = copy(gameArgs = gameArgs.map { it.replace(TOKEN_PLACEHOLDER, accessToken) })
    fun redactedCommandLine(): String = (listOf(javaExecutable) + Redactor.redactArgs(jvmArgs) + mainClass + Redactor.redactArgs(gameArgs)).joinToString(" ")
}

data class LaunchInput(
    val profile: Profile,
    val resolved: ResolvedVersion,
    val runtime: JavaRuntime,
    val session: LaunchSession,
    val renderer: RendererConfig,
    val memoryMb: Int,
    val width: Int,
    val height: Int,
    val sessionDir: Path,
    val lwjglComponent: InstalledComponent? = null, // Android: LWJGL jars + natives built for bionic
    val glfwShim: Path? = null,                     // Android: libcsglfw.so from the APK
    val launcherVersion: String = "1.0.0",
)

class LaunchPlanException(message: String) : Exception(message)

class LaunchPlanner(private val dirs: LauncherDirs, private val platform: PlatformInfo) {

    fun plan(input: LaunchInput): LaunchPlan {
        val rv = input.resolved; val meta = rv.meta; val p = input.profile
        val gameDir = p.gameDir(dirs)
        val nativesDir: Path = if (platform.isAndroid) input.lwjglComponent?.libDir ?: throw LaunchPlanException("LWJGL Android component is not installed")
                               else dirs.nativesDir(meta.id)

        // ---- classpath: metadata order, Android LWJGL substitution, client jar last ----
        val cp = LinkedHashMap<String, Path>()
        val lwjglJars = input.lwjglComponent?.jars().orEmpty()
        for (rl in rv.libraries.filter { it.onClasspath }) {
            val c = rl.library.coordinate
            if (platform.isAndroid && c.group == "org.lwjgl") continue
            cp.putIfAbsent(c.versionlessKey, rl.file)
        }
        if (platform.isAndroid) lwjglJars.forEach { cp.putIfAbsent("component:" + it.fileName, it) }
        cp["client"] = dirs.versionJar(rv.jarVersionId)
        val classpath = cp.values.map { it.toAbsolutePath().toString() }

        val features = LaunchFeatures(hasCustomResolution = input.width > 0 && input.height > 0)
        val assetIndexId = meta.assetIndex?.id ?: meta.assets ?: "legacy"
        val legacyAssets = dirs.assets.resolve("virtual").resolve(assetIndexId)
        val values = mapOf(
            "auth_player_name" to input.session.username,
            "auth_uuid" to input.session.uuid,
            "auth_access_token" to (if (input.session.isOffline) input.session.accessToken else LaunchPlan.TOKEN_PLACEHOLDER),
            "auth_session" to (if (input.session.isOffline) input.session.accessToken else "token:${LaunchPlan.TOKEN_PLACEHOLDER}:${input.session.uuid}"),
            "auth_xuid" to input.session.xuid,
            "clientid" to input.session.clientId,
            "user_type" to input.session.userType,
            "user_properties" to "{}",
            "version_name" to meta.id,
            "version_type" to meta.type.id.replace("modded", "release"),
            "game_directory" to gameDir.toAbsolutePath().toString(),
            "assets_root" to dirs.assets.toAbsolutePath().toString(),
            "game_assets" to (if (Files.isDirectory(legacyAssets)) legacyAssets else dirs.assets).toAbsolutePath().toString(),
            "assets_index_name" to assetIndexId,
            "resolution_width" to input.width.toString(),
            "resolution_height" to input.height.toString(),
            "natives_directory" to nativesDir.toAbsolutePath().toString(),
            "launcher_name" to "cslauncherpro",
            "launcher_version" to input.launcherVersion,
            "classpath" to classpath.joinToString(File.pathSeparator),
            "classpath_separator" to File.pathSeparator,
            "library_directory" to dirs.libraries.toAbsolutePath().toString(),
            "path" to (meta.logging?.let { dirs.logConfigs.resolve(it.fileId).toAbsolutePath().toString() } ?: ""),
        )
        val unresolved = linkedSetOf<String>()
        fun sub(s: String) = ArgumentBuilder.substitute(s, values, unresolved)

        // ---- JVM arguments ----
        val jvm = mutableListOf<String>()
        jvm += "-Xms${minOf(512, input.memoryMb)}m"; jvm += "-Xmx${input.memoryMb}m"
        if (meta.jvmArguments.isNotEmpty()) jvm += ArgumentBuilder.expand(meta.jvmArguments, platform, features).map(::sub)
        else { jvm += "-Djava.library.path=${values["natives_directory"]}"; jvm += "-cp"; jvm += values.getValue("classpath") }
        meta.logging?.let { lg -> if (Files.isRegularFile(dirs.logConfigs.resolve(lg.fileId))) jvm += sub(lg.argument) }
        jvm += "-Dminecraft.launcher.brand=cslauncherpro"
        jvm += "-Dminecraft.launcher.version=${input.launcherVersion}"
        jvm += "-Dlog4j2.formatMsgNoLookups=true"
        jvm += "-Djava.io.tmpdir=${input.sessionDir.resolve("tmp").toAbsolutePath()}"
        if (jvm.none { it.startsWith("-Dfile.encoding") }) jvm += "-Dfile.encoding=UTF-8"
        if (platform.isAndroid) {
            jvm += "-Dorg.lwjgl.librarypath=${nativesDir.toAbsolutePath()}"
            val shim = input.glfwShim ?: throw LaunchPlanException("GLFW integration library missing from the APK")
            jvm += "-Dorg.lwjgl.glfw.libname=${shim.toAbsolutePath()}"
            jvm += "-Dorg.lwjgl.util.NoChecks=true"
            jvm += "-Djava.awt.headless=true"
            val lw = input.lwjglComponent?.lwjglVersion
            if (lw != null && LwjglCompat.majorMinor(lw) >= LwjglCompat.majorMinor(LwjglCompat.SDL_BRIDGE_MIN)) {
                // LWJGL 3.4 (Minecraft 26.x). SDL3 calls go to the launcher's bridge inside libcsglfw.so; the shader
                // toolchain the 26.3 OpenGL backend uses (GLSL -> shaderc -> SPIR-V -> SPIRV-Cross -> GLSL) comes from
                // the component. Vulkan is not offered (the bridge renders through EGL): pointing LWJGL at a library
                // that does not exist makes VK.create() fail, which Minecraft handles by staying on OpenGL.
                jvm += "-Dorg.lwjgl.sdl.libname=${shim.toAbsolutePath()}"
                jvm += "-Dorg.lwjgl.shaderc.libname=${nativesDir.resolve("libshaderc.so").toAbsolutePath()}"
                jvm += "-Dorg.lwjgl.spvc.libname=${nativesDir.resolve("libspirv-cross.so").toAbsolutePath()}"
                jvm += "-Dorg.lwjgl.vulkan.libname=${nativesDir.resolve("vulkan-not-available.so").toAbsolutePath()}"
            }
        }
        input.renderer.jvmProps.forEach { (k, v) -> jvm += "-D$k=$v" }
        val userJvm = JvmArgs.tokenize(p.jvmArgs)
        val errors = JvmArgs.validate(userJvm).filter { it.severity == JvmArgs.Severity.ERROR }
        if (errors.isNotEmpty()) throw LaunchPlanException("Invalid JVM arguments: " + errors.joinToString { "${it.arg} (${it.message})" })
        jvm += userJvm

        // ---- game arguments ----
        val game = mutableListOf<String>()
        game += if (meta.usesModernArguments && meta.gameArguments.isNotEmpty()) ArgumentBuilder.expand(meta.gameArguments, platform, features).map(::sub)
                else ArgumentBuilder.legacy(meta.legacyArguments ?: throw LaunchPlanException("Version ${meta.id} defines no game arguments")).map(::sub)
        if (features.hasCustomResolution && meta.gameArguments.isEmpty()) game += listOf("--width", input.width.toString(), "--height", input.height.toString())
        game += JvmArgs.tokenize(p.gameArgs)

        if (unresolved.isNotEmpty()) throw LaunchPlanException("Unresolved launch placeholders: ${unresolved.joinToString()}")

        val env = LinkedHashMap<String, String>()
        env.putAll(input.renderer.env.filterValues { it.isNotEmpty() })
        env.putAll(p.env)
        val logFile = input.sessionDir.resolve("game-output.log")
        return LaunchPlan(
            profileId = p.id, profileName = p.name, versionId = meta.id, mainClass = meta.mainClass,
            javaHome = input.runtime.home.toAbsolutePath().toString(), javaMajor = input.runtime.major,
            javaExecutable = input.runtime.javaExecutable.toAbsolutePath().toString(), libjvm = input.runtime.libjvm()?.toAbsolutePath()?.toString(),
            classpath = classpath, nativeDirs = listOf(nativesDir.toAbsolutePath().toString()), jvmArgs = jvm, gameArgs = game,
            workingDir = gameDir.toAbsolutePath().toString(), env = env,
            rendererId = input.renderer.rendererId, rendererContext = input.renderer.contextKind.name,
            rendererLibraries = listOfNotNull(input.renderer.glLibrary?.let { "gl" to it }, input.renderer.eglLibrary?.let { "egl" to it },
                input.renderer.osmesaLibrary?.let { "osmesa" to it }).associate { it.first to it.second.toAbsolutePath().toString() },
            controlLayoutId = p.controlLayoutId, sessionDir = input.sessionDir.toAbsolutePath().toString(), logFile = logFile.toAbsolutePath().toString(),
            width = input.width, height = input.height,
        )
    }
}
