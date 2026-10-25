package pro.cslauncher.core.controls

/** Undo/redo editing model for the control editor. Consecutive drags of one control coalesce into one step. */
class LayoutEditor(initial: ControlLayout, private val maxHistory: Int = 100) {
    var current: ControlLayout = initial; private set
    private var saved = initial
    private val undo = ArrayDeque<ControlLayout>()
    private val redo = ArrayDeque<ControlLayout>()
    private var lastCoalesceKey: String? = null
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    val dirty get() = current != saved

    fun apply(coalesceKey: String? = null, change: (ControlLayout) -> ControlLayout) {
        val next = change(current)
        if (next == current) return
        if (coalesceKey == null || coalesceKey != lastCoalesceKey) { undo.addLast(current); if (undo.size > maxHistory) undo.removeFirst() }
        redo.clear(); current = next; lastCoalesceKey = coalesceKey
    }
    fun endGesture() { lastCoalesceKey = null }
    /** Call after the current layout was persisted; [dirty] becomes false until the next change. */
    fun markSaved() { saved = current }
    fun undo() { if (undo.isEmpty()) return; redo.addLast(current); current = undo.removeLast(); lastCoalesceKey = null }
    fun redo() { if (redo.isEmpty()) return; undo.addLast(current); current = redo.removeLast(); lastCoalesceKey = null }

    private fun mapMode(l: ControlLayout, mode: String, f: (List<ControlElement>) -> List<ControlElement>) = l.copy(modes = l.modes + (mode to f(l.modes[mode].orEmpty())))

    fun update(mode: String, id: String, coalesce: Boolean = false, f: (ControlElement) -> ControlElement) =
        apply(if (coalesce) "upd:$mode:$id" else null) { l -> mapMode(l, mode) { els -> els.map { if (it.id == id) f(it) else it } } }

    fun move(mode: String, id: String, x: Float, y: Float, grid: Float? = null) = update(mode, id, coalesce = true) {
        val sx = grid?.let { g -> (Math.round(x / g) * g) } ?: x
        val sy = grid?.let { g -> (Math.round(y / g) * g) } ?: y
        it.copy(x = sx.coerceIn(0f, 1f), y = sy.coerceIn(0f, 1f))
    }
    fun resize(mode: String, id: String, w: Float, h: Float) = update(mode, id, coalesce = true) { it.copy(widthDp = w.coerceIn(16f, 480f), heightDp = h.coerceIn(16f, 480f)) }
    fun add(mode: String, e: ControlElement) = apply { l -> mapMode(l, mode) { it + e.copy(id = uniqueId(l, mode, e.id)) } }
    fun delete(mode: String, id: String) = apply { l -> mapMode(l, mode) { els -> els.filterNot { it.id == id } } }
    fun duplicate(mode: String, id: String) = apply { l ->
        val src = l.modes[mode]?.firstOrNull { it.id == id } ?: return@apply l
        mapMode(l, mode) { it + src.copy(id = uniqueId(l, mode, src.id), x = (src.x + 0.04f).coerceAtMost(1f), y = (src.y + 0.04f).coerceAtMost(1f)) }
    }
    fun resetElement(mode: String, id: String, defaults: ControlLayout) {
        val d = defaults.modes[mode]?.firstOrNull { it.id == id } ?: return
        update(mode, id) { d }
    }
    fun resetAll(defaults: ControlLayout) = apply { defaults.copy(id = it.id, name = it.name) }
    fun addMode(name: String) = apply { l -> if (name in l.modes) l else l.copy(modes = l.modes + (name to emptyList())) }
    fun rename(name: String) = apply { it.copy(name = name) }

    private fun uniqueId(l: ControlLayout, mode: String, base: String): String {
        val ids = l.modes[mode].orEmpty().map { it.id }.toSet()
        if (base !in ids) return base
        var i = 2; while ("$base-$i" in ids) i++; return "$base-$i"
    }
}

/** Layout ids derived from user-visible names (file-name safe, unique). */
object LayoutIds {
    fun fromName(name: String, taken: Set<String>): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).ifBlank { "layout" }
        var id = base; var n = 2
        while (id in taken) id = "$base-${n++}"
        return id
    }
}
