package pro.cslauncher.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.cslauncher.app.R
import pro.cslauncher.app.Services
import pro.cslauncher.app.install.InstallJob
import pro.cslauncher.app.ui.LauncherViewModel
import pro.cslauncher.app.ui.Overlay
import pro.cslauncher.app.ui.PlayState
import pro.cslauncher.app.ui.Tab
import pro.cslauncher.app.ui.theme.*
import pro.cslauncher.core.account.AccountType
import pro.cslauncher.core.launch.FixAction
import pro.cslauncher.core.profile.Profile
import java.time.Instant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val launcherGreen = Color(0xFF38C916)

/**
 * Authentic Cubiq Launcher / Minecraft Java Edition PC Replica Home Screen.
 * Profiles, installs, accounts and launch coordination backed by our core engine.
 */
@Composable
fun MinecraftLauncherHome(
    vm: LauncherViewModel,
    onOpen: (Overlay) -> Unit,
    onTab: (Tab) -> Unit
) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val selected = ui.selected
    val state = vm.playState(ui, jobs)
    val currentJob = selected?.let { (jobs["task:profile-${it.id}"] ?: jobs[it.effectiveVersionId])?.takeIf { job -> job.active } }
        ?: jobs.values.firstOrNull { it.active }
    val isBusy = state in setOf(PlayState.DOWNLOADING, PlayState.VERIFYING, PlayState.UPDATING, PlayState.PREPARING, PlayState.LAUNCHING, PlayState.PLAYING)
    val canPlay = state == PlayState.READY || state == PlayState.LAUNCH_FAILED
    val actionText = when (state) {
        PlayState.NO_PROFILE -> "CREATE INSTALLATION"
        PlayState.INSTALL -> "INSTALL"
        PlayState.LAUNCH_FAILED -> "TRY AGAIN"
        PlayState.REPAIR_REQUIRED -> "REPAIR"
        PlayState.DOWNLOADING, PlayState.UPDATING -> "${state.label.uppercase()}${currentJob?.fraction?.let { " ${(it * 100).toInt()}%" }.orEmpty()}"
        PlayState.PREPARING, PlayState.VERIFYING, PlayState.LAUNCHING, PlayState.PLAYING -> state.label.uppercase()
        else -> "PLAY"
    }

    var showFailure by remember { mutableStateOf(false) }
    var showAddOfflineDialog by remember { mutableStateOf(false) }
    var showAddElybyDialog by remember { mutableStateOf(false) }
    var offlineUsername by remember { mutableStateOf("") }
    var elyName by remember { mutableStateOf("") }
    var elyPassword by remember { mutableStateOf("") }
    var elyOtp by remember { mutableStateOf("") }

    if (showFailure) ui.failure?.let { failure ->
        val fix = failure.issues.firstOrNull { it.fix != FixAction.NONE }?.fix
        AlertDialog(
            onDismissRequest = { showFailure = false },
            containerColor = Color(0xFF171D24),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = { Text(failure.title, fontFamily = Monocraft, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    failure.issues.forEach { Text("• ${it.message}", modifier = Modifier.padding(bottom = 5.dp)) }
                    failure.diagnoses.forEach { Text("${it.kind.title}: ${it.explanation}", modifier = Modifier.padding(bottom = 5.dp)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showFailure = false
                    when (fix) {
                        FixAction.INSTALL_VERSION, FixAction.REPAIR_INSTALLATION -> selected?.let { vm.repair(it.effectiveVersionId) }
                        FixAction.SIGN_IN -> onOpen(Overlay.Accounts)
                        FixAction.INSTALL_RUNTIME, FixAction.INSTALL_COMPONENT, FixAction.FREE_STORAGE -> onTab(Tab.SETTINGS)
                        FixAction.CHOOSE_RENDERER, FixAction.EDIT_PROFILE, FixAction.INSTALL_LOADER, FixAction.FIX_GAME_DIRECTORY -> selected?.let { onOpen(Overlay.EditProfile(it.id)) }
                        else -> onOpen(Overlay.Logs)
                    }
                }) {
                    Text(if (fix == null || fix == FixAction.NONE) "Open logs" else "Fix issue", color = launcherGreen, fontFamily = Monocraft)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFailure = false; vm.dismissFailure() }) {
                    Text("Dismiss", color = Color(0xFFAAAAAA), fontFamily = Monocraft)
                }
            }
        )
    }

    // Add Offline Account Dialog
    if (showAddOfflineDialog) {
        AlertDialog(
            onDismissRequest = { showAddOfflineDialog = false },
            containerColor = Color(0xFF1E1E20),
            titleContentColor = Color.White,
            title = { Text("Add Offline Account", fontFamily = Monocraft, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter your player username:", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                    OutlinedTextField(
                        value = offlineUsername,
                        onValueChange = { offlineUsername = it },
                        singleLine = true,
                        placeholder = { Text("Player name", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF55FF55),
                            unfocusedBorderColor = Color(0xFF444444),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            },
            confirmButton = {
                CubiqMinecraftButton(
                    onClick = {
                        val name = offlineUsername.trim()
                        if (name.isNotEmpty()) {
                            vm.addOffline(name)
                            showAddOfflineDialog = false
                            offlineUsername = ""
                        }
                    },
                    style = MinecraftButtonStyle.GREEN,
                    text = "Add Account",
                    fontSize = 12.sp,
                    modifier = Modifier.height(34.dp)
                )
            },
            dismissButton = {
                TextButton(onClick = { showAddOfflineDialog = false }) {
                    Text("Cancel", color = Color(0xFFAAAAAA), fontFamily = Monocraft)
                }
            }
        )
    }

    // Add Ely.by Account Dialog
    if (showAddElybyDialog) {
        AlertDialog(
            onDismissRequest = { showAddElybyDialog = false },
            containerColor = Color(0xFF1E1E20),
            titleContentColor = Color.White,
            title = { Text("Add Ely.by Account", fontFamily = Monocraft, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sign in with your Ely.by credentials:", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                    OutlinedTextField(
                        value = elyName,
                        onValueChange = { elyName = it },
                        singleLine = true,
                        placeholder = { Text("Email or Username", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF55FF55),
                            unfocusedBorderColor = Color(0xFF444444),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = elyPassword,
                        onValueChange = { elyPassword = it },
                        singleLine = true,
                        placeholder = { Text("Password", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF55FF55),
                            unfocusedBorderColor = Color(0xFF444444),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    OutlinedTextField(
                        value = elyOtp,
                        onValueChange = { elyOtp = it },
                        singleLine = true,
                        placeholder = { Text("2FA Code (Optional)", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF55FF55),
                            unfocusedBorderColor = Color(0xFF444444),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            },
            confirmButton = {
                CubiqMinecraftButton(
                    onClick = {
                        val name = elyName.trim()
                        val pass = elyPassword
                        if (name.isNotEmpty() && pass.isNotEmpty()) {
                            vm.addElyBy(name, pass, elyOtp.trim())
                            showAddElybyDialog = false
                            elyName = ""
                            elyPassword = ""
                            elyOtp = ""
                        }
                    },
                    style = MinecraftButtonStyle.GREEN,
                    text = "Sign In",
                    fontSize = 12.sp,
                    modifier = Modifier.height(34.dp)
                )
            },
            dismissButton = {
                TextButton(onClick = { showAddElybyDialog = false }) {
                    Text("Cancel", color = Color(0xFFAAAAAA), fontFamily = Monocraft)
                }
            }
        )
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0F1012))) {
        // High-res Wilderness Bound Key Art Background
        Image(
            painter = painterResource(R.drawable.img_hero_wilderness),
            contentDescription = "Minecraft Java Edition",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center
        )

        // Gradient Scrim Overlay for contrast
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.50f to Color.Transparent,
                        0.75f to Color(0x99000000),
                        1.0f to Color(0xF00B0C0E)
                    )
                )
        )

        // Main Layout Column
        Column(Modifier.fillMaxSize()) {
            // 1. Top Account Header & Action Bar
            TopAccountHeaderBar(
                activeAccountName = ui.activeAccount?.username,
                activeAccountType = ui.activeAccount?.type?.name,
                accounts = ui.accounts.accounts,
                activeAccountId = ui.accounts.activeId,
                onSelectAccount = { vm.setActiveAccount(it) },
                onAddOffline = { showAddOfflineDialog = true },
                onAddMicrosoft = { vm.startMicrosoft() },
                onAddElyby = { showAddElybyDialog = true },
                onManageAccounts = { onOpen(Overlay.Accounts) },
                onOpenFiles = { onOpen(Overlay.Content(ContentSection.MODS)) },
                onOpenMultiplayer = { onOpen(Overlay.Servers) },
                onOpenDownloads = { onTab(Tab.DOWNLOADS) },
                onOpenSettings = { onTab(Tab.SETTINGS) }
            )

            // 2. Sub-Navigation Tabs: Play | Installations | Skins | Patch Notes
            SubNavigationTabs(
                activeTab = -1, // -1 is Play
                onTabSelected = { tabIndex ->
                    when (tabIndex) {
                        0 -> onTab(Tab.PROFILES)
                        1 -> onOpen(Overlay.Skins)
                        2 -> onOpen(Overlay.News(true))
                        else -> {}
                    }
                }
            )

            // Centered Minecraft Java Edition Logo
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.img_mc_java_logo),
                    contentDescription = "Minecraft Java Edition",
                    modifier = Modifier
                        .height(72.dp)
                        .widthIn(max = 340.dp)
                        .padding(horizontal = 24.dp),
                    contentScale = ContentScale.Fit
                )
            }

            // 3. Floating Bottom Dock
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Background Download Bar if any download tasks are active
                if (currentJob != null && currentJob.active) {
                    CompactBackgroundDownloadBar(
                        job = currentJob,
                        onExpand = { onTab(Tab.DOWNLOADS) }
                    )
                } else if (isBusy) {
                    LaunchBusyIndicator(state.label)
                }

                // Failure Alert Pill
                ui.failure?.let { failure ->
                    Text(
                        text = "⚠ ${failure.title}  ·  Tap for Details",
                        color = Color(0xFFFFA5A0),
                        fontFamily = Monocraft,
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xE01A2026))
                            .border(BorderStroke(1.dp, Color(0xFF552222)), RoundedCornerShape(4.dp))
                            .clickable { showFailure = true }
                            .padding(8.dp)
                    )
                }

                // Dock Row: Version Selector Pill & 3D Green PLAY Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: 3D Version Chooser Pill
                    Box(modifier = Modifier.weight(1.15f)) {
                        VersionSelectorPill(
                            selected = selected,
                            profiles = ui.profiles.profiles,
                            latestRelease = ui.manifest?.latestRelease,
                            onSelectProfile = { vm.select(it) },
                            onManageInstallations = { onTab(Tab.PROFILES) }
                        )
                    }

                    // Right: Textured Minecraft Green PLAY Button
                    CubiqMinecraftButton(
                        onClick = {
                            MinecraftUiClickSound.play(context)
                            if (state == PlayState.NO_PROFILE) onTab(Tab.PROFILES) else vm.primaryAction()
                        },
                        style = if (state == PlayState.REPAIR_REQUIRED) MinecraftButtonStyle.RED else MinecraftButtonStyle.GREEN,
                        enabled = !isBusy && !ui.launching && !ui.gameRunning,
                        text = actionText,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    )
                }
            }
        }
    }
}

/**
 * Top Account Header Bar:
 * Displays Player Head with cyan/blue border, Username with dropdown arrow, and Quick Action Buttons.
 */
@Composable
private fun TopAccountHeaderBar(
    activeAccountName: String?,
    activeAccountType: String?,
    accounts: List<pro.cslauncher.core.account.Account>,
    activeAccountId: String?,
    onSelectAccount: (String) -> Unit,
    onAddOffline: () -> Unit,
    onAddMicrosoft: () -> Unit,
    onAddElyby: () -> Unit,
    onManageAccounts: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenMultiplayer: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141416))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Player Profile Pill (Clickable for interactive dropdown switcher)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        MinecraftUiClickSound.play(context)
                        expanded = true
                    }
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar with cyan/blue rounded border
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(BorderStroke(2.dp, Color(0xFF1E88E5)), RoundedCornerShape(6.dp))
                        .background(Color(0xFF222222)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_mc_pc_profile),
                        contentDescription = "Player Face",
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = activeAccountName ?: "Player",
                            color = Color.White,
                            fontFamily = Monocraft,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Icon(
                            painter = painterResource(R.drawable.ic_keyboard_arrow_down),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Text(
                        text = activeAccountType?.let { "(${it.lowercase().replaceFirstChar(Char::uppercase)})" } ?: "(Offline)",
                        color = Color(0xFFAAAAAA),
                        fontFamily = Monocraft,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }

            // Right: Screen Rotation, FILES & MULTIPLAYER Action Buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // Screen Rotation Toggle
                CubiqMinecraftButton(
                    onClick = {
                        MinecraftUiClickSound.play(context)
                        val cur = activity?.resources?.configuration?.orientation
                        if (cur == Configuration.ORIENTATION_PORTRAIT) {
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        } else {
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                    },
                    style = MinecraftButtonStyle.STONE,
                    modifier = Modifier
                        .height(34.dp)
                        .width(38.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_screen_rotation),
                        contentDescription = "Rotate",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // FILES Button
                CubiqMinecraftButton(
                    onClick = {
                        MinecraftUiClickSound.play(context)
                        onOpenFiles()
                    },
                    style = MinecraftButtonStyle.STONE,
                    text = "FILES",
                    fontSize = 11.sp,
                    modifier = Modifier
                        .height(34.dp)
                        .widthIn(min = 60.dp)
                )

                // MULTIPLAYER Button
                CubiqMinecraftButton(
                    onClick = {
                        MinecraftUiClickSound.play(context)
                        onOpenMultiplayer()
                    },
                    style = MinecraftButtonStyle.GREEN,
                    text = "MULTIPLAYER",
                    fontSize = 11.sp,
                    modifier = Modifier
                        .height(34.dp)
                        .widthIn(min = 96.dp)
                )
            }
        }

        // Account Dropdown Menu
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(280.dp)
                .background(Color(0xFF1E1E20))
                .border(BorderStroke(1.5.dp, Color(0xFF383838)), RoundedCornerShape(4.dp))
                .padding(6.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_person_outlined),
                        contentDescription = null,
                        tint = Color(0xFF55FF55),
                        modifier = Modifier.size(16.dp)
                    )
                    Text("ACCOUNTS", color = Color.White, fontFamily = Monocraft, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF28282A))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("${accounts.size} Saved", color = Color(0xFFAAAAAA), fontFamily = Monocraft, fontSize = 9.sp)
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2D2D30)))
            Spacer(Modifier.height(4.dp))

            accounts.forEach { acc ->
                val active = acc.id == activeAccountId
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = acc.username,
                                color = if (active) Color(0xFF55FF55) else Color.White,
                                fontFamily = Monocraft,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "(${acc.type.name.lowercase().replaceFirstChar(Char::uppercase)})",
                                color = Color(0xFFAAAAAA),
                                fontFamily = Monocraft,
                                fontSize = 9.sp
                            )
                            if (active) {
                                Spacer(Modifier.width(6.dp))
                                Text("✓", color = Color(0xFF55FF55), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    },
                    onClick = {
                        onSelectAccount(acc.id)
                        expanded = false
                    }
                )
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2D2D30)))
            DropdownMenuItem(
                text = { Text("+ Add Offline Account", color = Color(0xFF55FF55), fontFamily = Monocraft, fontSize = 11.sp) },
                onClick = { expanded = false; onAddOffline() }
            )
            DropdownMenuItem(
                text = { Text("+ Add Microsoft Account", color = Color(0xFF8FD3FF), fontFamily = Monocraft, fontSize = 11.sp) },
                onClick = { expanded = false; onAddMicrosoft() }
            )
            DropdownMenuItem(
                text = { Text("+ Add Ely.by Account", color = Color(0xFFFFD477), fontFamily = Monocraft, fontSize = 11.sp) },
                onClick = { expanded = false; onAddElyby() }
            )
            DropdownMenuItem(
                text = { Text("Manage Accounts", color = Color.White, fontFamily = Monocraft, fontSize = 11.sp) },
                onClick = { expanded = false; onManageAccounts() }
            )
        }
    }
}

/**
 * Sub-Navigation Tabs: Play | Installations | Skins | Patch Notes
 */
@Composable
private fun SubNavigationTabs(
    activeTab: Int,
    onTabSelected: (Int) -> Unit
) {
    val tabs = listOf(
        Pair("Play", -1),
        Pair("Installations", 0),
        Pair("Skins", 1),
        Pair("Patch Notes", 2)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF141416))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { (title, tabIndex) ->
            val isSelected = activeTab == tabIndex
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .clickable { onTabSelected(tabIndex) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    color = if (isSelected) Color.White else Color(0xFF9E9E9E),
                    fontFamily = Monocraft,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(3.dp))
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(2.5.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(Color(0xFF38D122))
                    )
                } else {
                    Spacer(modifier = Modifier.height(2.5.dp))
                }
            }
        }
    }
}

/**
 * 3D Beveled Version Selector Pill with block crafting table icon
 */
@Composable
private fun VersionSelectorPill(
    selected: Profile?,
    profiles: List<Profile>,
    latestRelease: String?,
    onSelectProfile: (String) -> Unit,
    onManageInstallations: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val activeVer = selected?.versionId ?: latestRelease ?: "Default"
    val verTitle = if (selected == null) "Latest Release" else "Selected Version"

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF16181B))
                .border(BorderStroke(1.5.dp, Color(0xFF32353A)), RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.block_crafting_table),
                contentDescription = "Version",
                modifier = Modifier.size(26.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = verTitle,
                    color = Color(0xFFAAAAAA),
                    fontFamily = Monocraft,
                    fontSize = 9.sp
                )
                Text(
                    text = selected?.let { "${it.name} ($activeVer)" } ?: "Minecraft $activeVer",
                    color = Color.White,
                    fontFamily = Monocraft,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                painter = painterResource(R.drawable.ic_arrow_drop_down_rounded),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(280.dp)
                .background(Color(0xFF1C1C1E))
                .border(BorderStroke(1.5.dp, Color(0xFF383838)), RoundedCornerShape(3.dp))
        ) {
            if (profiles.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Latest Release ($latestRelease)",
                            color = Color.White,
                            fontFamily = Monocraft,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    onClick = { expanded = false; onManageInstallations() }
                )
            } else {
                profiles.forEach { prof ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(prof.name, color = Color.White, fontFamily = Monocraft, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Minecraft ${prof.versionId}", fontSize = 10.sp, color = Color(0xFFAAAAAA), fontFamily = Monocraft)
                            }
                        },
                        onClick = {
                            onSelectProfile(prof.id)
                            expanded = false
                        }
                    )
                }
            }

            DropdownMenuItem(
                text = {
                    Text(
                        text = "+ Manage Installations",
                        color = Color(0xFF55FF55),
                        fontFamily = Monocraft,
                        fontWeight = FontWeight.Bold
                    )
                },
                onClick = {
                    expanded = false
                    onManageInstallations()
                }
            )
        }
    }
}

/**
 * Compact Background Download Bar docked above bottom controls
 */
@Composable
private fun CompactBackgroundDownloadBar(
    job: InstallJob,
    onExpand: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "compact_dl")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "compact_alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1E1E20))
            .border(BorderStroke(1.dp, Color(0xFF383838)), RoundedCornerShape(6.dp))
            .clickable(onClick = onExpand)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_download),
                contentDescription = null,
                tint = Color(0xFF55FF55).copy(alpha = alpha),
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "DOWNLOADING • ${job.stage.uppercase()}",
                        color = Color.White,
                        fontFamily = Monocraft,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    job.fraction?.let {
                        Text(
                            text = "${(it * 100).toInt()}%",
                            color = Color(0xFF55FF55),
                            fontFamily = Monocraft,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                AppProgress(job.fraction)
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${job.finishedFiles}/${job.totalFiles} files · ${formatBytes(job.bytes)}",
                    color = Color(0xFFAAAAAA),
                    fontSize = 9.sp
                )
            }
        }
    }
}

/**
 * Launch Busy Indicator with rotating 3D block and pulsating green progress bar
 */
@Composable
private fun LaunchBusyIndicator(label: String) {
    val reduce = LocalReduceMotion.current
    val infiniteTransition = rememberInfiniteTransition(label = "mc_loading")
    val rotation by if (reduce) remember { mutableFloatStateOf(0f) } else {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(2400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "block_rotation"
        )
    }
    val pulseAlpha by if (reduce) remember { mutableFloatStateOf(1f) } else {
        infiniteTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_pulse"
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xD916181B))
            .border(BorderStroke(1.dp, Color(0xFF32353A)), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .graphicsLayer { rotationZ = rotation },
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.img_minecraft),
                contentDescription = "Loading Block",
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label.uppercase(),
                color = Color.White,
                fontFamily = Monocraft,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF222224))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(pulseAlpha)
                        .background(Color(0xFF55FF55))
                )
            }
        }
    }
}

/** Live official Java news and patch notes; no hard-coded release claims. */
@Composable
fun OfficialNewsScreen(patchNotes: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    var feed by remember { mutableStateOf<List<OfficialPatchNote>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<OfficialPatchNote?>(null) }
    var showReleases by remember { mutableStateOf(true) }
    var showSnapshots by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val load: (Boolean) -> Unit = { force -> scope.launch {
        loading = true
        val result = withContext(Dispatchers.IO) { runCatching { OfficialMinecraftFeed.load(context.applicationContext, forceRefresh = force) } }
        feed = result.getOrDefault(emptyList())
        error = result.isFailure || feed.isEmpty()
        loading = false
    } }
    LaunchedEffect(Unit) { load(false) }
    val notes = if (!patchNotes) feed else feed.filter { note ->
        val release = note.type.equals("release", ignoreCase = true)
        (release && showReleases) || (!release && showSnapshots)
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF141416)).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (patchNotes) "PATCH NOTES" else "LATEST NEWS", color = Color.White, fontFamily = Monocraft, fontSize = 18.sp, modifier = Modifier.weight(1f))
            PixelButton("Back", onBack, height = 32.dp, fontSize = 10.sp)
        }
        if (patchNotes) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("VERSIONS:", color = Color(0xFFAAAAAA), fontFamily = Monocraft, fontSize = 10.sp)
            PixelToggle(showReleases, { showReleases = it }); Text("Releases", color = Color.White, fontSize = 10.sp)
            Spacer(Modifier.width(6.dp))
            PixelToggle(showSnapshots, { showSnapshots = it }); Text("Snapshots", color = Color.White, fontSize = 10.sp)
        }
        when {
            loading && feed.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = launcherGreen) }
            feed.isEmpty() -> NewsEmpty(error, { load(true) })
            notes.isEmpty() -> Text("No notes match these filters.", color = Color(0xFFAAAAAA), modifier = Modifier.padding(top = 16.dp), fontSize = 11.sp)
            else -> LazyVerticalGrid(GridCells.Adaptive(260.dp), Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(notes) { note -> NewsCard(note, { selected = note }) }
            }
        }
    }
    selected?.let { note -> NoteDialog(note, { selected = null }, { openOfficialArticle(context, note) }) }
}

@Composable
private fun NewsCard(note: OfficialPatchNote, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 82.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xCB10151A)).clickable(onClick = onClick).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(92.dp).height(76.dp).clip(RoundedCornerShape(6.dp))) { RemoteCover(note.imageUrl, Modifier.fillMaxSize(), "grass") }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(note.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note.type.replace('-', ' ').replaceFirstChar { it.uppercase() }, color = Color.White.copy(alpha = 0.83f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(note.shortText.ifBlank { "Open the official Minecraft Java patch notes." }, color = Color.White.copy(alpha = 0.68f), fontSize = 9.sp, lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            Text(formatNewsDate(note.date), color = Color.White.copy(alpha = 0.54f), fontSize = 8.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Text("↗", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun NewsEmpty(error: Boolean, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (error) "Official news is unavailable right now." else "Loading Minecraft news…", color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp)
        if (error) TextButton(onClick = onRefresh) { Text("Try again", color = launcherGreen) }
    }
}

@Composable
private fun NoteDialog(note: OfficialPatchNote, onDismiss: () -> Unit, onOpen: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color(0xFF171D24), titleContentColor = Color.White, textContentColor = Color.White,
        title = { Text(note.title) }, text = { Column {
            Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(8.dp))) { RemoteCover(note.imageUrl, Modifier.fillMaxSize()) }
            Text(note.shortText, color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            Text("${note.type.replaceFirstChar { it.uppercase() }} · ${formatNewsDate(note.date)}", color = Color.White.copy(alpha = 0.56f), fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
        } }, confirmButton = { TextButton(onClick = onOpen) { Text("Read on Minecraft.net", color = launcherGreen) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Color.White.copy(alpha = 0.7f)) } })
}

private fun formatNewsDate(value: String): String = runCatching {
    val instant = Instant.parse(value)
    SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date.from(instant))
}.getOrDefault(value.take(10))

private fun openOfficialArticle(context: android.content.Context, note: OfficialPatchNote) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(note.articleUrl))) }
}

private fun openMinecraftNews(context: android.content.Context) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.minecraft.net/en-us/articles"))) }
}
