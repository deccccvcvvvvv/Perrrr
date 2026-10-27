package pro.cslauncher.core.content

import kotlinx.serialization.json.*
import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.meta.VersionMetadata
import pro.cslauncher.core.net.HttpTransport
import pro.cslauncher.core.profile.LoaderType
import pro.cslauncher.core.util.*
import java.net.URLEncoder

data class LoaderVersion(val version: String, val stable: Boolean)
data class LoaderSupport(val supported: Boolean, val reason: String)

/** Numeric component comparison (0.10 sorts newer than 0.9), with upstream text as a stable tie-breaker. */
private fun compareLoaderNumbers(left: String, right: String): Int {
    fun parts(value: String) = Regex("\\d+").findAll(value.substringBefore('+')).mapNotNull { it.value.toIntOrNull() }.toList()
    val a = parts(left); val b = parts(right)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x.compareTo(y)
    }
    return left.compareTo(right, ignoreCase = true)
}

/**
 * Installs mod loaders by writing their launcher profile JSON (inheritsFrom the vanilla version).
 * Fabric and Quilt publish launcher-ready profiles, so installation is a verified metadata download plus library
 * downloads done by the normal installer. Forge/NeoForge require running installer "processors" (binary patching
 * of the client) which this launcher does not implement yet; they are reported as unsupported, not faked.
 */
class LoaderInstaller(private val http: HttpTransport, private val dirs: LauncherDirs) {
    private fun meta(type: LoaderType) = when (type) {
        LoaderType.FABRIC -> "https://meta.fabricmc.net/v2"
        LoaderType.QUILT -> "https://meta.quiltmc.org/v3"
        else -> null
    }

    fun support(type: LoaderType): LoaderSupport = when (type) {
        LoaderType.VANILLA -> LoaderSupport(true, "No loader")
        LoaderType.FABRIC, LoaderType.QUILT -> LoaderSupport(true, "Installed from the official ${type.name.lowercase()} metadata service")
        LoaderType.FORGE, LoaderType.NEOFORGE -> LoaderSupport(false, "${type.name.lowercase().replaceFirstChar { it.uppercase() }} needs its installer processors to patch the game; not supported yet")
    }

    fun versions(type: LoaderType, mcVersion: String): List<LoaderVersion> {
        val base = meta(type) ?: return emptyList()
        val arr = CsJson.parseToJsonElement(http.getText("$base/versions/loader/${URLEncoder.encode(mcVersion, "UTF-8")}")).jsonArray
        return arr.mapNotNull { el -> val l = el.jsonObject.obj("loader") ?: return@mapNotNull null
            val version = l.str("version") ?: return@mapNotNull null
            LoaderVersion(version, l.bool("stable") ?: !version.contains("beta", ignoreCase = true))
        }.sortedWith { a, b ->
            when {
                a.stable != b.stable -> if (a.stable) -1 else 1
                else -> compareLoaderNumbers(b.version, a.version)
            }
        }
    }

    /** Writes versions/<id>/<id>.json and returns the new version id. Libraries are fetched by the regular installer. */
    fun install(type: LoaderType, mcVersion: String, loaderVersion: String): String {
        val base = meta(type) ?: throw UnsupportedOperationException(support(type).reason)
        val text = http.getText("$base/versions/loader/${URLEncoder.encode(mcVersion, "UTF-8")}/${URLEncoder.encode(loaderVersion, "UTF-8")}/profile/json")
        val parsed = VersionMetadata.parse(text)  // validates structure before anything is written
        require(parsed.inheritsFrom == mcVersion) { "Loader profile inherits from ${parsed.inheritsFrom}, expected $mcVersion" }
        require(parsed.libraries.all { it.artifact?.url?.startsWith("https://") == true }) { "Loader profile contains non-HTTPS libraries" }
        FsUtil.writeAtomic(dirs.versionJson(parsed.id), text)
        return parsed.id
    }
}
