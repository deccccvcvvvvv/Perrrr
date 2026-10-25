package pro.cslauncher.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pro.cslauncher.app.ui.LauncherViewModel
import pro.cslauncher.app.ui.MsaState
import pro.cslauncher.app.ui.theme.CsCard
import pro.cslauncher.app.ui.theme.LocalPalette
import pro.cslauncher.core.account.AccountType
import pro.cslauncher.core.account.OfflineIdentity

@Composable
fun AccountsScreen(vm: LauncherViewModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val msa by vm.msa.collectAsStateWithLifecycle()
    var offlineName by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Accounts", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            ui.accounts.accounts.forEach { a ->
                val active = a.id == ui.accounts.activeId
                CsCard(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { vm.setActiveAccount(a.id) }, selected = active) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.username, color = p.text, fontWeight = FontWeight.Medium)
                            if (a.type == AccountType.MICROSOFT) Text("Microsoft account" + if ((a.accessTokenExpiresAtMs ?: 0) < System.currentTimeMillis()) " · session will be refreshed at launch" else "", color = p.textSecondary, fontSize = 13.sp)
                            else Text("Offline profile — single-player and servers in offline mode only. Not a Minecraft login.", color = p.warning, fontSize = 13.sp)
                        }
                        TextButton(onClick = { removing = a.id }) { Text("Sign out", color = p.danger) }
                    }
                }
            }

            SectionTitle("Microsoft account")
            CsCard(Modifier.fillMaxWidth()) {
                when (val s = msa) {
                    MsaState.Idle, is MsaState.Done -> {
                        if (s is MsaState.Done) Text("Signed in as ${s.name}", color = p.success)
                        Text("Sign in with the Microsoft account that owns Minecraft: Java Edition. You enter the code on Microsoft's own page; the launcher never sees your password.", color = p.textSecondary, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp)); Button(onClick = { vm.startMicrosoft() }, colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.background)) { Text("Sign in with Microsoft") }
                    }
                    is MsaState.Code -> {
                        Text("Open the page below and enter this code:", color = p.textSecondary)
                        Text(s.userCode, color = p.text, fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                        Text(s.uri, color = p.accent)
                        Row {
                            TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open browser", color = p.accent) }
                            TextButton(onClick = { vm.cancelMicrosoft() }) { Text("Cancel", color = p.textSecondary) }
                        }
                        Text("Waiting for confirmation… (code expires in ${((s.expiresAtMs - System.currentTimeMillis()) / 60000).coerceAtLeast(0)} min)", color = p.textMuted, fontSize = 12.sp)
                    }
                    is MsaState.Working -> { Text(s.message, color = p.textSecondary); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = p.accent) }
                    is MsaState.Error -> { Text(s.message, color = p.danger); TextButton(onClick = { vm.startMicrosoft() }) { Text("Try again", color = p.accent) } }
                }
            }

            SectionTitle("Offline profile")
            CsCard(Modifier.fillMaxWidth()) {
                Text("For single-player and LAN/servers running in offline mode. Online-mode servers and Realms require a Microsoft account.", color = p.textSecondary, fontSize = 13.sp)
                OutlinedTextField(offlineName, { offlineName = it }, label = { Text("Player name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = offlineName.isNotEmpty() && !OfflineIdentity.validName(offlineName), supportingText = { Text("3–16 letters, digits or _") })
                TextButton(onClick = { vm.addOffline(offlineName); offlineName = "" }, enabled = OfflineIdentity.validName(offlineName)) { Text("Add offline profile", color = p.accent) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    removing?.let { id -> ConfirmDialog("Sign out?", "The account and its stored session are removed from this device. Profiles and worlds are not affected.", "Sign out", true, { vm.removeAccount(id) }, { removing = null }) }
}
