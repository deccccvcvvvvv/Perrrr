package pro.cslauncher.core.util

import kotlinx.serialization.json.*

/** Shared JSON configuration. Network metadata is untrusted: unknown keys are ignored, malformed data raises. */
val CsJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    explicitNulls = false
    classDiscriminator = "type"
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

class MetadataException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun parseJsonObject(text: String, what: String): JsonObject = try {
    CsJson.parseToJsonElement(text) as? JsonObject ?: throw MetadataException("$what is not a JSON object")
} catch (e: MetadataException) { throw e } catch (e: Exception) {
    throw MetadataException("$what is not valid JSON: ${e.message}", e)
}
