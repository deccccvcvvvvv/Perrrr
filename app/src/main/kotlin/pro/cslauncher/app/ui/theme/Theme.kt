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
import androidx.compose.ui.unit.dp
import pro.cslauncher.core.settings.ThemeChoice

/** Exact palette values from the design specification. */
data class CsPalette(
    val glass: Boolean,
    val background: Color, val surfaceSecondary: Color, val elevated: Color, val card: Color, val secondarySurface: Color,
    val border: Color, val text: Color, val textSecondary: Color, val textMuted: Color, val accent: Color, val selected: Color,
    val highlight: Color, val danger: Color = Color(0xFFE5484D), val success: Color = Color(0xFF6BCB8B), val warning: Color = Color(0xFFE2B86B),
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

val LocalPalette = staticCompositionLocalOf { DarkGraphite }
val LocalReduceMotion = staticCompositionLocalOf { false }
/** True when translucent glass surfaces may be used (glass theme, API 31+, user did not ask for reduced transparency). */
val LocalGlassEnabled = staticCompositionLocalOf { false }

@Composable
fun CsTheme(choice: ThemeChoice, reduceTransparency: Boolean, reduceMotion: Boolean, content: @Composable () -> Unit) {
    val p = if (choice == ThemeChoice.GLASSMORPHISM) Glassmorphism else DarkGraphite
    val glassOk = p.glass && !reduceTransparency && Build.VERSION.SDK_INT >= 31
    val scheme = darkColorScheme(
        primary = p.accent, onPrimary = p.background, secondary = p.textSecondary, background = p.background, onBackground = p.text,
        surface = p.elevated, onSurface = p.text, surfaceVariant = p.secondarySurface, onSurfaceVariant = p.textSecondary,
        outline = p.border, outlineVariant = p.border, error = p.danger, surfaceContainer = p.card, surfaceContainerHigh = p.secondarySurface,
        surfaceContainerLow = p.surfaceSecondary, secondaryContainer = p.selected, onSecondaryContainer = p.text,
    )
    CompositionLocalProvider(LocalPalette provides p, LocalReduceMotion provides reduceMotion, LocalGlassEnabled provides glassOk) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}

/**
 * Screen background. The glass theme draws a static, pre-blurred light field once (no animation, no per-frame work);
 * with reduced transparency or below API 31 it falls back to the opaque deep background.
 */
@Composable
fun CsBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val p = LocalPalette.current
    Box(modifier.fillMaxSize().background(p.background)) {
        if (LocalGlassEnabled.current) {
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

/** Card surface: opaque graphite card, or a translucent glass panel with a hairline border. */
@Composable
fun CsCard(modifier: Modifier = Modifier, selected: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(18.dp)
    val bg = when {
        selected -> p.selected.copy(alpha = if (p.glass) 0.22f else 1f)
        p.glass && LocalGlassEnabled.current -> p.card.copy(alpha = 0.72f)
        else -> p.card
    }
    Column(modifier.clip(shape).background(bg).border(BorderStroke(1.dp, if (selected) p.accent.copy(alpha = 0.6f) else p.border), shape).padding(16.dp), content = content)
}
