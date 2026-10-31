package pro.cslauncher.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pro.cslauncher.app.R
import pro.cslauncher.app.ui.theme.MinecraftUiClickSound
import pro.cslauncher.app.ui.theme.Monocraft

/**
 * 1:1 Replica of the Cubiq Launcher / Minecraft PC Bottom Navigation Bar
 * Tabs: News | Play | Mods | Profile
 */
@Composable
internal fun NavBottomBar(
    tab: Tab,
    overlay: Overlay?,
    activeJobs: Int,
    onSelectTab: (Tab) -> Unit,
    onOpenOverlay: (Overlay) -> Unit
) {
    val context = LocalContext.current
    val selectedItem = when {
        overlay is Overlay.News && !overlay.patchNotes -> 0 // News
        tab == Tab.BROWSE || overlay is Overlay.Content -> 2 // Mods
        overlay == Overlay.Accounts -> 3 // Profile
        else -> 1 // Play (includes Home, Profiles, Skins, Patch Notes)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1B1B1E))
            .border(BorderStroke(1.5.dp, Color(0xFF2C2C32)), RoundedCornerShape(6.dp))
            .padding(3.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // 0: News
            MinecraftPCNavItem(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                iconRes = R.drawable.ic_mc_pc_news,
                label = "News",
                isSelected = selectedItem == 0,
                onClick = {
                    MinecraftUiClickSound.play(context)
                    onOpenOverlay(Overlay.News(false))
                }
            )

            // 1: Play
            MinecraftPCNavItem(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                iconRes = R.drawable.ic_mc_pc_versions,
                label = "Play",
                isSelected = selectedItem == 1,
                badgeCount = if (activeJobs > 0) activeJobs else 0,
                onClick = {
                    MinecraftUiClickSound.play(context)
                    onSelectTab(Tab.HOME)
                }
            )

            // 2: Mods
            MinecraftPCNavItem(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                iconRes = R.drawable.ic_mc_pc_mods,
                label = "Mods",
                isSelected = selectedItem == 2,
                onClick = {
                    MinecraftUiClickSound.play(context)
                    onSelectTab(Tab.BROWSE)
                }
            )

            // 3: Profile
            MinecraftPCNavItem(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                iconRes = R.drawable.ic_mc_pc_profile,
                label = "Profile",
                isSelected = selectedItem == 3,
                onClick = {
                    MinecraftUiClickSound.play(context)
                    onOpenOverlay(Overlay.Accounts)
                }
            )
        }
    }
}

@Composable
private fun MinecraftPCNavItem(
    modifier: Modifier = Modifier,
    iconRes: Int,
    label: String,
    isSelected: Boolean,
    badgeCount: Int = 0,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val outerBorder = if (isSelected) {
        if (isPressed) Color(0xFF1B5E20) else Color(0xFF22C55E)
    } else {
        if (isPressed) Color(0xFF18181B) else Color(0xFF24242A)
    }

    val topHighlight = if (isSelected) {
        if (isPressed) Color(0xFF2E7D32) else Color(0xFF4CAF50)
    } else {
        if (isPressed) Color(0xFF1E1E22) else Color(0xFF383842)
    }

    val bottomShadow = if (isSelected) {
        if (isPressed) Color(0xFF1B5E20) else Color(0xFF143015)
    } else {
        if (isPressed) Color(0xFF383842) else Color(0xFF121215)
    }

    val bgGradient = if (isSelected) {
        if (isPressed) listOf(Color(0xFF1C241D), Color(0xFF161C17))
        else listOf(Color(0xFF222B23), Color(0xFF1A221B))
    } else {
        if (isPressed) listOf(Color(0xFF222226), Color(0xFF1B1B1F))
        else listOf(Color(0xFF2C2C34), Color(0xFF222228))
    }

    val textColor = if (isSelected) Color(0xFF55FF55) else Color(0xFFCCCCCC)
    val textShadow = if (isSelected) Color(0xFF0F2B0A) else Color(0xFF101012)

    Column(
        modifier = modifier
            .offset { IntOffset(0, if (isPressed) 2 else 0) }
            .clip(RoundedCornerShape(4.dp))
            .border(BorderStroke(1.dp, outerBorder), RoundedCornerShape(4.dp))
            .background(Brush.verticalGradient(bgGradient))
            .drawWithContent {
                drawContent()
                val highlightThickness = 2.dp.toPx()
                val shadowThickness = 3.dp.toPx()

                // Top highlight line
                drawRect(
                    color = topHighlight,
                    topLeft = Offset(0.5f, 0.5f),
                    size = Size(size.width - 1f, highlightThickness)
                )
                // Left border highlight
                drawLine(
                    color = topHighlight.copy(alpha = 0.7f),
                    start = Offset(1f, 0.5f),
                    end = Offset(1f, size.height - shadowThickness),
                    strokeWidth = 1.5f
                )
                // Bottom shadow shelf (gives 3D physical height)
                drawRect(
                    color = bottomShadow,
                    topLeft = Offset(0.5f, size.height - shadowThickness),
                    size = Size(size.width - 1f, shadowThickness)
                )
                // Right border shadow
                drawLine(
                    color = bottomShadow.copy(alpha = 0.7f),
                    start = Offset(size.width - 1f, highlightThickness),
                    end = Offset(size.width - 1f, size.height - 0.5f),
                    strokeWidth = 1.5f
                )
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = label,
                modifier = Modifier.size(24.dp)
            )
            if (badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-4).dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF38C916))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = badgeCount.toString(),
                        color = Color.Black,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = Monocraft
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = textColor,
            fontFamily = Monocraft,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 9.sp,
            maxLines = 1,
            style = TextStyle(
                shadow = Shadow(color = textShadow, offset = Offset(1f, 1f), blurRadius = 1f)
            )
        )
    }
}
