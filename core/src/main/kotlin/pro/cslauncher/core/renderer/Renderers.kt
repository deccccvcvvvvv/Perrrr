package pro.cslauncher.core.renderer

import pro.cslauncher.core.platform.PlatformInfo
import java.nio.file.Path

/** How the native layer must create the GL context for a backend. */
enum class ContextKind { EGL_GLES2, EGL_GLES3, OSMESA, DESKTOP_GL, EGL_DESKTOP }

data class RendererEnvironment(
    val platform: PlatformInfo,
    val androidApi: Int,
    val glesVersion: Int,                       // e.g. 32 for OpenGL ES 3.2, 0 if unknown
    val vulkanApi: String?,                     // e.g. "1.1.128" or null when Vulkan is unavailable
    val libraries: Map<String, Path>,           // native libs from verified components
    val gameVersionId: String,
    val gameUsesLwjgl3: Boolean,
    val gameNeedsGl32Core: Boolean,             // Minecraft 1.17+ requires OpenGL 3.2 core profile
    val gameUsesSdl: Boolean = false,           // Minecraft 26.3+: SDL3 window, rendered through the EGL bridge only
)

enum class RendererState { AVAILABLE, MISSING_COMPONENT, DEVICE_UNSUPPORTED, GAME_UNSUPPORTED, NOT_APPLICABLE }
data class RendererStatus(val state: RendererState, val reason: String, val missing: List<String> = emptyList()) {
    val usable get() = state == RendererState.AVAILABLE
}

enum class OptionType { BOOL, CHOICE }
data class RendererOption(val key: String, val label: String, val description: String, val type: OptionType, val default: String,
                          val choices: List<String> = emptyList(), val experimental: Boolean = false)

data class RendererConfig(
    val rendererId: String, val contextKind: ContextKind,
    val glLibrary: Path?, val eglLibrary: Path?, val osmesaLibrary: Path?,
    val env: Map<String, String>, val jvmProps: Map<String, String>, val notes: List<String> = emptyList(),
)

interface RendererBackend {
    val id: String
    val displayName: String
    val description: String
    val license: String
    val requiredLibraries: List<String>
    val options: List<RendererOption>
    fun status(env: RendererEnvironment): RendererStatus
    fun configure(env: RendererEnvironment, options: Map<String, String>): RendererConfig

    /** Only declared options with valid values are passed on; everything else is dropped. */
    fun sanitize(options: Map<String, String>): Map<String, String> = this.options.associate { o ->
        val v = options[o.key]
        o.key to when {
            v == null -> o.default
            o.type == OptionType.BOOL -> if (v == "1" || v == "0") v else o.default
            else -> if (v in o.choices) v else o.default
        }
    }
    fun missingLibs(env: RendererEnvironment) = requiredLibraries.filterNot { it in env.libraries }
}

/** GL4ES: OpenGL 2.1 → OpenGL ES 2/3 translation (MIT). Cannot provide the GL 3.2 core profile needed by 1.17+. */
class Gl4esBackend : RendererBackend {
    override val id = "gl4es"
    override val displayName = "GL4ES"
    override val description = "Translates OpenGL 2.1 to OpenGL ES. Most compatible choice for Minecraft 1.13 – 1.16.5."
    override val license = "MIT"
    override val requiredLibraries = listOf("libgl4es.so")
    override val options = listOf(
        RendererOption("LIBGL_ES", "OpenGL ES backend", "GLES version used underneath GL4ES", OptionType.CHOICE, "2", listOf("2", "3")),
        RendererOption("LIBGL_MIPMAP", "Mipmap handling", "0 default, 1 force, 2 disable automipmap, 3 ignore", OptionType.CHOICE, "3", listOf("0", "1", "2", "3")),
        RendererOption("LIBGL_NOERROR", "Skip glGetError", "Faster, but hides GL errors from logs", OptionType.BOOL, "1"),
        RendererOption("LIBGL_SHRINK", "Texture shrink", "Halve large textures to save GPU memory", OptionType.CHOICE, "0", listOf("0", "1", "10", "11")),
        RendererOption("LIBGL_VSYNC", "VSync", "Synchronise buffer swaps with the display", OptionType.BOOL, "0"),
    )
    override fun status(env: RendererEnvironment): RendererStatus = when {
        !env.platform.isAndroid -> RendererStatus(RendererState.NOT_APPLICABLE, "Android only")
        !env.gameUsesLwjgl3 -> RendererStatus(RendererState.GAME_UNSUPPORTED, "Minecraft ${env.gameVersionId} uses LWJGL 2, which needs a compatibility layer that is not included")
        env.gameNeedsGl32Core -> RendererStatus(RendererState.GAME_UNSUPPORTED, "Minecraft ${env.gameVersionId} requires OpenGL 3.2 core; GL4ES provides 2.1")
        env.glesVersion in 1..19 -> RendererStatus(RendererState.DEVICE_UNSUPPORTED, "Requires OpenGL ES 2.0+")
        missingLibs(env).isNotEmpty() -> RendererStatus(RendererState.MISSING_COMPONENT, "Install a GL4ES component pack", missingLibs(env))
        else -> RendererStatus(RendererState.AVAILABLE, "Ready")
    }
    override fun configure(env: RendererEnvironment, options: Map<String, String>): RendererConfig {
        val o = sanitize(options)
        val gl = env.libraries.getValue("libgl4es.so")
        return RendererConfig(id, if (o["LIBGL_ES"] == "3") ContextKind.EGL_GLES3 else ContextKind.EGL_GLES2, gl, null, null,
            env = o + mapOf("LIBGL_GL" to "21", "LIBGL_NOINTOVLHACK" to "1"),
            jvmProps = mapOf("org.lwjgl.opengl.libname" to gl.toString()))
    }
}

/** GL4ES running on ANGLE (GLES over Vulkan). Useful on devices with broken GLES drivers. */
class Gl4esAngleBackend : RendererBackend {
    override val id = "gl4es-angle"
    override val displayName = "GL4ES on ANGLE (Vulkan)"
    override val description = "GL4ES with Google's ANGLE as the OpenGL ES driver. Try when the system GLES driver misrenders."
    override val license = "MIT (GL4ES), BSD-3-Clause (ANGLE)"
    override val requiredLibraries = listOf("libgl4es.so", "libEGL_angle.so", "libGLESv2_angle.so")
    override val options = listOf(RendererOption("LIBGL_NOERROR", "Skip glGetError", "Faster, hides GL errors", OptionType.BOOL, "1"))
    override fun status(env: RendererEnvironment): RendererStatus = when {
        !env.platform.isAndroid -> RendererStatus(RendererState.NOT_APPLICABLE, "Android only")
        !env.gameUsesLwjgl3 -> RendererStatus(RendererState.GAME_UNSUPPORTED, "LWJGL 2 versions are not supported")
        env.gameNeedsGl32Core -> RendererStatus(RendererState.GAME_UNSUPPORTED, "Requires OpenGL 3.2 core; GL4ES provides 2.1")
        env.vulkanApi == null -> RendererStatus(RendererState.DEVICE_UNSUPPORTED, "ANGLE needs Vulkan, which this device does not report")
        missingLibs(env).isNotEmpty() -> RendererStatus(RendererState.MISSING_COMPONENT, "Install GL4ES and ANGLE component packs", missingLibs(env))
        else -> RendererStatus(RendererState.AVAILABLE, "Ready (experimental)")
    }
    override fun configure(env: RendererEnvironment, options: Map<String, String>): RendererConfig {
        val gl = env.libraries.getValue("libgl4es.so"); val egl = env.libraries.getValue("libEGL_angle.so"); val gles = env.libraries.getValue("libGLESv2_angle.so")
        return RendererConfig(id, ContextKind.EGL_GLES3, gl, egl, null,
            env = sanitize(options) + mapOf("LIBGL_ES" to "3", "LIBGL_GL" to "21", "LIBGL_EGL" to egl.toString(), "LIBGL_GLES" to gles.toString()),
            jvmProps = mapOf("org.lwjgl.opengl.libname" to gl.toString()), notes = listOf("Experimental"))
    }
}

/**
 * MobileGlues (LGPL-2.1): desktop OpenGL 4.x front end over OpenGL ES 3.x with GLSL translation. It owns the EGL
 * front end (contexts are created through its eglCreateContext with EGL_OPENGL_API), so it is used as both the
 * EGL and the GL library. This is the default for Minecraft 1.17+, which needs a GL 3.2 core profile.
 */
class MobileGluesBackend : RendererBackend {
    override val id = "mobileglues"
    override val displayName = "MobileGlues"
    override val description = "OpenGL 4 on top of OpenGL ES 3.x. Recommended for Minecraft 1.17 and newer."
    override val license = "LGPL-2.1 (MobileGlues), Apache-2.0 (SPIRV-Cross), BSD/MIT (glslang)"
    override val requiredLibraries = listOf("libmobileglues.so")
    // Written to MobileGlues' own config.json (MG_DIR_PATH) by the game process, see [MobileGluesConfig].
    override val options = listOf(
        RendererOption("errorFilter", "Shader error filter", "Ignore GL errors from shader/program checks. Auto = partial for Minecraft 26.3+, which needs it (black screen otherwise), off for older versions.",
            OptionType.CHOICE, "auto", listOf("auto", "off", "partial", "full")),
        RendererOption("glVersion", "Reported OpenGL version", "Version MobileGlues reports to the game. Auto = 4.0.", OptionType.CHOICE, "auto", listOf("auto", "33", "40", "43", "46")),
        RendererOption("glslCache", "Shader cache", "Keep translated shaders (32 MB) so later launches load faster", OptionType.BOOL, "1"),
        RendererOption("dsa", "Direct state access", "Expose GL_ARB_direct_state_access (faster on some drivers, may misrender)", OptionType.BOOL, "0", experimental = true),
    )
    override fun status(env: RendererEnvironment): RendererStatus = when {
        !env.platform.isAndroid -> RendererStatus(RendererState.NOT_APPLICABLE, "Android only")
        !env.gameUsesLwjgl3 -> RendererStatus(RendererState.GAME_UNSUPPORTED, "LWJGL 2 versions are not supported")
        env.glesVersion in 1..29 -> RendererStatus(RendererState.DEVICE_UNSUPPORTED, "Requires OpenGL ES 3.0+, device reports ${env.glesVersion / 10}.${env.glesVersion % 10}")
        missingLibs(env).isNotEmpty() -> RendererStatus(RendererState.MISSING_COMPONENT, "Install the MobileGlues component pack", missingLibs(env))
        else -> RendererStatus(RendererState.AVAILABLE, if (env.glesVersion in 30..31) "Ready (ES ${env.glesVersion / 10}.${env.glesVersion % 10}; ES 3.2 works best)" else "Ready")
    }
    override fun configure(env: RendererEnvironment, options: Map<String, String>): RendererConfig {
        val mg = env.libraries.getValue("libmobileglues.so")
        val o = sanitize(options)
        return RendererConfig(id, ContextKind.EGL_DESKTOP, mg, mg, null, env = mapOf(MobileGluesConfig.ENV to MobileGluesConfig.json(o, env.gameUsesSdl)),
            jvmProps = mapOf("org.lwjgl.opengl.libname" to mg.toString(), "org.lwjgl.egl.libname" to mg.toString()))
    }
}

/**
 * MobileGlues reads config.json from MG_DIR_PATH (keys and enum values from its config/config.cpp and settings.h).
 * Without the file it disables error filtering, and Minecraft 26.3+ then renders only black after the loading screen
 * (MobileGlues release notes: "Ignore shader/program error is required to run 26.3-snapshot-3 and later").
 * The launcher passes the JSON through the plan environment; the game process writes the file before starting the VM.
 */
object MobileGluesConfig {
    const val ENV = "CS_MG_CONFIG"
    /** NoErrorConfig: 0 auto (= none), 1 disable, 2 level 1 (partial), 3 level 2 (full). */
    fun noErrorLevel(choice: String?, gameUsesSdl: Boolean): Int = when (choice) { "off" -> 1; "partial" -> 2; "full" -> 3; else -> if (gameUsesSdl) 2 else 0 }
    fun json(o: Map<String, String>, gameUsesSdl: Boolean): String {
        val gl = o["glVersion"]?.toIntOrNull() ?: 0          // 0 = MobileGlues default (4.0)
        val entries = linkedMapOf(
            "enableANGLE" to 0,                                // DisableIfPossible: ANGLE is not bundled with MobileGlues
            "enableNoError" to noErrorLevel(o["errorFilter"], gameUsesSdl),
            "enableExtComputeShader" to 0,
            "enableExtTimerQuery" to 0,
            "enableExtDirectStateAccess" to (if (o["dsa"] == "1") 1 else 0),
            "maxGlslCacheSize" to (if (o["glslCache"] == "0") 0 else 32),
            "angleDepthClearFixMode" to 0,
            "customGLVersion" to gl,
            "fsr1Setting" to 0,
            "hideMGEnvLevel" to 0,
        )
        return entries.entries.joinToString(",\n  ", "{\n  ", "\n}\n") { (k, v) -> "\"$k\": $v" }
    }
}

/** Mesa Zink (OpenGL 4.x over Vulkan) via OSMesa off-screen context, blitted to the Android window. */
class ZinkBackend : RendererBackend {
    override val id = "zink"
    override val displayName = "Zink (Mesa, Vulkan)"
    override val description = "Mesa's OpenGL-on-Vulkan driver. Provides OpenGL 3.2+ core for Minecraft 1.17 and newer."
    override val license = "MIT (Mesa)"
    override val requiredLibraries = listOf("libOSMesa.so")
    override val options = listOf(
        RendererOption("MESA_GL_VERSION_OVERRIDE", "Reported GL version", "Version string reported to the game", OptionType.CHOICE, "4.6", listOf("3.3", "4.3", "4.6")),
        RendererOption("ZINK_DESCRIPTORS", "Descriptor mode", "Mesa Zink descriptor management", OptionType.CHOICE, "auto", listOf("auto", "lazy", "db"), experimental = true),
        RendererOption("MESA_DEBUG", "Mesa debug output", "Verbose driver messages in the game log", OptionType.BOOL, "0"),
    )
    override fun status(env: RendererEnvironment): RendererStatus = when {
        !env.platform.isAndroid -> RendererStatus(RendererState.NOT_APPLICABLE, "Android only")
        !env.gameUsesLwjgl3 -> RendererStatus(RendererState.GAME_UNSUPPORTED, "LWJGL 2 versions are not supported")
        env.gameUsesSdl -> RendererStatus(RendererState.GAME_UNSUPPORTED, "Minecraft ${env.gameVersionId} uses SDL3 windowing, which the launcher renders through EGL; Zink (OSMesa) is not supported there yet. Use MobileGlues.")
        env.vulkanApi == null -> RendererStatus(RendererState.DEVICE_UNSUPPORTED, "Requires Vulkan 1.1+, which this device does not report")
        !vk11(env.vulkanApi) -> RendererStatus(RendererState.DEVICE_UNSUPPORTED, "Requires Vulkan 1.1+, device reports ${env.vulkanApi}")
        missingLibs(env).isNotEmpty() -> RendererStatus(RendererState.MISSING_COMPONENT, "Install a Mesa Zink component pack", missingLibs(env))
        else -> RendererStatus(RendererState.AVAILABLE, "Ready (experimental, driver dependent)")
    }
    private fun vk11(v: String): Boolean { val p = v.split('.').mapNotNull { it.toIntOrNull() }; return (p.getOrElse(0) { 0 } * 100 + p.getOrElse(1) { 0 }) >= 101 }
    override fun configure(env: RendererEnvironment, options: Map<String, String>): RendererConfig {
        val o = sanitize(options).toMutableMap()
        if (o["MESA_DEBUG"] == "0") o.remove("MESA_DEBUG")
        val osm = env.libraries.getValue("libOSMesa.so")
        return RendererConfig(id, ContextKind.OSMESA, osm, null, osm,
            env = o + mapOf("GALLIUM_DRIVER" to "zink", "MESA_LOADER_DRIVER_OVERRIDE" to "zink", "MESA_GLSL_CACHE_DIR" to ""),
            jvmProps = mapOf("org.lwjgl.opengl.libname" to osm.toString()), notes = listOf("Experimental"))
    }
}

/** Desktop OpenGL from the host system. Used by the host end-to-end test; not applicable on Android. */
class SystemGlBackend : RendererBackend {
    override val id = "system-gl"
    override val displayName = "System OpenGL (desktop)"
    override val description = "Native desktop OpenGL driver. Only for desktop hosts."
    override val license = "System"
    override val requiredLibraries = emptyList<String>()
    override val options = emptyList<RendererOption>()
    override fun status(env: RendererEnvironment) =
        if (env.platform.isAndroid) RendererStatus(RendererState.NOT_APPLICABLE, "Desktop only") else RendererStatus(RendererState.AVAILABLE, "Ready")
    override fun configure(env: RendererEnvironment, options: Map<String, String>) = RendererConfig(id, ContextKind.DESKTOP_GL, null, null, null, emptyMap(), emptyMap())
}

data class RendererDecision(val backend: RendererBackend?, val config: RendererConfig?, val reason: String, val statuses: Map<String, RendererStatus>, val fromMemory: Boolean = false)

class RendererRegistry(val backends: List<RendererBackend> = listOf(SystemGlBackend(), MobileGluesBackend(), Gl4esBackend(), ZinkBackend(), Gl4esAngleBackend())) {
    fun byId(id: String) = backends.firstOrNull { it.id == id }
    fun statuses(env: RendererEnvironment) = backends.associate { it.id to it.status(env) }

    fun fingerprint(env: RendererEnvironment, backendId: String): String =
        listOf(backendId, env.gameVersionId, env.platform.arch.name, env.androidApi, env.glesVersion, env.vulkanApi ?: "-",
            env.libraries.entries.sortedBy { it.key }.joinToString(",") { it.key + ":" + runCatching { java.nio.file.Files.size(it.value) }.getOrDefault(0) }).joinToString("|")

    /**
     * Picks a backend. Explicit choices are honoured or rejected with a reason (never silently swapped).
     * Automatic mode reuses the remembered backend only if the fingerprint is unchanged, skips backends that already
     * failed for this exact fingerprint (no crash loops) and otherwise follows the priority order.
     */
    fun decide(env: RendererEnvironment, requested: String, options: Map<String, String>, memo: Pair<String, String>?, failedFingerprints: Set<String>): RendererDecision {
        val st = statuses(env)
        if (requested != "auto") {
            val b = byId(requested) ?: return RendererDecision(null, null, "Unknown renderer '$requested'", st)
            val s = st.getValue(b.id)
            return if (s.usable) RendererDecision(b, b.configure(env, options), "Selected by profile", st)
                   else RendererDecision(null, null, "${b.displayName} is unavailable: ${s.reason}", st)
        }
        if (memo != null) {
            val (id, fp) = memo
            val b = byId(id)
            if (b != null && st[id]?.usable == true && fp == fingerprint(env, id) && fp !in failedFingerprints)
                return RendererDecision(b, b.configure(env, options), "Last successful renderer for this setup", st, fromMemory = true)
        }
        val order = if (env.gameNeedsGl32Core) listOf("system-gl", "mobileglues", "zink", "gl4es", "gl4es-angle")
                    else listOf("system-gl", "gl4es", "mobileglues", "gl4es-angle", "zink")
        for (id in order) {
            val b = byId(id) ?: continue
            if (st[id]?.usable == true && fingerprint(env, id) !in failedFingerprints) return RendererDecision(b, b.configure(env, options), "Automatically selected", st)
        }
        // Every compatible backend failed before on this setup. Blocking the launch would be a dead end (the earlier
        // failure may not have been the renderer's fault), so retry the preferred compatible backend and say so.
        order.firstOrNull { st[it]?.usable == true }?.let { id ->
            val b = byId(id)!!
            return RendererDecision(b, b.configure(env, options),
                "Retrying ${b.displayName}: it failed on an earlier launch, and no other compatible renderer is available", st)
        }
        val why = st.entries.filter { it.value.state != RendererState.NOT_APPLICABLE }.joinToString("; ") { "${byId(it.key)?.displayName}: ${it.value.reason}" }
        return RendererDecision(null, null, "No compatible renderer. $why", st)
    }
}
