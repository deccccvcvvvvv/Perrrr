package pro.cslauncher.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.cslauncher.app.ui.theme.PixelIconData

class PixelIconDataTest {
    private val usedByUi = listOf(
        "home", "grass", "search", "download", "settings", "play", "pickaxe", "painting", "chest", "world", "book", "gamepad", "shield", "record",
        "image", "head", "back", "plus", "more", "trash", "edit", "check", "close", "refresh", "folder", "info", "warning", "sun", "bolt", "star",
        "user_add", "list", "upload", "stop",
    )

    @Test fun everyIconIs12x12AndUsesKnownColours() {
        val known = PixelIconData.palette.keys + setOf('.', '#', '=', '+')
        for ((name, rows) in PixelIconData.icons) {
            assertEquals("$name rows", 12, rows.size)
            rows.forEachIndexed { y, r ->
                assertEquals("$name row $y width", 12, r.length)
                r.forEach { c -> assertTrue("$name uses unknown colour '$c'", c in known) }
            }
            assertTrue("$name is empty", rows.any { r -> r.any { it != '.' } })
        }
    }

    @Test fun everyIconTheUiReferencesExists() {
        for (n in usedByUi) assertTrue("missing icon $n", PixelIconData.icons.containsKey(n))
    }

    @Test fun paletteIsOpaque() {
        for ((c, argb) in PixelIconData.palette) assertEquals("colour $c alpha", 0xFFL, argb ushr 24)
    }
}
