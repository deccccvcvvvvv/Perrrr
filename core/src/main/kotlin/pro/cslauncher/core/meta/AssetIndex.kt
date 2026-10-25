package pro.cslauncher.core.meta

import pro.cslauncher.core.util.*

data class AssetObject(val name: String, val hash: String, val size: Long) {
    val relativeObjectPath get() = "${hash.substring(0, 2)}/$hash"
    fun url(base: String = DEFAULT_BASE) = "${base.trimEnd('/')}/$relativeObjectPath"
    companion object { const val DEFAULT_BASE = "https://resources.download.minecraft.net" }
}

/** Pre-1.7 versions use "virtual" (legacy) or "map_to_resources" (pre-1.6) layouts in addition to objects/. */
data class AssetIndex(val objects: List<AssetObject>, val virtual: Boolean, val mapToResources: Boolean) {
    val totalSize get() = objects.sumOf { it.size }
    companion object {
        private val HASH = Regex("^[0-9a-fA-F]{40}$")
        fun parse(text: String): AssetIndex {
            val o = parseJsonObject(text, "Asset index")
            val objs = o.obj("objects") ?: throw MetadataException("Asset index has no 'objects'")
            val list = objs.map { (name, v) ->
                val e = v as? kotlinx.serialization.json.JsonObject ?: throw MetadataException("Asset '$name' malformed")
                val hash = e.str("hash") ?: throw MetadataException("Asset '$name' has no hash")
                if (!HASH.matches(hash)) throw MetadataException("Asset '$name' has an invalid hash")
                if (name.contains("..") || name.startsWith("/")) throw MetadataException("Asset '$name' has an unsafe path")
                AssetObject(name, hash.lowercase(), e.long("size") ?: -1)
            }
            return AssetIndex(list, o.bool("virtual") == true, o.bool("map_to_resources") == true)
        }
    }
}
