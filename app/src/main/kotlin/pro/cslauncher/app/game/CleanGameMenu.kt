package pro.cslauncher.app.game

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pro.cslauncher.app.ui.screens.AppButton
import pro.cslauncher.app.ui.screens.SwitchRow
import pro.cslauncher.app.ui.theme.*
import pro.cslauncher.core.settings.CursorMode

@Composable
internal fun CleanGameMenu(host: GameMenuHost, open: Boolean, stats: List<Pair<String, String>>, initialTab: GameMenuTab) {
    val p = LocalPalette.current
    val reduce = LocalReduceMotion.current
    val s by host.settings
    var confirm by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }
    LaunchedEffect(open, initialTab) { if (open) showLog = initialTab == GameMenuTab.LOG }
    BackHandler(open && showLog) { showLog = false }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = if (reduce) EnterTransition.None else fadeIn(tween(140)), exit = if (reduce) ExitTransition.None else fadeOut(tween(110))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                .clickable(remember { MutableInteractionSource() }, null) { host.closeMenu() })
        }
        AnimatedVisibility(open, modifier = Modifier.align(Alignment.CenterEnd),
            enter = if (reduce) EnterTransition.None else slideInHorizontally(tween(180)) { it } + fadeIn(tween(140)),
            exit = if (reduce) ExitTransition.None else slideOutHorizontally(tween(150)) { it } + fadeOut(tween(110))) {
            val width = minOf(440, (LocalConfiguration.current.screenWidthDp * 0.72f).toInt()).coerceAtLeast(300).dp
            Surface(Modifier.fillMaxHeight().width(width).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical)),
                color = p.background, shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp), tonalElevation = 6.dp) {
                Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (showLog) {
                            TextButton(onClick = { showLog = false }) { Text("‹  Controls", color = p.accent) }
                            Text("Game log", color = p.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        } else {
                            Column(Modifier.weight(1f)) {
                                Text("Game controls", color = p.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                                Text("Session settings and launcher tools", color = p.textMuted, fontSize = 11.sp)
                            }
                            AppButton("Resume", { host.closeMenu() }, Modifier.heightIn(min = 38.dp), style = PxStyle.PRIMARY, icon = "play")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (showLog) {
                        LogTab(host)
                    } else {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Card(colors = CardDefaults.cardColors(containerColor = p.card), shape = RoundedCornerShape(9.dp)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    CleanSectionTitle("Controls")
                                    SwitchRow("Virtual mouse", "Trackpad-style pointer for menus", s.cursor.mode == CursorMode.TOUCHPAD) {
                                        host.applySettings(s.copy(cursor = s.cursor.withVirtualMouse(it)))
                                    }
                                    HorizontalDivider(color = p.border.copy(alpha = 0.6f))
                                    SwitchRow("Hide on-screen controls", "Temporarily clear the touch overlay", host.controlsHidden()) { host.toggleControls() }
                                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        AppButton("Keyboard", { host.closeMenu(); host.showKeyboard() }, Modifier.weight(1f), icon = "keyboard")
                                        AppButton("Edit controls", { host.closeMenu(); host.editControls() }, Modifier.weight(1f), icon = "edit")
                                    }
                                    val layouts = remember { host.layouts() }
                                    var currentLayout by remember { mutableStateOf(host.currentLayoutId()) }
                                    if (layouts.size > 1) {
                                        var expanded by remember { mutableStateOf(false) }
                                        Box {
                                            TextButton(onClick = { expanded = true }) {
                                                Text("Layout: ${layouts.firstOrNull { it.id == currentLayout }?.name ?: "Default"}  ▾", color = p.textSecondary)
                                            }
                                            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                                layouts.forEach { layout ->
                                                    DropdownMenuItem(text = { Text(layout.name) }, onClick = {
                                                        currentLayout = layout.id; expanded = false; host.switchLayout(layout.id)
                                                    })
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Card(colors = CardDefaults.cardColors(containerColor = p.card), shape = RoundedCornerShape(9.dp)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    CleanSectionTitle("Performance & capture")
                                    SwitchRow("Show FPS", "Display the frame-rate indicator in-game", s.touch.showFpsHud) {
                                        host.applySettings(s.copy(touch = s.touch.copy(showFpsHud = it)))
                                    }
                                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        AppButton(if (host.recording()) "Stop recording" else "Record screen", { host.closeMenu(); host.toggleRecording() }, Modifier.weight(1f), icon = if (host.recording()) "stop" else "record")
                                        if (host.recording()) AppButton(if (host.recordingPaused()) "Resume rec" else "Pause rec", { host.togglePause() }, Modifier.weight(1f), icon = "pause")
                                    }
                                    AppButton("View game log", { showLog = true }, Modifier.fillMaxWidth(), icon = "terminal")
                                    val fps = stats.firstOrNull { it.first == "Frame rate" }?.second
                                    val ram = stats.firstOrNull { it.first == "Game process memory" }?.second
                                    if (fps != null || ram != null) Text(listOfNotNull(fps?.let { "FPS $it" }, ram?.let { "Memory $it" }).joinToString("   ·   "), color = p.textMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                                }
                            }
                            Card(colors = CardDefaults.cardColors(containerColor = p.card), shape = RoundedCornerShape(9.dp)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    CleanSectionTitle("Session")
                                    Text("Quit saves and closes the game. Force stop immediately ends the process and may lose unsaved progress.", color = p.textSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                                    Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        AppButton("Save & quit", { confirm = "quit" }, Modifier.weight(1f), style = PxStyle.NORMAL, icon = "door")
                                        AppButton("Force stop", { confirm = "stop" }, Modifier.weight(1f), style = PxStyle.DANGER, icon = "stop")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { action ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(if (action == "quit") "Save and quit?" else "Force stop?", color = p.text) },
            text = { Text(if (action == "quit") "The game will save the world and return to the launcher." else "The game closes immediately. Unsaved progress may be lost.", color = p.textSecondary) },
            confirmButton = { TextButton(onClick = { confirm = null; if (action == "quit") host.quitGame() else host.forceStop() }) { Text(if (action == "quit") "Quit" else "Force stop", color = if (action == "quit") p.accent else p.danger) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel", color = p.textSecondary) } },
            containerColor = p.elevated)
    }
}

@Composable
private fun CleanSectionTitle(text: String) {
    val p = LocalPalette.current
    Text(text, color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
}
