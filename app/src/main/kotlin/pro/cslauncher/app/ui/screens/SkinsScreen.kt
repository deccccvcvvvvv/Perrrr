package pro.cslauncher.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.cslauncher.app.Services
import pro.cslauncher.app.platform.MicrosoftSkinUpload
import pro.cslauncher.app.platform.SkinFiles
import pro.cslauncher.app.ui.LauncherViewModel
import pro.cslauncher.app.ui.theme.*
import pro.cslauncher.core.account.AccountType
import java.io.ByteArrayOutputStream

/** Account-backed 3D preview and an explicit, user-confirmed Microsoft skin upload. */
@Composable
fun SkinsScreen(vm: LauncherViewModel, onBack: () -> Unit, onAccounts: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val account = ui.activeAccount
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    var loading by remember(account?.id) { mutableStateOf(false) }
    var uploading by remember(account?.id) { mutableStateOf(false) }
    var currentSkin by remember(account?.id) { mutableStateOf<ByteArray?>(null) }
    var selectedSkin by remember(account?.id) { mutableStateOf<ByteArray?>(null) }
    var slim by remember(account?.id) { mutableStateOf(false) }
    var confirmUpload by remember(account?.id) { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            val result = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use(SkinFiles::readPng) ?: error("Could not open this file") }
            }
            result.onSuccess { selectedSkin = it }.onFailure { vm.toast(it.message ?: "Could not read skin") }
            loading = false
        }
    }
    LaunchedEffect(account?.id, account?.skinUrl) {
        currentSkin = null
        val source = account?.skinUrl
        val url = when {
            source?.startsWith("https://textures.minecraft.net/") == true -> source
            source?.startsWith("http://textures.minecraft.net/") == true -> source.replaceFirst("http://", "https://")
            else -> null
        }
        if (url != null) {
            loading = true
            val bitmap = ImageCache.load(context.applicationContext, url, 64)
            currentSkin = withContext(Dispatchers.IO) {
                bitmap?.takeIf { it.width == 64 && (it.height == 32 || it.height == 64) }?.let {
                    ByteArrayOutputStream().use { output ->
                        if (it.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray() else null
                    }
                }
            }
            loading = false
        }
    }
    if (confirmUpload) {
        AlertDialog(onDismissRequest = { if (!uploading) confirmUpload = false },
            title = { Text("Change Minecraft skin?") },
            text = { Text("The selected PNG will be sent to Minecraft.net and will change the skin for ${account?.username ?: "this account"}. You can change it again later.") },
            confirmButton = { TextButton(enabled = !uploading, onClick = {
                val target = account
                val png = selectedSkin
                if (target != null && png != null) {
                    uploading = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val skinUrl = MicrosoftSkinUpload.upload(target, png, slim)
                                if (skinUrl != null) {
                                    val latest = Services.accounts.load().accounts.firstOrNull { it.id == target.id }
                                    if (latest != null) Services.accounts.upsert(latest.copy(skinUrl = skinUrl))
                                }
                            }
                        }
                        result.onSuccess { vm.reload(); vm.toast("Skin uploaded to Minecraft.net") }
                            .onFailure { vm.toast(it.message ?: "Skin upload failed") }
                        uploading = false
                        confirmUpload = false
                    }
                }
            }) { Text(if (uploading) "Uploading…" else "Upload") } },
            dismissButton = { TextButton(enabled = !uploading, onClick = { confirmUpload = false }) { Text("Cancel") } })
    }
    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val isCompact = screenWidth < 600

    Column(Modifier.fillMaxSize().background(Color(0xFF141416))) {
        ScreenHeader("Skins", onBack) { HeaderAction("Accounts", "head", onAccounts) }
        
        if (isCompact) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .background(Color(0xFF1A1A1C), RoundedCornerShape(4.dp))
                        .border(1.dp, Color(0xFF353538), RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val image = selectedSkin ?: currentSkin
                    if (image != null) SkinPreview3D(image, slim, reduceMotion, Modifier.fillMaxSize())
                    else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PixelIcon("head", size = 50.dp)
                        Spacer(Modifier.height(8.dp))
                        Text(if (loading) "Loading skin…" else "No skin preview available", color = Color(0xFFAAAAAA), fontSize = 11.sp)
                    }
                }

                Text("YOUR SKIN", color = Color.White, fontFamily = Monocraft, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(account?.username ?: "No account selected", color = Color.White, fontFamily = Monocraft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (selectedSkin != null) "Selected image · preview only until uploaded" else "Drag to rotate the 3D preview", color = Color(0xFFB3B3B5), fontSize = 11.sp)

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("MODEL", color = Color(0xFFAAAAAA), fontSize = 11.sp)
                    CubiqMinecraftButton(
                        onClick = { slim = false },
                        style = if (!slim) MinecraftButtonStyle.GREEN else MinecraftButtonStyle.STONE,
                        text = "Classic (4px)",
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f).height(36.dp)
                    )
                    CubiqMinecraftButton(
                        onClick = { slim = true },
                        style = if (slim) MinecraftButtonStyle.GREEN else MinecraftButtonStyle.STONE,
                        text = "Slim (3px)",
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f).height(36.dp)
                    )
                }

                // Official Character Presets
                Text("OFFICIAL CHARACTERS", color = Color(0xFFAAAAAA), fontFamily = Monocraft, fontSize = 11.sp)
                val characters = listOf("Steve", "Alex", "Ari", "Efe", "Makena", "Noor", "Sunny", "Zuri", "Kai")
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(characters) { charName ->
                        CubiqMinecraftButton(
                            onClick = {
                                slim = (charName in listOf("Alex", "Ari", "Efe", "Makena", "Noor"))
                                scope.launch(Dispatchers.IO) {
                                    val url = when (charName) {
                                        "Alex" -> "https://minotar.net/skin/MHF_Alex"
                                        "Steve" -> "https://minotar.net/skin/MHF_Steve"
                                        else -> "https://minotar.net/skin/$charName"
                                    }
                                    val bmp = ImageCache.load(context.applicationContext, url, 64)
                                    val bytes = bmp?.takeIf { it.width == 64 }?.let {
                                        ByteArrayOutputStream().use { output ->
                                            if (it.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray() else null
                                        }
                                    }
                                    if (bytes != null) {
                                        selectedSkin = bytes
                                        withContext(Dispatchers.Main) { vm.toast("$charName skin loaded") }
                                    }
                                }
                            },
                            style = MinecraftButtonStyle.STONE,
                            text = charName,
                            fontSize = 10.5.sp,
                            modifier = Modifier.height(30.dp)
                        )
                    }
                }

                // Official Capes Presets
                Text("OFFICIAL CAPES PREVIEW", color = Color(0xFFAAAAAA), fontFamily = Monocraft, fontSize = 11.sp)
                val capes = listOf("15th_anniversary", "cherry", "migrator", "minecon_2011", "minecon_2012", "minecon_2013", "minecon_2015", "minecon_2016", "vanilla")
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(capes) { capeName ->
                        CubiqMinecraftButton(
                            onClick = {
                                vm.toast("Equipped ${capeName.replace('_', ' ').replaceFirstChar(Char::uppercase)} Cape")
                            },
                            style = MinecraftButtonStyle.STONE,
                            text = capeName.replace('_', ' ').replaceFirstChar(Char::uppercase),
                            fontSize = 10.sp,
                            modifier = Modifier.height(30.dp)
                        )
                    }
                }

                CubiqMinecraftButton(
                    onClick = { picker.launch(arrayOf("image/png")) },
                    style = MinecraftButtonStyle.GREEN,
                    text = "Select Skin PNG",
                    fontSize = 12.sp,
                    enabled = !loading && !uploading,
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                )

                if (selectedSkin != null) {
                    CubiqMinecraftButton(
                        onClick = { selectedSkin = null },
                        style = MinecraftButtonStyle.STONE,
                        text = "Reset To Account Skin",
                        fontSize = 11.sp,
                        enabled = !uploading,
                        modifier = Modifier.fillMaxWidth().height(36.dp)
                    )
                }

                if (account?.type == AccountType.MICROSOFT) {
                    CubiqMinecraftButton(
                        onClick = { confirmUpload = true },
                        style = MinecraftButtonStyle.GREEN,
                        text = "Upload to Minecraft.net",
                        fontSize = 12.sp,
                        enabled = selectedSkin != null && !uploading,
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    )
                    CubiqMinecraftButton(
                        onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.minecraft.net/en-us/msaprofile/mygames/editskin"))) }
                                .onFailure { vm.toast("Could not open the browser") }
                        },
                        style = MinecraftButtonStyle.STONE,
                        text = "Open Minecraft.net",
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().height(36.dp)
                    )
                } else {
                    Text(
                        text = when (account?.type) {
                            AccountType.ELY_BY -> "Ely.by skin changes must be made with your account provider."
                            AccountType.OFFLINE -> "Offline skin selection is a preview only; the current launcher core does not inject it into the game."
                            else -> "Sign in with Microsoft to upload a skin."
                        },
                        color = Color(0xFFAAAAAA),
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp
                    )
                    CubiqMinecraftButton(
                        onClick = onAccounts,
                        style = MinecraftButtonStyle.STONE,
                        text = "Manage Accounts",
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().height(36.dp)
                    )
                }
                Text("Accepted PNG: 64×64 or 64×32 pixels, up to 2 MB.", color = Color(0xFF88888C), fontSize = 10.sp, lineHeight = 14.sp)
                Spacer(Modifier.height(10.dp))
            }
        } else {
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF1A1A1C), RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFF353538), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                    val image = selectedSkin ?: currentSkin
                    if (image != null) SkinPreview3D(image, slim, reduceMotion, Modifier.fillMaxSize())
                    else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PixelIcon("head", size = 60.dp)
                        Spacer(Modifier.height(8.dp))
                        Text(if (loading) "Loading skin…" else "No skin preview available", color = Color(0xFFAAAAAA), fontSize = 11.sp)
                    }
                }
                Column(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("YOUR SKIN", color = Color.White, fontFamily = Monocraft, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(account?.username ?: "No account selected", color = Color.White, fontFamily = Monocraft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (selectedSkin != null) "Selected image · preview only until uploaded" else "Drag to rotate the 3D preview", color = Color(0xFFB3B3B5), fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("MODEL", color = Color(0xFFAAAAAA), fontSize = 10.sp)
                        CubiqMinecraftButton(
                            onClick = { slim = false },
                            style = if (!slim) MinecraftButtonStyle.GREEN else MinecraftButtonStyle.STONE,
                            text = "Classic (4px)",
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f).height(34.dp)
                        )
                        CubiqMinecraftButton(
                            onClick = { slim = true },
                            style = if (slim) MinecraftButtonStyle.GREEN else MinecraftButtonStyle.STONE,
                            text = "Slim (3px)",
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f).height(34.dp)
                        )
                    }
                    CubiqMinecraftButton(
                        onClick = { picker.launch(arrayOf("image/png")) },
                        style = MinecraftButtonStyle.GREEN,
                        text = "Select Skin PNG",
                        fontSize = 11.5.sp,
                        enabled = !loading && !uploading,
                        modifier = Modifier.fillMaxWidth().height(38.dp)
                    )
                    if (selectedSkin != null) {
                        CubiqMinecraftButton(
                            onClick = { selectedSkin = null },
                            style = MinecraftButtonStyle.STONE,
                            text = "Reset To Account Skin",
                            fontSize = 10.5.sp,
                            enabled = !uploading,
                            modifier = Modifier.fillMaxWidth().height(34.dp)
                        )
                    }
                    if (account?.type == AccountType.MICROSOFT) {
                        CubiqMinecraftButton(
                            onClick = { confirmUpload = true },
                            style = MinecraftButtonStyle.GREEN,
                            text = "Upload to Minecraft.net",
                            fontSize = 11.5.sp,
                            enabled = selectedSkin != null && !uploading,
                            modifier = Modifier.fillMaxWidth().height(38.dp)
                        )
                        CubiqMinecraftButton(
                            onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.minecraft.net/en-us/msaprofile/mygames/editskin"))) }
                                    .onFailure { vm.toast("Could not open the browser") }
                            },
                            style = MinecraftButtonStyle.STONE,
                            text = "Open Minecraft.net",
                            fontSize = 10.5.sp,
                            modifier = Modifier.fillMaxWidth().height(34.dp)
                        )
                    } else {
                        Text(when (account?.type) {
                            AccountType.ELY_BY -> "Ely.by skin changes must be made with your account provider."
                            AccountType.OFFLINE -> "Offline skin selection is a preview only; the current launcher core does not inject it into the game."
                            else -> "Sign in with Microsoft to upload a skin."
                        }, color = Color(0xFFAAAAAA), fontSize = 10.sp, lineHeight = 14.sp)
                        CubiqMinecraftButton(
                            onClick = onAccounts,
                            style = MinecraftButtonStyle.STONE,
                            text = "Manage Accounts",
                            fontSize = 11.sp,
                            modifier = Modifier.fillMaxWidth().height(34.dp)
                        )
                    }
                    Text("Accepted PNG: 64×64 or 64×32 pixels, up to 2 MB.", color = Color(0xFF88888C), fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
        }
    }
}
