package pro.cslauncher.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test
import pro.cslauncher.app.ui.screens.*
import pro.cslauncher.app.ui.theme.*
import pro.cslauncher.core.settings.ThemeChoice

/**
 * Renders the real pixel UI components (rail, scene, panels, buttons, icons) in phone-landscape layouts with sample
 * data, so the look can be checked without a device. Run: ./gradlew :app:recordPaparazziDebug -Ppaparazzi
 */
class PixelScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 2340, screenHeight = 1080, orientation = ScreenOrientation.LANDSCAPE, softButtons = false),
        theme = "android:Theme.Material.NoActionBar",
        maxPercentDifference = 100.0,
    )

    private fun shot(tab: Tab, content: @Composable () -> Unit) = paparazzi.snapshot {
        CsTheme(ThemeChoice.PIXEL, reduceTransparency = false, reduceMotion = true) {
            CsBackground(animated = false) {
                Row(Modifier.fillMaxSize()) {
                    NavRail(tab, true, if (tab == Tab.DOWNLOADS) 2 else 0) {}
                    Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                }
            }
        }
    }

    @Test fun home() = shot(Tab.HOME) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1.1f)) {
                Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)) {
                    Column {
                        PixelTitle("CS LAUNCHER", fontSize = 30.sp)
                        Text("PRO · Java Edition", color = Color(0xFFBFC0C2), fontFamily = Monocraft, fontSize = 12.sp, style = TextStyle(shadow = PixelTextShadow))
                    }
                    SplashText("OptiFine ready!", Modifier.align(Alignment.BottomEnd).padding(end = 4.dp), fontSize = 13.sp)
                }
                PixelPanel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PixelSlot { PixelIcon("bolt", size = 36.dp) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            PixelTitle("OptiFine 1.21.11", fontSize = 17.sp)
                            Text("1.21.11 · OptiFine HD_U_J9", color = Color(0xFFB8B9BB), fontSize = 13.sp)
                        }
                        PixelIconButton("list", "Switch", {}); Spacer(Modifier.width(6.dp)); PixelIconButton("edit", "Edit", {})
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRowCompat { Chip("Renderer: mobileglues ✓"); Chip("RAM auto 2048 MB") }
                    Text("Last session ended normally · 2 h ago", color = Color(0xFF6CC349), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(12.dp))
                PixelButton("PLAY", {}, Modifier.fillMaxWidth(), style = PxStyle.PRIMARY, icon = "play", height = 64.dp, fontSize = 20.sp)
                Column(Modifier.padding(top = 10.dp)) {
                    PixelProgress(0.62f)
                    Text("Downloading assets · 2481/4012 files · 212.4 MB", color = Color(0xFFB8B9BB), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Badge("OFFLINE", Color(0xFFE0B020)); Spacer(Modifier.width(8.dp))
                    PixelButton("Steve_42", {}, icon = "head", height = 40.dp)
                }
                SectionTitle("Quick actions")
                listOf(
                    listOf("pickaxe" to "Mods", "painting" to "Packs", "world" to "Worlds", "chest" to "Modpacks", "gamepad" to "Controls"),
                    listOf("bolt" to "OptiFine", "record" to "Videos", "image" to "Shots", "shield" to "Verify", "book" to "Logs"),
                ).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (i, l) -> PixelTile(i, l, {}, Modifier.weight(1f)) }
                    }
                }
                SectionTitle("Performance")
                PixelPanel(Modifier.fillMaxWidth()) {
                    InfoRow("Preset", "Balanced"); InfoRow("Device memory", "7712 MB"); InfoRow("Graphics", "GLES 3.2 · Vulkan 1.3")
                }
            }
        }
    }

    @Test fun browse() = shot(Tab.BROWSE) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(250.dp).padding(start = 12.dp, top = 10.dp, end = 4.dp)) {
                PixelTitle("Browse", fontSize = 20.sp)
                Spacer(Modifier.height(10.dp))
                listOf("pickaxe" to "Mods", "chest" to "Modpacks", "painting" to "Resource packs", "sun" to "Shaders").forEachIndexed { i, (ic, l) ->
                    PixelButton(l, {}, Modifier.fillMaxWidth().padding(bottom = 6.dp), style = if (i == 0) PxStyle.SELECTED else PxStyle.NORMAL, icon = ic, height = 40.dp)
                }
                SectionTitle("For profile")
                PixelButton("Fabric 1.21.1", {}, Modifier.fillMaxWidth(), icon = "pickaxe", height = 40.dp)
                Text("1.21.1 · Fabric 0.16.5", color = Color(0xFFB8B9BB), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Column(Modifier.weight(1f).padding(start = 8.dp, end = 12.dp, top = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelTextField("", {}, Modifier.weight(1f), placeholder = "Search mods on Modrinth")
                    Spacer(Modifier.width(8.dp)); PixelIconButton("search", "Search", {}, style = PxStyle.PRIMARY, size = 44.dp)
                }
                Spacer(Modifier.height(10.dp))
                listOf(
                    Triple("Sodium", "Modern rendering engine and client-side optimization mod.", true),
                    Triple("Fabric API", "Lightweight and modular API providing common hooks.", true),
                    Triple("Iris Shaders", "A modern shader pack loader for Minecraft.", false),
                ).forEach { (t, d, inst) ->
                    PixelPanel(Modifier.fillMaxWidth().padding(bottom = 8.dp), padding = 8.dp) {
                        Row {
                            PixelSlot(size = 56.dp) { PixelIcon("pickaxe", size = 34.dp, tint = Color(0xFFB0B0B0)) }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t, color = Color.White, fontFamily = Monocraft, fontSize = 13.sp)
                                Text("by Example · 12.4M downloads", color = Color(0xFF8A8B8D), fontSize = 11.sp)
                                Text(d, color = Color(0xFFB8B9BB), fontSize = 12.sp, maxLines = 2)
                                if (inst) Box(Modifier.padding(top = 4.dp)) { Badge("INSTALLED", icon = "check") }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun profilesAndControls() = shot(Tab.PROFILES) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Profiles") {
                PixelButton("Modpack", {}, icon = "chest", height = 40.dp); PixelButton("OptiFine", {}, icon = "bolt", height = 40.dp)
                PixelIconButton("upload", "Import", {}); PixelButton("New", {}, icon = "plus", style = PxStyle.PRIMARY, height = 40.dp)
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Triple("grass", "Vanilla 26.3", true), Triple("pickaxe", "Fabric 1.21.1", false), Triple("chest", "Fabulously Optimized", false)).forEach { (ic, n, sel) ->
                    PixelPanel(Modifier.weight(1f), selected = sel, padding = 10.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PixelSlot(size = 48.dp) { PixelIcon(ic, size = 32.dp) }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                PixelTitle(n, fontSize = 14.sp)
                                Text("26.3 · Java 25", color = Color(0xFFB8B9BB), fontSize = 12.sp)
                                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { Badge("READY", icon = "check"); if (sel) Badge("SELECTED", Color(0xFF8FD3FF)) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            PixelPanel(Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                SwitchRow("Animated background", "Moving pixel landscape behind the launcher", true) {}
                SwitchRow("Reduce motion", "Disable non-essential animations", false) {}
                SliderRow("Render scale", 0.75f, 0.3f..1f, format = { "${(it * 100).toInt()}%" }) {}
                ChoiceRow("Theme", listOf("Pixel (blocky)", "Dark Graphite", "Glassmorphism"), "Pixel (blocky)", label = { it }) {}
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelButton("Cancel", {}); PixelButton("Delete", {}, style = PxStyle.DANGER, icon = "trash"); PixelButton("Disabled", {}, enabled = false)
                    PixelProgress(null, Modifier.width(160.dp).align(Alignment.CenterVertically))
                }
            }
        }
    }

    @Test fun iconSheet() = shot(Tab.SETTINGS) {
        Column(Modifier.padding(16.dp)) {
            PixelTitle("Icons", fontSize = 18.sp)
            PixelIconData.icons.keys.chunked(12).forEach { row ->
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { n -> Column(horizontalAlignment = Alignment.CenterHorizontally) { PixelSlot(size = 48.dp) { PixelIcon(n, size = 32.dp) }; Text(n, color = Color.White, fontSize = 9.sp) } }
                }
            }
        }
    }

    private class FakeGame : pro.cslauncher.app.game.GameMenuHost {
        override val settings = androidx.compose.runtime.mutableStateOf(pro.cslauncher.core.settings.LauncherSettings())
        override fun applySettings(s: pro.cslauncher.core.settings.LauncherSettings) { settings.value = s }
        override fun closeMenu() {}
        override fun controlsHidden() = false
        override fun toggleControls() {}
        override fun showKeyboard() {}
        override fun editControls() {}
        override fun tapKey(key: Int) {}
        override fun recording() = false
        override fun recordingPaused() = false
        override fun togglePause() {}
        override fun toggleRecording() {}
        override fun layouts() = pro.cslauncher.core.controls.DefaultLayouts.all()
        override fun currentLayoutId() = "default-touch"
        override fun switchLayout(id: String) {}
        override fun quitGame() {}
        override fun forceStop() {}
        override val gameLog = java.io.File("/nonexistent")
        override val rendererLog: java.io.File? = null
        override fun infoLines() = listOf("Frame rate" to "58 FPS", "Game process memory" to "1630 MB")
    }

    private fun gameShot(tab: pro.cslauncher.app.game.GameMenuTab) = paparazzi.snapshot {
        CsTheme(ThemeChoice.PIXEL, reduceTransparency = false, reduceMotion = true) {
            CsBackground(animated = false) {
                pro.cslauncher.app.game.GameMenu(FakeGame(), open = true, initialTab = tab)
            }
        }
    }

    /** In-game menu over the game (the game itself is a live GL surface, stood in for by the launcher scene). */
    @Test fun gameMenuQuick() = gameShot(pro.cslauncher.app.game.GameMenuTab.QUICK)
    @Test fun gameMenuControls() = gameShot(pro.cslauncher.app.game.GameMenuTab.CONTROLS)
}
