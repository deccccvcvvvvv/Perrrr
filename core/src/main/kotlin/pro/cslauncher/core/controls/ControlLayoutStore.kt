package pro.cslauncher.core.controls

import pro.cslauncher.core.util.FsUtil
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/**
 * File-backed control layouts (one validated JSON file per layout). The built-in default is always available and
 * can be customised: a saved "default-touch" file overrides it, and [resetDefault] restores the original.
 */
class ControlLayoutStore(private val dir: Path) {
    private val idPattern = Regex("^[a-z0-9_-]{1,40}$")

    fun list(): List<ControlLayout> {
        val saved = if (!Files.isDirectory(dir)) emptyList() else dir.listDirectoryEntries().filter { it.extension == "json" }
            .mapNotNull { p -> runCatching { ControlLayoutCodec.decode(pro.cslauncher.core.util.FsUtil.readText(p)) }.getOrNull()?.takeIf { it.id == p.nameWithoutExtension } }
        val default = saved.firstOrNull { it.id == DEFAULT_ID } ?: DefaultLayouts.defaultTouch()
        return listOf(default) + saved.filter { it.id != DEFAULT_ID }.sortedBy { it.name.lowercase() }
    }

    /** Falls back to the default layout when [id] is unknown or its file is invalid (the game must stay controllable). */
    fun load(id: String): ControlLayout = list().firstOrNull { it.id == id } ?: list().first()

    fun save(layout: ControlLayout) {
        require(idPattern.matches(layout.id)) { "Invalid layout id '${layout.id}'" }
        val problems = ControlLayoutCodec.validate(layout)
        if (problems.isNotEmpty()) throw LayoutValidationException(problems)
        Files.createDirectories(dir)
        FsUtil.writeAtomic(dir.resolve("${layout.id}.json"), ControlLayoutCodec.encode(layout))
    }

    /** Imports shared JSON. Never overwrites an existing layout: a clashing id gets a numeric suffix. */
    fun import(text: String): ControlLayout {
        val parsed = ControlLayoutCodec.decode(text)
        val base = parsed.id.lowercase().replace(Regex("[^a-z0-9_-]"), "-").take(32).ifBlank { "imported" }
        val taken = list().map { it.id }.toSet()
        var id = base; var n = 2
        while (id in taken) id = "$base-${n++}"
        return parsed.copy(id = id).also(::save)
    }

    fun export(id: String): String = ControlLayoutCodec.encode(load(id))

    fun delete(id: String) {
        require(id != DEFAULT_ID) { "The default layout cannot be deleted (use reset instead)" }
        Files.deleteIfExists(dir.resolve("$id.json"))
    }

    fun resetDefault() { Files.deleteIfExists(dir.resolve("$DEFAULT_ID.json")) }

    companion object { const val DEFAULT_ID = "default-touch" }
}
