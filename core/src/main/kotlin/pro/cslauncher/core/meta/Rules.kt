package pro.cslauncher.core.meta

import kotlinx.serialization.json.*
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.util.*

/** Feature flags referenced by modern argument rules. */
data class LaunchFeatures(
    val isDemoUser: Boolean = false,
    val hasCustomResolution: Boolean = false,
    val enabled: Set<String> = emptySet(), // other features, e.g. "has_quick_plays_support"
) {
    fun isSet(name: String): Boolean = when (name) {
        "is_demo_user" -> isDemoUser
        "has_custom_resolution" -> hasCustomResolution
        else -> name in enabled
    }
}

data class Rule(val allow: Boolean, val osName: String?, val osVersion: String?, val osArch: String?, val features: Map<String, Boolean>) {
    fun matches(p: PlatformInfo, f: LaunchFeatures): Boolean {
        if (osName != null && osName != p.osName) return false
        if (osArch != null) {
            // Mojang uses "x86" for 32-bit x86. Some third-party metadata uses "arm64"/"x86_64".
            val ok = when (osArch) {
                "x86" -> p.arch == PlatformInfo.Arch.X86
                "x86_64" -> p.arch == PlatformInfo.Arch.X86_64
                "arm64", "aarch64" -> p.arch == PlatformInfo.Arch.ARM64
                "arm", "arm32" -> p.arch == PlatformInfo.Arch.ARM32
                else -> false
            }
            if (!ok) return false
        }
        val versionPattern: String? = osVersion
        if (versionPattern != null) {
            val matched: Boolean = try { Regex(versionPattern).containsMatchIn(p.osVersion) } catch (e: IllegalArgumentException) { false }
            if (!matched) return false
        }
        for ((k, v) in features) if (f.isSet(k) != v) return false
        return true
    }

    companion object {
        fun parse(o: JsonObject): Rule {
            val os = o.obj("os")
            val feats = o.obj("features")?.mapValues { (it.value as? JsonPrimitive)?.booleanOrNull ?: false } ?: emptyMap()
            return Rule(o.str("action") == "allow", os?.str("name"), os?.str("version"), os?.str("arch"), feats)
        }
        fun parseList(a: JsonArray?): List<Rule> = a?.mapNotNull { (it as? JsonObject)?.let(::parse) } ?: emptyList()
    }
}

object RuleEvaluator {
    /** Mojang semantics: no rules = allowed. Otherwise the last matching rule wins; default disallow. */
    fun allowed(rules: List<Rule>, p: PlatformInfo, f: LaunchFeatures = LaunchFeatures()): Boolean {
        if (rules.isEmpty()) return true
        var result = false
        for (r in rules) if (r.matches(p, f)) result = r.allow
        return result
    }
}
