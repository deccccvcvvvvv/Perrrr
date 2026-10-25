package pro.cslauncher.core.launch

import kotlinx.serialization.Serializable

@Serializable enum class Milestone { JVM_STARTED, MAIN_ENTERED, GAME_INIT, RENDER_INIT, READY, CRASH_DETECTED }

/**
 * Derives startup milestones from game output. Patterns are version tolerant; READY uses the texture-atlas /
 * sound-engine markers that vanilla prints right before the title screen (strongest reliable indicator available
 * without injecting code into the game).
 */
class StartupMonitor(private val onMilestone: (Milestone, String) -> Unit = { _, _ -> }) {
    private val reached = linkedSetOf<Milestone>()
    val milestones: Set<Milestone> get() = reached
    var crashLine: String? = null; private set
    var lineCount = 0L; private set

    private val rules: List<Pair<Milestone, Regex>> = listOf(
        Milestone.MAIN_ENTERED to Regex("Setting user: |Loading Minecraft .* with Fabric Loader|Loading for game Minecraft|ModLauncher running|Launching wrapped minecraft|Starting minecraft client"),
        Milestone.GAME_INIT to Regex("Backend library: LWJGL|LWJGL Version: |Environment: authHost|Datafixer optimizations"),
        Milestone.RENDER_INIT to Regex("OpenGL|GL_VENDOR|Reloading ResourceManager|Narrator library"),
        Milestone.READY to Regex("Sound engine started|Created: \\d+x\\d+x?\\d* minecraft:textures/atlas/blocks\\.png-atlas|Created: \\d+x\\d+ textures-atlas"),
        // Only fatal markers. Non-fatal errors that vanilla recovers from (optional Narrator native library, missing audio
        // device, GLFW warnings, Realms/auth lookups) must not be reported as a crash; they are kept as log evidence.
        Milestone.CRASH_DETECTED to Regex("---- Minecraft Crash Report ----|#@!@# Game crashed!|Exception in thread \"(main|Render thread)\"|Error: Could not (create the Java Virtual Machine|find or load main class)|A fatal error has been detected by the Java Runtime|Failed to start the minecraft server|Unable to launch\\b|Unreported exception thrown!|Error occurred during initialization of VM"),
    )

    fun markJvmStarted() = hit(Milestone.JVM_STARTED, "JVM started")

    fun onLine(line: String) {
        lineCount++
        if (Milestone.JVM_STARTED !in reached) hit(Milestone.JVM_STARTED, line)
        for ((m, re) in rules) {
            if (m in reached && m != Milestone.CRASH_DETECTED) continue
            if (re.containsMatchIn(line)) {
                if (m == Milestone.CRASH_DETECTED) { if (crashLine == null) { crashLine = line; hit(m, line) } }
                else {
                    // Imply earlier milestones when a later one is observed (output formats differ between versions).
                    Milestone.entries.filter { it.ordinal < m.ordinal && it != Milestone.CRASH_DETECTED }.forEach { if (it !in reached) hit(it, line) }
                    hit(m, line)
                }
            }
        }
    }
    private fun hit(m: Milestone, line: String) { if (reached.add(m)) onMilestone(m, line) }

    fun stateFor(): LaunchState = when {
        Milestone.READY in reached -> LaunchState.RUNNING
        Milestone.MAIN_ENTERED in reached -> LaunchState.LOADING_GAME
        else -> LaunchState.INITIALIZING_GAME
    }
}
