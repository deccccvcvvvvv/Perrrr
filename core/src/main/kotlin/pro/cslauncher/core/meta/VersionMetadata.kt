package pro.cslauncher.core.meta

import kotlinx.serialization.json.*
import pro.cslauncher.core.util.*

data class Download(val url: String, val integrity: Integrity, val path: String? = null)

/** An argument fragment of the modern format: values only apply when the rules allow them. */
data class ArgumentEntry(val values: List<String>, val rules: List<Rule>)

data class AssetIndexRef(val id: String, val url: String, val sha1: String?, val size: Long?, val totalSize: Long?)

data class LoggingConfig(val argument: String, val fileId: String, val download: Download)

data class Library(
    val name: String,
    val coordinate: MavenCoordinate,
    val artifact: Download?,             // main jar (null for natives-only legacy entries)
    val classifiers: Map<String, Download>,
    val natives: Map<String, String>,    // os -> classifier (may contain ${arch})
    val rules: List<Rule>,
    val extractExclude: List<String>,
    val mavenBaseUrl: String?,           // loader metadata (Fabric/Quilt) uses url + name instead of downloads
) {
    /** LWJGL/JInput/etc. natives packaged as classifier jars, or modern "natives-*" coordinate classifiers. */
    val isNativeOnly get() = natives.isNotEmpty() && artifact == null
    val isNativeClassifierCoordinate get() = coordinate.classifier?.startsWith("natives-") == true
}

data class VersionMetadata(
    val id: String,
    val type: VersionType,
    val inheritsFrom: String?,
    val mainClass: String,
    val legacyArguments: String?,        // "minecraftArguments" (pre 1.13)
    val gameArguments: List<ArgumentEntry>,
    val jvmArguments: List<ArgumentEntry>,
    val assetIndex: AssetIndexRef?,
    val assets: String?,
    val clientDownload: Download?,
    val libraries: List<Library>,
    val javaMajor: Int?,
    val javaComponent: String?,
    val logging: LoggingConfig?,
    val releaseTime: String?,
    val raw: JsonObject,
) {
    val usesModernArguments get() = gameArguments.isNotEmpty() || jvmArguments.isNotEmpty()
    /** Minecraft before 1.13 used LWJGL 2 which needs a different Android integration. */
    val usesLwjgl3 get() = libraries.any { it.coordinate.group == "org.lwjgl" && it.coordinate.version.startsWith("3") }

    companion object {
        fun parse(text: String): VersionMetadata = parse(parseJsonObject(text, "Version metadata"))

        fun parse(o: JsonObject): VersionMetadata {
            val id = o.str("id") ?: throw MetadataException("Version metadata has no 'id'")
            val inherits = o.str("inheritsFrom")
            val mainClass = o.str("mainClass")
            if (mainClass == null && inherits == null) throw MetadataException("Version $id has no mainClass")
            val args = o.obj("arguments")
            val libs = (o.arr("libraries") ?: JsonArray(emptyList())).mapIndexed { i, el ->
                parseLibrary(el as? JsonObject ?: throw MetadataException("Library #$i of $id is not an object"), id)
            }
            val ai = o.obj("assetIndex")?.let {
                AssetIndexRef(it.str("id") ?: throw MetadataException("assetIndex.id missing in $id"),
                    it.str("url") ?: throw MetadataException("assetIndex.url missing in $id"), it.str("sha1"), it.long("size"), it.long("totalSize"))
            }
            val client = o.obj("downloads")?.obj("client")?.let(::parseDownload)
            val logging = o.obj("logging")?.obj("client")?.let { lc ->
                val f = lc.obj("file") ?: return@let null
                LoggingConfig(lc.str("argument") ?: return@let null, f.str("id") ?: return@let null, parseDownload(f) ?: return@let null)
            }
            return VersionMetadata(
                id = id, type = VersionType.of(o.str("type")), inheritsFrom = inherits, mainClass = mainClass ?: "",
                legacyArguments = o.str("minecraftArguments"),
                gameArguments = parseArgs(args?.arr("game")), jvmArguments = parseArgs(args?.arr("jvm")),
                assetIndex = ai, assets = o.str("assets"), clientDownload = client, libraries = libs,
                javaMajor = o.obj("javaVersion")?.int("majorVersion"), javaComponent = o.obj("javaVersion")?.str("component"),
                logging = logging, releaseTime = o.str("releaseTime"), raw = o,
            )
        }

        private fun parseDownload(o: JsonObject): Download? {
            val url = o.str("url") ?: return null
            return Download(url, Integrity(sha1 = o.str("sha1"), size = o.long("size")), o.str("path"))
        }

        private fun parseArgs(a: JsonArray?): List<ArgumentEntry> = a?.mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> ArgumentEntry(listOf(el.content), emptyList())
                is JsonObject -> {
                    val v = el["value"]
                    val values = when (v) {
                        is JsonPrimitive -> listOf(v.content)
                        is JsonArray -> v.map { (it as JsonPrimitive).content }
                        else -> return@mapNotNull null
                    }
                    ArgumentEntry(values, Rule.parseList(el.arr("rules")))
                }
                else -> null
            }
        } ?: emptyList()

        private fun parseLibrary(o: JsonObject, versionId: String): Library {
            val name = o.str("name") ?: throw MetadataException("A library in $versionId has no name")
            val coord = MavenCoordinate.parse(name)
            val dl = o.obj("downloads")
            val artifact = dl?.obj("artifact")?.let(::parseDownload)
            val classifiers = dl?.obj("classifiers")?.mapNotNull { (k, v) -> (v as? JsonObject)?.let(::parseDownload)?.let { k to it } }?.toMap() ?: emptyMap()
            val natives = o.obj("natives")?.mapValues { (it.value as JsonPrimitive).content } ?: emptyMap()
            val base = o.str("url")
            // Loader-style library: only "name" (+"url"), optional sha1/size.
            val loaderArtifact = if (artifact == null && dl == null && natives.isEmpty()) {
                val b = (base ?: "https://libraries.minecraft.net/").let { if (it.endsWith("/")) it else "$it/" }
                Download(b + coord.path(), Integrity(sha1 = o.str("sha1"), size = o.long("size")), coord.path())
            } else null
            return Library(name, coord, artifact ?: loaderArtifact, classifiers, natives, Rule.parseList(o.arr("rules")),
                o.obj("extract")?.arr("exclude")?.map { (it as JsonPrimitive).content } ?: emptyList(), base)
        }

        /** Merges a child (e.g. Fabric profile) onto its parent following launcher conventions. */
        fun merge(child: VersionMetadata, parent: VersionMetadata): VersionMetadata {
            val childKeys = child.libraries.map { it.coordinate.versionlessKey }.toSet()
            return child.copy(
                inheritsFrom = parent.inheritsFrom,
                type = if (child.type == VersionType.MODDED) parent.type else child.type,
                mainClass = child.mainClass.ifEmpty { parent.mainClass },
                legacyArguments = child.legacyArguments ?: parent.legacyArguments,
                gameArguments = parent.gameArguments + child.gameArguments,
                jvmArguments = parent.jvmArguments + child.jvmArguments,
                assetIndex = child.assetIndex ?: parent.assetIndex,
                assets = child.assets ?: parent.assets,
                clientDownload = child.clientDownload ?: parent.clientDownload,
                // Child libraries take precedence; parent duplicates (same group:artifact:classifier) are dropped.
                libraries = child.libraries + parent.libraries.filter { it.coordinate.versionlessKey !in childKeys },
                javaMajor = child.javaMajor ?: parent.javaMajor,
                javaComponent = child.javaComponent ?: parent.javaComponent,
                logging = child.logging ?: parent.logging,
                releaseTime = child.releaseTime ?: parent.releaseTime,
            )
        }
    }
}

data class MavenCoordinate(val group: String, val artifact: String, val version: String, val classifier: String?, val extension: String) {
    val versionlessKey get() = "$group:$artifact" + (classifier?.let { ":$it" } ?: "")
    fun withClassifier(c: String?) = copy(classifier = c)
    fun path(): String = group.replace('.', '/') + "/$artifact/$version/$artifact-$version" + (classifier?.let { "-$it" } ?: "") + ".$extension"

    companion object {
        fun parse(s: String): MavenCoordinate {
            val (main, ext) = s.split('@', limit = 2).let { it[0] to (it.getOrNull(1) ?: "jar") }
            val p = main.split(':')
            if (p.size < 3 || p.any { it.isBlank() || it.contains("..") || it.contains('/') || it.contains('\\') })
                throw MetadataException("Invalid maven coordinate '$s'")
            return MavenCoordinate(p[0], p[1], p[2], p.getOrNull(3), ext)
        }
    }
}
