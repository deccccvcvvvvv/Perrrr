package pro.cslauncher.app.ui.screens

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pro.cslauncher.app.ui.LauncherViewModel
import pro.cslauncher.app.ui.MicrosoftLoginActivity
import pro.cslauncher.app.ui.MsaState
import pro.cslauncher.app.ui.theme.*
import androidx.compose.ui.graphics.Color
import pro.cslauncher.app.ui.theme.LocalPalette
import pro.cslauncher.core.account.AccountType
import pro.cslauncher.core.account.OfflineIdentity

@Composable
fun AccountsScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val msa by vm.msa.collectAsStateWithLifecycle()
    val microsoftLoginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val code = result.data?.getStringExtra(MicrosoftLoginActivity.EXTRA_CODE)
            val returnedState = result.data?.getStringExtra(MicrosoftLoginActivity.EXTRA_RETURNED_STATE)
            if (code.isNullOrBlank()) vm.failMicrosoftLogin("Microsoft did not return an authorization code. Please try again.")
            else vm.completeMicrosoft(code, returnedState)
        } else {
            val error = result.data?.getStringExtra(MicrosoftLoginActivity.EXTRA_ERROR)
            if (error.isNullOrBlank()) vm.cancelMicrosoft() else vm.failMicrosoftLogin(error)
        }
    }
    LaunchedEffect(msa) {
        val login = msa as? MsaState.WebLogin ?: return@LaunchedEffect
        microsoftLoginLauncher.launch(Intent(ctx, MicrosoftLoginActivity::class.java).apply {
            putExtra(MicrosoftLoginActivity.EXTRA_URL, login.url)
            putExtra(MicrosoftLoginActivity.EXTRA_STATE, login.state)
        })
    }
    var elyName by remember { mutableStateOf("") }
    var elyPassword by remember { mutableStateOf("") }
    var elyOtp by remember { mutableStateOf("") }
    var offlineName by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Accounts", onBack)
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                if (ui.accounts.accounts.isEmpty()) PixelPanel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) { PixelIcon("user_add", size = 28.dp); Spacer(Modifier.width(10.dp)); Text("No account yet. Add Microsoft, Ely.by or a local offline account.", color = p.textSecondary, fontSize = 13.sp) }
                }
                ui.accounts.accounts.forEachIndexed { i, a ->
                    val active = a.id == ui.accounts.activeId
                    PixelPanel(Modifier.fillMaxWidth().padding(bottom = 8.dp).staggerIn(i).clickable { vm.setActiveAccount(a.id) }, selected = active, padding = 8.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PixelSlot(size = 40.dp) { AccountHead(a.skinUrl) }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PixelTitle(a.username, Modifier.weight(1f, fill = false), fontSize = 13.sp); Spacer(Modifier.width(6.dp))
                                    if (a.type == AccountType.MICROSOFT) Badge("MICROSOFT", Color(0xFF8FD3FF)) else if (a.type == AccountType.ELY_BY) Badge("ELY.BY", p.accent) else Badge("LOCAL", p.warning)
                                    if (active) { Spacer(Modifier.width(6.dp)); Badge("ACTIVE", icon = "check") }
                                }
                                if (a.type != AccountType.OFFLINE) Text(if ((a.accessTokenExpiresAtMs ?: 0) < System.currentTimeMillis()) "Session will be refreshed at launch" else "Signed in", color = p.textSecondary, fontSize = 10.sp)
                                else Text("Single-player and offline-mode servers only. Not a Minecraft login.", color = p.warning, fontSize = 10.sp, lineHeight = 12.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                            PixelIconButton("trash", "Sign out", { removing = a.id }, style = PxStyle.DANGER, size = 32.dp)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                PixelPanel(Modifier.fillMaxWidth()) {
                    PixelTitle("Microsoft account", fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    when (val s = msa) {
                        MsaState.Idle, is MsaState.Done -> {
                            if (s is MsaState.Done) Text("Signed in as ${s.name}", color = p.success, fontSize = 13.sp)
                            Text("Sign in with the Microsoft account that owns Minecraft: Java Edition. Microsoft’s login page opens inside the launcher; type your password only on an official Microsoft page.", color = p.textSecondary, fontSize = 11.sp, lineHeight = 13.sp)
                            Text("No Client ID setup is needed. The launcher does not save your Microsoft password; account session tokens are stored encrypted on this device. This legacy launcher-compatible sign-in may stop working if Microsoft changes or blocks it.", color = p.textMuted, fontSize = 10.sp, lineHeight = 12.sp, modifier = Modifier.padding(top = 4.dp))
                            Spacer(Modifier.height(8.dp)); PixelButton("Sign in with Microsoft", { vm.startMicrosoft() }, style = PxStyle.PRIMARY, icon = "user_add", height = 36.dp)
                        }
                        is MsaState.WebLogin -> {
                            Text("Opening Microsoft sign-in…", color = p.textSecondary, fontSize = 11.sp)
                            PixelProgress(null, Modifier.padding(top = 8.dp))
                            PixelButton("Cancel", { vm.cancelMicrosoft() }, height = 34.dp, modifier = Modifier.padding(top = 6.dp))
                        }
                        is MsaState.Working -> { Text(s.message, color = p.textSecondary, fontSize = 11.sp, lineHeight = 13.sp); PixelProgress(null, Modifier.padding(top = 8.dp)) }
                        is MsaState.Error -> { Text(s.message, color = p.danger, fontSize = 12.sp); PixelButton("Try again", { vm.startMicrosoft() }, icon = "refresh", height = 38.dp, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                PixelPanel(Modifier.fillMaxWidth()) {
                    PixelTitle("Ely.by account", fontSize = 13.sp)
                    PixelTextField(elyName, { elyName = it }, label = "Username or email")
                    PixelTextField(elyPassword, { elyPassword = it }, label = "Password", secret = true)
                    PixelTextField(elyOtp, { elyOtp = it }, label = "2FA code (if enabled)", secret = true)
                    PixelButton("Sign in to Ely.by", {
                        vm.addEly(elyName, elyPassword, elyOtp); elyPassword = ""; elyOtp = ""
                    }, enabled = elyName.isNotBlank() && elyPassword.isNotEmpty(), height = 36.dp)
                    Text("Ely.by is a separate sign-in provider. If 2FA is enabled, enter its current code above. Your password is sent to Ely.by over HTTPS for sign-in only and is not saved; the session is stored encrypted on this device.", color = p.textMuted, fontSize = 10.sp, lineHeight = 12.sp)
                    PixelButton("Create an Ely.by account", {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://account.ely.by/register"))) }
                            .onFailure { vm.toast("Could not open the browser") }
                    }, height = 32.dp, fontSize = 10.sp)
                }
                Spacer(Modifier.height(10.dp))
                PixelPanel(Modifier.fillMaxWidth()) {
                    PixelTitle("Local account", fontSize = 13.sp)
                    Text("For single-player and LAN/servers running in offline mode. Online-mode servers and Realms require a Microsoft account.", color = p.textSecondary, fontSize = 11.sp, lineHeight = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                    PixelTextField(offlineName, { offlineName = it }, placeholder = "Player name")
                    if (offlineName.isNotEmpty() && !OfflineIdentity.validName(offlineName)) Text("3–16 letters, digits or _", color = p.danger, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    PixelButton("Add offline profile", { vm.addOffline(offlineName); offlineName = "" }, enabled = OfflineIdentity.validName(offlineName), icon = "plus", height = 36.dp)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    removing?.let { id -> ConfirmDialog("Sign out?", "The account and its stored session are removed from this device. Profiles and worlds are not affected.", "Sign out", true, { vm.removeAccount(id) }, { removing = null }) }
}

/** Loads the account service's actual texture, never a third-party UUID proxy. */
@Composable
private fun AccountHead(url: String?) {
    val ctx = LocalContext.current
    var head by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(url) {
        head = if (url == null || !url.startsWith("https://textures.minecraft.net/")) null else {
            val skin = pro.cslauncher.app.ui.theme.ImageCache.load(ctx, url, 64)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                skin?.let {
                    val bitmap = it.asAndroidBitmap()
                    if (bitmap.width >= 64 && bitmap.height >= 32)
                        android.graphics.Bitmap.createBitmap(bitmap, 8, 8, 8, 8).asImageBitmap() else null
                }
            }
        }
    }
    val image = head
    if (image == null) PixelIcon("head", size = 26.dp)
    else androidx.compose.foundation.Image(image, "Account skin", Modifier.size(28.dp), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
}
