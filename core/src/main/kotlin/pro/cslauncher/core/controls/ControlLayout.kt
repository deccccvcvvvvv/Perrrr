package pro.cslauncher.core.controls

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import pro.cslauncher.core.util.*

@Serializable sealed class InputAction {
    @Serializable @SerialName("key") data class Key(val key: Int) : InputAction()
    @Serializable @SerialName("mouse") data class Mouse(val button: Int) : InputAction()
    @Serializable @SerialName("scroll") data class Scroll(val dy: Float) : InputAction()
    @Serializable @SerialName("mode") data class SwitchMode(val mode: String) : InputAction()
    @Serializable @SerialName("keyboard") data object ShowKeyboard : InputAction()
    @Serializable @SerialName("toggle_overlay") data object ToggleOverlay : InputAction()
    @Serializable @SerialName("record") data object ToggleRecording : InputAction()
    @Serializable @SerialName("menu") data object LauncherMenu : InputAction()

    fun describe(): String = when (this) {
        is Key -> Glfw.names[key] ?: "Key $key"
        is Mouse -> when (button) { 0 -> "Left click"; 1 -> "Right click"; 2 -> "Middle click"; else -> "Mouse $button" }
        is Scroll -> if (dy > 0) "Scroll up" else "Scroll down"
        is SwitchMode -> "Mode: $mode"
        ShowKeyboard -> "Keyboard"; ToggleOverlay -> "Hide controls"; ToggleRecording -> "Record"; LauncherMenu -> "Menu"
    }
}

@Serializable enum class ElementKind { BUTTON, JOYSTICK, TOUCHPAD }
@Serializable enum class ButtonBehavior { HOLD, TOGGLE, TAP, DOUBLE_TAP, REPEAT }
@Serializable enum class Visibility { ALWAYS, IN_GAME, IN_MENU }
@Serializable enum class JoystickRole { MOVEMENT, LOOK }

@Serializable data class JoystickConfig(
    val role: JoystickRole = JoystickRole.MOVEMENT,
    val deadZone: Float = 0.18f, val sensitivity: Float = 1f, val floating: Boolean = false,
    val sprintThreshold: Float = 0.94f,   // 0 disables auto-sprint at full deflection
    val showIndicator: Boolean = true,
    val forward: Int = Glfw.KEY_W, val back: Int = Glfw.KEY_S, val left: Int = Glfw.KEY_A, val right: Int = Glfw.KEY_D,
    val sprintKey: Int = Glfw.KEY_LEFT_CONTROL,
)

@Serializable data class TouchpadConfig(val sensitivity: Float = 1f, val tapToClick: Boolean = true)

@Serializable data class ControlElement(
    val id: String,
    val kind: ElementKind = ElementKind.BUTTON,
    val label: String = "",
    val icon: String? = null,
    val x: Float, val y: Float,                    // centre, normalised to the safe area (0..1)
    val widthDp: Float = 56f, val heightDp: Float = 56f,
    val opacity: Float = 0.55f,
    val cornerRadius: Float = 0.3f,                // fraction of the smaller side
    val colorArgb: Long = 0xFF1E1E26,
    val actions: List<InputAction> = emptyList(),
    val behavior: ButtonBehavior = ButtonBehavior.HOLD,
    val repeatDelayMs: Int = 350, val repeatIntervalMs: Int = 70, val doubleTapMs: Int = 300,
    val visibility: Visibility = Visibility.ALWAYS,
    val swipeable: Boolean = false,
    val requiresModifier: Int? = null,
    val joystick: JoystickConfig? = null,
    val touchpad: TouchpadConfig? = null,
)

@Serializable data class ControlLayout(
    val formatVersion: Int = ControlLayoutCodec.FORMAT,
    val id: String,
    val name: String,
    val modes: Map<String, List<ControlElement>>,
    val defaultMode: String = "game",
    val autoModes: Boolean = true,                 // switch to "inventory" mode while the game shows a cursor
) {
    fun elements(mode: String) = modes[mode] ?: modes[defaultMode].orEmpty()
}

class LayoutValidationException(val problems: List<String>) : Exception(problems.joinToString("; "))

/** Versioned, documented layout format (see docs/CONTROL_LAYOUT_FORMAT.md). Imports are validated before use. */
object ControlLayoutCodec {
    const val FORMAT = 1
    const val MAX_ELEMENTS_PER_MODE = 160

    fun encode(l: ControlLayout): String = CsJson.encodeToString(ControlLayout.serializer(), l)

    fun decode(text: String): ControlLayout {
        val root = parseJsonObject(text, "Control layout")
        val v = root.int("formatVersion") ?: throw LayoutValidationException(listOf("Missing formatVersion"))
        if (v > FORMAT) throw LayoutValidationException(listOf("Layout format $v is newer than supported ($FORMAT)"))
        val layout = try { CsJson.decodeFromJsonElement(ControlLayout.serializer(), root) }
                     catch (e: Exception) { throw LayoutValidationException(listOf("Malformed layout: ${e.message?.take(200)}")) }
        val p = validate(layout)
        if (p.isNotEmpty()) throw LayoutValidationException(p)
        return layout
    }

    fun validate(l: ControlLayout): List<String> = buildList {
        if (l.name.isBlank() || l.name.length > 48) add("Layout name must be 1-48 characters")
        if (l.modes.isEmpty()) add("Layout has no modes")
        if (l.defaultMode !in l.modes) add("Default mode '${l.defaultMode}' does not exist")
        for ((mode, els) in l.modes) {
            if (!Regex("^[a-z0-9_-]{1,24}$").matches(mode)) add("Invalid mode name '$mode'")
            if (els.size > MAX_ELEMENTS_PER_MODE) add("Mode '$mode' has more than $MAX_ELEMENTS_PER_MODE controls")
            els.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { add("Duplicate control id '$it' in '$mode'") }
            for (e in els) {
                if (e.x !in 0f..1f || e.y !in 0f..1f) add("${e.id}: position outside the screen")
                if (e.widthDp !in 16f..480f || e.heightDp !in 16f..480f) add("${e.id}: size out of range")
                if (e.opacity !in 0f..1f) add("${e.id}: opacity out of range")
                if (e.label.length > 24) add("${e.id}: label too long")
                if (e.kind == ElementKind.JOYSTICK && e.joystick == null) add("${e.id}: joystick without configuration")
                e.joystick?.let { j -> if (j.deadZone !in 0f..0.9f) add("${e.id}: dead zone out of range"); listOf(j.forward, j.back, j.left, j.right).filterNot(Glfw::isValidKey).forEach { add("${e.id}: invalid key $it") } }
                e.requiresModifier?.let { if (!Glfw.isValidKey(it)) add("${e.id}: invalid modifier key") }
                for (a in e.actions) when (a) {
                    is InputAction.Key -> if (!Glfw.isValidKey(a.key)) add("${e.id}: invalid key code ${a.key}")
                    is InputAction.Mouse -> if (!Glfw.isValidMouse(a.button)) add("${e.id}: invalid mouse button ${a.button}")
                    is InputAction.SwitchMode -> if (a.mode !in l.modes) add("${e.id}: switches to unknown mode '${a.mode}'")
                    is InputAction.Scroll -> if (a.dy == 0f || a.dy !in -10f..10f) add("${e.id}: invalid scroll amount")
                    else -> {}
                }
            }
        }
    }
}

/** Built-in layouts. Positions are normalised; sizes in dp so they scale with density. */
object DefaultLayouts {
    private fun key(id: String, label: String, x: Float, y: Float, k: Int, w: Float = 52f, h: Float = 52f, b: ButtonBehavior = ButtonBehavior.HOLD, vis: Visibility = Visibility.ALWAYS, swipe: Boolean = false) =
        ControlElement(id, ElementKind.BUTTON, label, null, x, y, w, h, actions = listOf(InputAction.Key(k)), behavior = b, visibility = vis, swipeable = swipe)
    private fun mouse(id: String, label: String, x: Float, y: Float, btn: Int, w: Float = 56f) =
        ControlElement(id, ElementKind.BUTTON, label, null, x, y, w, w, actions = listOf(InputAction.Mouse(btn)), visibility = Visibility.IN_GAME)
    private fun hotbar(y: Float = 0.955f): List<ControlElement> = (1..9).map { i ->
        ControlElement("hotbar$i", ElementKind.BUTTON, "", null, 0.5f + (i - 5) * 0.046f, y, 40f, 40f, opacity = 0.0f,
            actions = listOf(InputAction.Key(Glfw.KEY_0 + i)), behavior = ButtonBehavior.TAP, visibility = Visibility.IN_GAME, swipeable = true)
    }
    private fun joystick(x: Float = 0.14f, y: Float = 0.72f, floating: Boolean = false, size: Float = 150f) =
        ControlElement("move", ElementKind.JOYSTICK, "", null, x, y, size, size, opacity = 0.5f, visibility = Visibility.IN_GAME,
            joystick = JoystickConfig(floating = floating))
    private val menuRow = listOf(
        key("esc", "Esc", 0.04f, 0.07f, Glfw.KEY_ESCAPE, 46f, 36f, ButtonBehavior.TAP),
        key("chat", "T", 0.10f, 0.07f, Glfw.KEY_T, 40f, 36f, ButtonBehavior.TAP, Visibility.IN_GAME),
        key("perspective", "F5", 0.16f, 0.07f, Glfw.KEY_F5, 40f, 36f, ButtonBehavior.TAP, Visibility.IN_GAME),
        key("debug", "F3", 0.22f, 0.07f, Glfw.KEY_F3, 40f, 36f, ButtonBehavior.TAP, Visibility.IN_GAME),
        ControlElement("keyboard", ElementKind.BUTTON, "⌨", null, 0.96f, 0.07f, 40f, 36f, actions = listOf(InputAction.ShowKeyboard), behavior = ButtonBehavior.TAP),
        ControlElement("menu", ElementKind.BUTTON, "☰", null, 0.90f, 0.07f, 40f, 36f, actions = listOf(InputAction.LauncherMenu), behavior = ButtonBehavior.TAP),
    )
    private val gameButtons = listOf(
        key("jump", "Jump", 0.89f, 0.72f, Glfw.KEY_SPACE, 64f, 64f, vis = Visibility.IN_GAME),
        key("sneak", "Sneak", 0.14f, 0.40f, Glfw.KEY_LEFT_SHIFT, 52f, 44f, ButtonBehavior.TOGGLE, Visibility.IN_GAME),
        key("inventory", "Inv", 0.79f, 0.88f, Glfw.KEY_E, 46f, 40f, ButtonBehavior.TAP, Visibility.IN_GAME),
        key("drop", "Q", 0.72f, 0.88f, Glfw.KEY_Q, 40f, 40f, ButtonBehavior.TAP, Visibility.IN_GAME),
        key("swap", "F", 0.86f, 0.88f, Glfw.KEY_F, 40f, 40f, ButtonBehavior.TAP, Visibility.IN_GAME),
        mouse("attack", "⚔", 0.80f, 0.60f, Glfw.MOUSE_LEFT), mouse("use", "✋", 0.80f, 0.42f, Glfw.MOUSE_RIGHT),
    )
    private val inventoryMode = listOf(
        key("inv_close", "Close", 0.92f, 0.10f, Glfw.KEY_E, 56f, 40f, ButtonBehavior.TAP),
        key("inv_shift", "Shift", 0.06f, 0.75f, Glfw.KEY_LEFT_SHIFT, 56f, 44f, ButtonBehavior.HOLD),
        key("inv_esc", "Esc", 0.06f, 0.10f, Glfw.KEY_ESCAPE, 46f, 36f, ButtonBehavior.TAP),
        ControlElement("inv_keyboard", ElementKind.BUTTON, "⌨", null, 0.06f, 0.30f, 44f, 40f, actions = listOf(InputAction.ShowKeyboard), behavior = ButtonBehavior.TAP),
        ControlElement("inv_scroll_up", ElementKind.BUTTON, "▲", null, 0.94f, 0.45f, 44f, 40f, actions = listOf(InputAction.Scroll(1f)), behavior = ButtonBehavior.REPEAT),
        ControlElement("inv_scroll_down", ElementKind.BUTTON, "▼", null, 0.94f, 0.60f, 44f, 40f, actions = listOf(InputAction.Scroll(-1f)), behavior = ButtonBehavior.REPEAT),
    )
    private val chatMode = listOf(
        ControlElement("chat_keyboard", ElementKind.BUTTON, "⌨ Type", null, 0.5f, 0.12f, 96f, 40f, actions = listOf(InputAction.ShowKeyboard), behavior = ButtonBehavior.TAP),
        key("chat_send", "Send", 0.90f, 0.12f, Glfw.KEY_ENTER, 64f, 40f, ButtonBehavior.TAP),
        key("chat_esc", "Esc", 0.06f, 0.12f, Glfw.KEY_ESCAPE, 46f, 40f, ButtonBehavior.TAP),
        key("chat_up", "↑", 0.80f, 0.12f, Glfw.KEY_UP, 40f, 40f, ButtonBehavior.REPEAT),
        key("chat_tab", "Tab", 0.70f, 0.12f, Glfw.KEY_TAB, 46f, 40f, ButtonBehavior.TAP),
    )

    fun defaultTouch() = ControlLayout(id = "default-touch", name = "Default Touch",
        modes = mapOf("game" to (listOf(joystick()) + gameButtons + menuRow + hotbar()), "inventory" to inventoryMode, "chat" to chatMode))
    fun compact() = ControlLayout(id = "compact", name = "Compact",
        modes = mapOf("game" to (listOf(joystick(size = 120f)) + gameButtons.filter { it.id in setOf("jump", "sneak", "inventory") }.map { it.copy(widthDp = it.widthDp * 0.85f, heightDp = it.heightDp * 0.85f) } + menuRow.take(2) + menuRow.takeLast(2) + hotbar()), "inventory" to inventoryMode, "chat" to chatMode))
    fun pvp() = ControlLayout(id = "pvp", name = "PvP",
        modes = mapOf("game" to (listOf(joystick(floating = true)) + gameButtons.map { if (it.id == "attack") it.copy(widthDp = 72f, heightDp = 72f) else it } +
            key("sprint", "Sprint", 0.14f, 0.52f, Glfw.KEY_LEFT_CONTROL, 52f, 44f, ButtonBehavior.TOGGLE, Visibility.IN_GAME) + menuRow + hotbar()), "inventory" to inventoryMode, "chat" to chatMode))
    fun creative() = ControlLayout(id = "creative", name = "Creative",
        modes = mapOf("game" to (listOf(joystick()) + gameButtons + key("fly_down", "▼", 0.89f, 0.88f, Glfw.KEY_LEFT_SHIFT, 52f, 44f, vis = Visibility.IN_GAME) +
            mouse("pick", "Pick", 0.70f, 0.42f, Glfw.MOUSE_MIDDLE, 48f) + menuRow + hotbar()), "inventory" to inventoryMode, "chat" to chatMode))
    fun controllerHybrid() = ControlLayout(id = "controller-hybrid", name = "Controller Hybrid",
        modes = mapOf("game" to (menuRow + hotbar()), "inventory" to inventoryMode, "chat" to chatMode))
    fun all() = listOf(defaultTouch(), compact(), pvp(), creative(), controllerHybrid())
}
