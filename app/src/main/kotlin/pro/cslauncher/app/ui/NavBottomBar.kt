package pro.cslauncher.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pro.cslauncher.app.ui.theme.LocalPalette
import pro.cslauncher.app.ui.theme.MinecraftUiClickSound
import pro.cslauncher.app.ui.theme.PixelIcon

/** Compact tab bar for portrait phones; landscape keeps the wider launcher rail. */
@Composable
internal fun NavBottomBar(selected: Tab, activeJobs: Int, onSelect: (Tab) -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().background(p.surfaceSecondary)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        .padding(horizontal = 5.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Tab.entries.forEach { tab ->
            val active = selected == tab
            Column(Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                .background(if (active) p.selected else androidx.compose.ui.graphics.Color.Transparent)
                .clickable { MinecraftUiClickSound.play(context); onSelect(tab) }
                .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box {
                    PixelIcon(tab.icon, size = 19.dp, tint = if (active) p.accent else p.textSecondary)
                    if (tab == Tab.DOWNLOADS && activeJobs > 0) Text(activeJobs.toString(), color = p.accent, fontSize = 8.sp,
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-4).dp))
                }
                Text(tab.label, color = if (active) p.text else p.textMuted, fontSize = 9.sp, maxLines = 1)
            }
        }
    }
}
