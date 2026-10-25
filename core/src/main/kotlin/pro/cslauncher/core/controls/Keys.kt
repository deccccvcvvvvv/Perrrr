package pro.cslauncher.core.controls

/** GLFW key / mouse constants (the values LWJGL 3 and Minecraft expect). */
object Glfw {
    const val PRESS = 1; const val RELEASE = 0; const val REPEAT = 2
    const val MOUSE_LEFT = 0; const val MOUSE_RIGHT = 1; const val MOUSE_MIDDLE = 2
    const val KEY_SPACE = 32; const val KEY_APOSTROPHE = 39; const val KEY_COMMA = 44; const val KEY_MINUS = 45; const val KEY_PERIOD = 46
    const val KEY_SLASH = 47; const val KEY_0 = 48; const val KEY_9 = 57; const val KEY_SEMICOLON = 59; const val KEY_EQUAL = 61
    const val KEY_A = 65; const val KEY_D = 68; const val KEY_E = 69; const val KEY_F = 70; const val KEY_Q = 81; const val KEY_S = 83
    const val KEY_T = 84; const val KEY_W = 87; const val KEY_Z = 90
    const val KEY_LEFT_BRACKET = 91; const val KEY_BACKSLASH = 92; const val KEY_RIGHT_BRACKET = 93; const val KEY_GRAVE = 96
    const val KEY_ESCAPE = 256; const val KEY_ENTER = 257; const val KEY_TAB = 258; const val KEY_BACKSPACE = 259; const val KEY_INSERT = 260
    const val KEY_DELETE = 261; const val KEY_RIGHT = 262; const val KEY_LEFT = 263; const val KEY_DOWN = 264; const val KEY_UP = 265
    const val KEY_PAGE_UP = 266; const val KEY_PAGE_DOWN = 267; const val KEY_HOME = 268; const val KEY_END = 269; const val KEY_CAPS_LOCK = 280
    const val KEY_F1 = 290; const val KEY_F2 = 291; const val KEY_F3 = 292; const val KEY_F5 = 294; const val KEY_F11 = 300; const val KEY_F12 = 301
    const val KEY_KP_0 = 320; const val KEY_LEFT_SHIFT = 340; const val KEY_LEFT_CONTROL = 341; const val KEY_LEFT_ALT = 342
    const val KEY_RIGHT_SHIFT = 344; const val KEY_RIGHT_CONTROL = 345; const val KEY_RIGHT_ALT = 346
    const val MOD_SHIFT = 1; const val MOD_CONTROL = 2; const val MOD_ALT = 4

    fun isValidKey(k: Int) = k == KEY_SPACE || k in 39..96 || k in 256..348
    fun isValidMouse(b: Int) = b in 0..7

    /** Human readable names used by the control editor. */
    val names: Map<Int, String> = buildMap {
        for (c in 'A'..'Z') put(c.code, c.toString())
        for (d in 0..9) put(KEY_0 + d, d.toString())
        for (f in 1..12) put(KEY_F1 + f - 1, "F$f")
        put(KEY_SPACE, "Space"); put(KEY_ESCAPE, "Esc"); put(KEY_ENTER, "Enter"); put(KEY_TAB, "Tab"); put(KEY_BACKSPACE, "Backspace")
        put(KEY_DELETE, "Delete"); put(KEY_INSERT, "Insert"); put(KEY_UP, "Up"); put(KEY_DOWN, "Down"); put(KEY_LEFT, "Left"); put(KEY_RIGHT, "Right")
        put(KEY_PAGE_UP, "Page Up"); put(KEY_PAGE_DOWN, "Page Down"); put(KEY_HOME, "Home"); put(KEY_END, "End"); put(KEY_CAPS_LOCK, "Caps Lock")
        put(KEY_LEFT_SHIFT, "L-Shift"); put(KEY_RIGHT_SHIFT, "R-Shift"); put(KEY_LEFT_CONTROL, "L-Ctrl"); put(KEY_RIGHT_CONTROL, "R-Ctrl")
        put(KEY_LEFT_ALT, "L-Alt"); put(KEY_RIGHT_ALT, "R-Alt"); put(KEY_MINUS, "-"); put(KEY_EQUAL, "="); put(KEY_LEFT_BRACKET, "[")
        put(KEY_RIGHT_BRACKET, "]"); put(KEY_BACKSLASH, "\\"); put(KEY_SEMICOLON, ";"); put(KEY_APOSTROPHE, "'"); put(KEY_COMMA, ",")
        put(KEY_PERIOD, "."); put(KEY_SLASH, "/"); put(KEY_GRAVE, "`")
    }

    /** Scancode that matches the key on typical Linux/X11 layouts; Minecraft uses it for key names only. */
    fun scancodeFor(key: Int): Int = key
}

/** Android KeyEvent key codes (stable platform constants) mapped to GLFW keys for physical keyboards. */
object AndroidKeyMap {
    private val map: Map<Int, Int> = buildMap {
        for (i in 0..25) put(29 + i, Glfw.KEY_A + i)          // KEYCODE_A..Z
        for (i in 0..9) put(7 + i, Glfw.KEY_0 + i)            // KEYCODE_0..9
        for (i in 0..11) put(131 + i, Glfw.KEY_F1 + i)        // KEYCODE_F1..F12
        for (i in 0..9) put(144 + i, Glfw.KEY_KP_0 + i)       // KEYCODE_NUMPAD_0..9
        put(62, Glfw.KEY_SPACE); put(66, Glfw.KEY_ENTER); put(160, Glfw.KEY_ENTER); put(67, Glfw.KEY_BACKSPACE); put(112, Glfw.KEY_DELETE)
        put(61, Glfw.KEY_TAB); put(111, Glfw.KEY_ESCAPE); put(59, Glfw.KEY_LEFT_SHIFT); put(60, Glfw.KEY_RIGHT_SHIFT)
        put(113, Glfw.KEY_LEFT_CONTROL); put(114, Glfw.KEY_RIGHT_CONTROL); put(57, Glfw.KEY_LEFT_ALT); put(58, Glfw.KEY_RIGHT_ALT)
        put(19, Glfw.KEY_UP); put(20, Glfw.KEY_DOWN); put(21, Glfw.KEY_LEFT); put(22, Glfw.KEY_RIGHT)
        put(69, Glfw.KEY_MINUS); put(70, Glfw.KEY_EQUAL); put(71, Glfw.KEY_LEFT_BRACKET); put(72, Glfw.KEY_RIGHT_BRACKET)
        put(73, Glfw.KEY_BACKSLASH); put(74, Glfw.KEY_SEMICOLON); put(75, Glfw.KEY_APOSTROPHE); put(76, Glfw.KEY_SLASH)
        put(55, Glfw.KEY_COMMA); put(56, Glfw.KEY_PERIOD); put(68, Glfw.KEY_GRAVE); put(92, Glfw.KEY_PAGE_UP); put(93, Glfw.KEY_PAGE_DOWN)
        put(122, Glfw.KEY_HOME); put(123, Glfw.KEY_END); put(124, Glfw.KEY_INSERT); put(115, Glfw.KEY_CAPS_LOCK)
    }
    const val KEYCODE_BACK = 4
    fun toGlfw(androidKeyCode: Int): Int? = map[androidKeyCode]
    fun modsFromMetaState(meta: Int): Int {
        var m = 0
        if (meta and 0x1 != 0) m = m or Glfw.MOD_SHIFT       // META_SHIFT_ON
        if (meta and 0x2 != 0) m = m or Glfw.MOD_ALT         // META_ALT_ON
        if (meta and 0x1000 != 0) m = m or Glfw.MOD_CONTROL  // META_CTRL_ON
        return m
    }
}
