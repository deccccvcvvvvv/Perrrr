/*
 * Adapted from Cubiq Launcher, MinecraftWidgets.kt at 289cc076012f5b2a7a58b88ccd355f7e912e1d5e.
 * https://github.com/VivxKumar07/Cubiq-Launcher
 * GPL-3.0: see assets/licenses/Cubiq-GPL-3.0.txt. Changes: CS font, sound, haptics and spinner.
 */
package pro.cslauncher.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class MinecraftButtonStyle {
    GREEN,
    STONE,
    DARK,
    RED
}

/**
 * Authentic 3D Textured Minecraft Java Edition Button
 * Exact 1:1 replica of the official Minecraft Java Edition "PLAY" and "New installation" buttons
 */
@Composable
fun CubiqMinecraftButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: MinecraftButtonStyle = MinecraftButtonStyle.GREEN,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    text: String? = null,
    fontSize: TextUnit = 15.sp,
    content: (@Composable BoxScope.() -> Unit)? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val actuallyPressed = isPressed && enabled && !isLoading

    // Colors matching official Minecraft UI button textures
    val (outerBorder, highlightBar, shadowBar, gradientBody, textColor, textShadow) = when (style) {
        MinecraftButtonStyle.GREEN -> {
            if (!enabled) {
                Tuple6(
                    Color(0xFF4A4A4A),
                    Color(0xFF26522B),
                    Color(0xFF142B17),
                    listOf(Color(0xFF1E3F23), Color(0xFF17311B)),
                    Color(0xFF6B876F),
                    Color(0xFF0E1A10)
                )
            } else if (actuallyPressed) {
                Tuple6(
                    Color(0xFFCCCCCC),
                    Color(0xFF103F1B), // Inverted shadow on top
                    Color(0xFF42A62C), // Inverted highlight on bottom
                    listOf(Color(0xFF205F1C), Color(0xFF184E15)),
                    Color(0xFFDCDCDC),
                    Color(0xFF081F0E)
                )
            } else {
                Tuple6(
                    Color(0xFF1B3D14), // Dark authentic Minecraft green border
                    Color(0xFF7AE842), // Top bright highlight line
                    Color(0xFF1E520F), // Bottom 3D bevel shadow
                    listOf(Color(0xFF58B62F), Color(0xFF3E8E22)), // Vivid Minecraft green
                    Color.White,
                    Color(0xFF0F2B09) // Dark green text shadow
                )
            }
        }
        MinecraftButtonStyle.STONE -> {
            // Authentic Minecraft GUI Slate/Stone 3D button (Options, Cancel, Back, Done)
            if (!enabled) {
                Tuple6(
                    Color(0xFF222224),
                    Color(0xFF3A3A3E),
                    Color(0xFF1A1A1C),
                    listOf(Color(0xFF2E2E32), Color(0xFF252528)),
                    Color(0xFF707075),
                    Color(0xFF121214)
                )
            } else if (actuallyPressed) {
                Tuple6(
                    Color(0xFF18181A),
                    Color(0xFF222226), // Pressed top shadow
                    Color(0xFF55555C), // Pressed bottom highlight
                    listOf(Color(0xFF2D2D32), Color(0xFF242428)),
                    Color(0xFFD0D0D0),
                    Color(0xFF101012)
                )
            } else {
                Tuple6(
                    Color(0xFF18181A), // Dark outer border
                    Color(0xFF686870), // Crisp top-edge 3D highlight
                    Color(0xFF242428), // Bottom bevel shelf
                    listOf(Color(0xFF45454B), Color(0xFF36363B)), // Medium-dark slate stone face
                    Color.White, // Authentic crisp white text
                    Color(0xFF141416) // Text shadow
                )
            }
        }
        MinecraftButtonStyle.DARK -> {
            if (!enabled) {
                Tuple6(
                    Color(0xFF161618),
                    Color(0xFF2A2A2E),
                    Color(0xFF1A1A1C),
                    listOf(Color(0xFF222226), Color(0xFF1A1A1E)),
                    Color(0xFF666666),
                    Color(0xFF101012)
                )
            } else if (actuallyPressed) {
                Tuple6(
                    Color(0xFF111113),
                    Color(0xFF1C1C20),
                    Color(0xFF4A4A52),
                    listOf(Color(0xFF26262B), Color(0xFF1F1F23)),
                    Color(0xFFD0D0D0),
                    Color(0xFF111113)
                )
            } else {
                Tuple6(
                    Color(0xFF111113),
                    Color(0xFF4A4A52), // Top highlight
                    Color(0xFF18181A), // Bottom shadow
                    listOf(Color(0xFF2E2E34), Color(0xFF232328)),
                    Color.White,
                    Color(0xFF101012)
                )
            }
        }
        MinecraftButtonStyle.RED -> {
            if (actuallyPressed) {
                Tuple6(
                    Color(0xFF220808),
                    Color(0xFF3D0E0E),
                    Color(0xFF8E2626),
                    listOf(Color(0xFF4C1515), Color(0xFF381010)),
                    Color(0xFFD0D0D0),
                    Color(0xFF180606)
                )
            } else {
                Tuple6(
                    Color(0xFF200606),
                    Color(0xFFE24646),
                    Color(0xFF4E1010),
                    listOf(Color(0xFF8A2424), Color(0xFF681A1A)),
                    Color.White,
                    Color(0xFF200606)
                )
            }
        }
    }

    Box(
        modifier = modifier
            .offset { IntOffset(0, if (actuallyPressed) 2 else 0) }
            .clip(RoundedCornerShape(3.dp))
            .border(BorderStroke(1.5.dp, outerBorder), RoundedCornerShape(3.dp))
            .background(Brush.verticalGradient(gradientBody))
            .drawWithContent {
                drawContent()
                val highlightThickness = 2.5.dp.toPx()
                val shadowThickness = 3.dp.toPx()

                // Top highlight bar
                drawRect(
                    color = highlightBar,
                    topLeft = Offset(1.5f, 1.5f),
                    size = Size(size.width - 3f, highlightThickness)
                )
                // Left subtle highlight
                drawLine(
                    color = highlightBar.copy(alpha = 0.6f),
                    start = Offset(1.5f, 1.5f),
                    end = Offset(1.5f, size.height - shadowThickness),
                    strokeWidth = 2f
                )
                // Bottom shadow bar
                drawRect(
                    color = shadowBar,
                    topLeft = Offset(1.5f, size.height - shadowThickness),
                    size = Size(size.width - 3f, shadowThickness)
                )
                // Right subtle shadow
                drawLine(
                    color = shadowBar.copy(alpha = 0.6f),
                    start = Offset(size.width - 1.5f, highlightThickness),
                    end = Offset(size.width - 1.5f, size.height - 1.5f),
                    strokeWidth = 2f
                )
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && !isLoading,
                onClick = {
                    MinecraftUiClickSound.play(context)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            MinecraftPixelDonutSpinner(
                size = 22.dp,
                baseColor = Color.White
            )
        } else if (content != null) {
            content()
        } else if (text != null) {
            Text(
                text = text,
                color = textColor,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                fontFamily = Monocraft,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = TextStyle(
                    shadow = Shadow(
                        color = textShadow,
                        offset = Offset(2f, 2f),
                        blurRadius = 0f
                    )
                )
            )
        }
    }
}


private data class Tuple6<A, B, C, D, E, F>(val a: A, val b: B, val c: C, val d: D, val e: E, val f: F)

@Composable
private fun MinecraftPixelDonutSpinner(size: androidx.compose.ui.unit.Dp = 28.dp, baseColor: Color = Color.White) {
    if (LocalReduceMotion.current) Text("…", color = baseColor) else
        CircularProgressIndicator(Modifier.size(size), color = baseColor, strokeWidth = 2.dp)
}
