package pro.cslauncher.core.activity

import kotlinx.serialization.Serializable
import pro.cslauncher.core.util.*
import java.nio.file.Files
import java.nio.file.Path

@Serializable enum class ActivityKind { LAUNCHED, INSTALLED, MOD_IMPORTED, RECORDING, DOWNLOAD_FAILED, CRASHED, REPAIRED }
@Serializable data class ActivityItem(val kind: ActivityKind, val title: String, val detail: String, val atMs: Long = System.currentTimeMillis(), val profileId: String? = null)

/** Small bounded activity log; can be disabled and cleared by the user. */
class ActivityLog(private val file: Path, private val max: Int = 40) {
    @Synchronized fun list(): List<ActivityItem> = runCatching { CsJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ActivityItem.serializer()), pro.cslauncher.core.util.FsUtil.readText(file)) }.getOrDefault(emptyList())
    @Synchronized fun add(item: ActivityItem, enabled: Boolean) {
        if (!enabled) return
        FsUtil.writeAtomic(file, CsJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(ActivityItem.serializer()), (listOf(item) + list()).take(max)))
    }
    @Synchronized fun clear() { Files.deleteIfExists(file) }
}
