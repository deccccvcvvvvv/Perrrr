package pro.cslauncher.app.ui.theme

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pro.cslauncher.app.R
import pro.cslauncher.core.settings.ThemeChoice

/** Palette used by every screen. [pixel] switches the shared components to the blocky Pocket-Edition-like look. */
data class CsPalette(
    val glass: Boolean,
    val background: Color, val surfaceSecondary: Color, val elevated: Color, val card: Color, val secondarySurface: Color,
    val border: Color, val text: Color, val textSecondary: Color, val textMuted: Color, val accent: Color, val selected: Color,
    val highlight: Color, val danger: Color = Color(0xFFE5484D), val success: Color = Color(0xFF6BCB8B), val warning: Color = Color(0xFFE2B86B),
    val pixel: Boolean = false,
)

val DarkGraphite = CsPalette(
    glass = false, background = Color(0xFF0B0B0E), surfaceSecondary = Color(0xFF111116), elevated = Color(0xFF17171D), card = Color(0xFF1E1E26),
    secondarySurface = Color(0xFF25252E), border = Color(0xFF34343F), text = Color(0xFFF0F1F5), textSecondary = Color(0xFFA5A7B2),
    textMuted = Color(0xFF777B87), accent = Color(0xFFC5CBD5), selected = Color(0xFF30343D), highlight = Color(0xFF30343D),
)

val Glassmorphism = CsPalette(
    glass = true, background = Color(0xFF080A0F), surfaceSecondary = Color(0xFF10131B), elevated = Color(0xFF10131B), card = Color(0xFF1A1E28),
    secondarySurface = Color(0xFF1A1E28), border = Color(0x1AFFFFFF), text = Color(0xFFF4F5F8), textSecondary = Color(0xFFA9AEBB),
    textMuted = Color(0xFF7D8290), accent = Color(0xFFC6CDD8), selected = Color(0x12FFFFFF), highlight = Color(0x12FFFFFF),
)

/** Blocky stone-and-grass palette inspired by Minecraft's mobile menus (original colour choices). */
val PixelPalette = CsPalette(
    glass = false, background = Color(0xFF1B1B1C), surfaceSecondary = Color(0xFF242425), elevated = Color(0xFF313233), card = Color(0xFF313233),
    secondarySurface = Color(0xFF48494A), border = Color(0xFF1E1E1F), text = Color(0xFFFFFFFF), textSecondary = Color(0xFFCFD0D2),
    textMuted = Color(0xFF9A9B9E), accent = Color(0xFF6CC349), selected = Color(0xFF3C8527), highlight = Color(0xFF6D6E70),
    danger = Color(0xFFE05050), success = Color(0xFF6CC349), warning = Color(0xFFFFD94A), pixel = true,
)

val LocalPalette = staticCompositionLocalOf { PixelPalette }
val LocalReduceMotion = staticCompositionLocalOf { false }
/** True when translucent glass surfaces may be used (glass theme, API 31+, user did not ask for reduced transparency). */
val LocalGlassEnabled = staticCompositionLocalOf { false }

val Monocraft = FontFamily(Font(R.font.monocraft, FontWeight.Normal), Font(R.font.monocraft_bold, FontWeight.Bold))

/** Pixel font for titles, buttons and labels; body copy stays in the system font for readability (as Minecraft's own mobile UI does). */
private fun pixelTypography(): Typography {
    val base = Typography()
    fun TextStyle.px(size: Int, weight: FontWeight = FontWeight.Normal) = copy(fontFamily = Monocraft, fontSize = size.sp, fontWeight = weight, letterSpacing = 0.sp, lineHeight = (size + 6).sp)
    return base.copy(
        displayLarge = base.displayLarge.px(40, FontWeight.Bold), displayMedium = base.displayMedium.px(32, FontWeight.Bold), displaySmall = base.displaySmall.px(26, FontWeight.Bold),
        headlineLarge = base.headlineLarge.px(26), headlineMedium = base.headlineMedium.px(22), headlineSmall = base.headlineSmall.px(19),
        titleLarge = base.titleLarge.px(18), titleMedium = base.titleMedium.px(15), titleSmall = base.titleSmall.px(13),
        labelLarge = base.labelLarge.px(13), labelMedium = base.labelMedium.px(12), labelSmall = base.labelSmall.px(10),
    )
}

@Composable
fun CsTheme(choice: ThemeChoice, reduceTransparency: Boolean, reduceMotion: Boolean, content: @Composable () -> Unit) {
    val p = when (choice) { ThemeChoice.GLASSMORPHISM -> Glassmorphism; ThemeChoice.DARK_GRAPHITE -> DarkGraphite; ThemeChoice.PIXEL -> PixelPalette }
    val glassOk = p.glass && !reduceTransparency && Build.VERSION.SDK_INT >= 31
    val scheme = darkColorScheme(
        primary = p.accent, onPrimary = if (p.pixel) Color.White else p.background, secondary = p.textSecondary, background = p.background, onBackground = p.text,
        surface = p.elevated, onSurface = p.text, surfaceVariant = p.secondarySurface, onSurfaceVariant = p.textSecondary,
        outline = if (p.pixel) Color(0xFF8B8C8E) else p.border, outlineVariant = p.border, error = p.danger, surfaceContainer = p.card, surfaceContainerHigh = p.secondarySurface,
        surfaceContainerHighest = p.secondarySurface, surfaceContainerLow = p.surfaceSecondary, secondaryContainer = p.selected, onSecondaryContainer = p.text,
        primaryContainer = p.selected, onPrimaryContainer = Color.White,
    )
    val shapes = if (p.pixel) androidx.compose.foundation.shape.RoundedCornerShape(0).let { sq -> Shapes(sq, sq, sq, sq, sq) } else Shapes()
    CompositionLocalProvider(LocalPalette provides p, LocalReduceMotion provides reduceMotion, LocalGlassEnabled provides glassOk) {
        MaterialTheme(colorScheme = scheme, typography = if (p.pixel) pixelTypography() else Typography(), shapes = shapes) {
            // Material3 Text does not read the typography by default; give plain Text calls the pixel font too
            // (family only, so explicit sizes and line heights elsewhere stay as they are).
            if (p.pixel) CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides TextStyle(fontFamily = Monocraft, letterSpacing = 0.sp), content = content)
            else content()
        }
    }
}

/**
 * Screen background. Pixel theme: the animated blocky landscape ([PixelScene]); glass theme: a static pre-blurred light field;
 * otherwise the opaque deep background.
 */
@Composable
fun CsBackground(modifier: Modifier = Modifier, animated: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val p = LocalPalette.current
    Box(modifier.fillMaxSize().background(p.background)) {
        if (p.pixel) {
            PixelScene(Modifier.fillMaxSize(), animate = animated && !LocalReduceMotion.current)
            Box(Modifier.fillMaxSize().background(Color(0x66000000)))
        } else if (LocalGlassEnabled.current) {
            Canvas(Modifier.fillMaxSize().blur(80.dp)) {
                drawCircle(Brush.radialGradient(listOf(Color(0x332F3A52), Color.Transparent), center = Offset(size.width * 0.15f, size.height * 0.12f), radius = size.width * 0.7f),
                    radius = size.width * 0.7f, center = Offset(size.width * 0.15f, size.height * 0.12f))
                drawCircle(Brush.radialGradient(listOf(Color(0x2240465A), Color.Transparent), center = Offset(size.width * 0.9f, size.height * 0.65f), radius = size.width * 0.6f),
                    radius = size.width * 0.6f, center = Offset(size.width * 0.9f, size.height * 0.65f))
            }
        }
        content()
    }
}

/** Card surface: a bevelled stone panel (pixel), an opaque graphite card, or a translucent glass panel. */
@Composable
fun CsCard(modifier: Modifier = Modifier, selected: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    if (p.pixel) { PixelPanel(modifier, selected = selected, content = content); return }
    val shape = RoundedCornerShape(18.dp)
    val bg = when {
        selected -> p.selected.copy(alpha = if (p.glass) 0.22f else 1f)
        p.glass && LocalGlassEnabled.current -> p.card.copy(alpha = 0.72f)
        else -> p.card
    }
    Column(modifier.clip(shape).background(bg).border(BorderStroke(1.dp, if (selected) p.accent.copy(alpha = 0.6f) else p.border), shape).padding(16.dp), content = content)
}
