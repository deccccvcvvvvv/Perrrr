package pro.cslauncher.core.launch

import pro.cslauncher.core.meta.*
import pro.cslauncher.core.platform.PlatformInfo

object ArgumentBuilder {
    private val PH = Regex("""\$\{([A-Za-z0-9_.]+)\}""")   // "}" escaped: Android's ICU regex rejects a bare "}"

    fun substitute(s: String, values: Map<String, String>, unresolved: MutableSet<String>): String =
        PH.replace(s) { m -> values[m.groupValues[1]] ?: run { unresolved += m.groupValues[1]; m.value } }

    /** Expands modern argument entries, honouring rules and feature flags. */
    fun expand(entries: List<ArgumentEntry>, platform: PlatformInfo, features: LaunchFeatures): List<String> =
        entries.filter { RuleEvaluator.allowed(it.rules, platform, features) }.flatMap { it.values }

    /** Pre-1.13 "minecraftArguments" string. Values never contain spaces in Mojang metadata. */
    fun legacy(minecraftArguments: String): List<String> = minecraftArguments.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
}

/**
 * JNI_CreateJavaVM accepts single-token options only. Converts launcher-style arguments into that form.
 * Used by the Android in-process launcher; desktop launches use the arguments unchanged.
 */
object JvmOptionNormalizer {
    data class Result(val options: List<String>, val dropped: List<String>)
    private val TWO_TOKEN = setOf("--add-opens", "--add-exports", "--add-reads", "--add-modules", "--patch-module", "--limit-modules", "--module-path", "-p")

    fun normalize(args: List<String>): Result {
        val out = mutableListOf<String>(); val dropped = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-cp" || a == "-classpath" || a == "--class-path" -> { out += "-Djava.class.path=" + args.getOrElse(i + 1) { "" }; i++ }
                a in TWO_TOKEN -> {
                    val v = args.getOrElse(i + 1) { "" }; i++
                    val key = if (a == "-p") "--module-path" else a
                    // HotSpot accepts these via the jdk.module.* system properties when passed through JNI.
                    out += when (key) {
                        "--add-opens" -> "--add-opens=$v"; "--add-exports" -> "--add-exports=$v"; "--add-reads" -> "--add-reads=$v"
                        "--add-modules" -> "--add-modules=$v"; "--patch-module" -> "--patch-module=$v"; "--limit-modules" -> "--limit-modules=$v"
                        else -> "-Djdk.module.path=$v"
                    }
                }
                a == "-XstartOnFirstThread" -> dropped += a           // macOS only
                a.startsWith("-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance") -> dropped += a // Windows only
                a.isBlank() -> {}
                else -> out += a
            }
            i++
        }
        return Result(out, dropped)
    }
}
