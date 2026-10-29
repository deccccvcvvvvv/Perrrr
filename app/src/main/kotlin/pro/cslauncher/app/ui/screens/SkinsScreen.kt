package pro.cslauncher.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pro.cslauncher.app.ui.LauncherViewModel
import pro.cslauncher.app.ui.theme.*
import pro.cslauncher.core.account.AccountType

/** A truthful preview of the signed-in account's skin; changes are made on Minecraft.net. */
@Composable
fun SkinsScreen(vm: LauncherViewModel, onBack: () -> Unit, onAccounts: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val account = ui.activeAccount
    val context = LocalContext.current
    var loading by remember(account?.skinUrl) { mutableStateOf(false) }
    var skin by remember(account?.skinUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(account?.skinUrl) {
        skin = null
        val url = account?.skinUrl
        if (url != null && url.startsWith("https://textures.minecraft.net/")) {
            loading = true
            skin = ImageCache.load(context.applicationContext, url, 64)?.takeIf { it.width == 64 && (it.height == 32 || it.height == 64) }
            loading = false
        } else loading = false
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF141416))) {
        ScreenHeader("Skins", onBack) { HeaderAction("Accounts", "head", onAccounts) }
        Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF1A1A1C), RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFF353538), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                val image = skin
                if (image != null) SkinFront(image, Modifier.fillMaxHeight(0.86f).width(140.dp))
                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PixelIcon("head", size = 60.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(if (loading) "Loading skin…" else "No skin preview available", color = Color(0xFFAAAAAA), fontSize = 11.sp)
                }
            }
            Column(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("YOUR SKIN", color = Color.White, fontFamily = Monocraft, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(account?.username ?: "No account selected", color = Color.White, fontFamily = Monocraft, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when (account?.type) {
                    AccountType.MICROSOFT -> "Preview of the skin returned by your Minecraft account. Manage your skin on Minecraft.net."
                    AccountType.ELY_BY -> "Ely.by skins are managed through your account provider. Preview is unavailable here."
                    AccountType.OFFLINE -> "Offline accounts do not upload skins. The game may use its default skin."
                    null -> "Add an account to see its available skin."
                }, color = Color(0xFFB3B3B5), fontSize = 11.sp, lineHeight = 15.sp)
                if (account?.type == AccountType.MICROSOFT) {
                    PixelButton("Manage skin on Minecraft.net", {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.minecraft.net/en-us/msaprofile/mygames/editskin"))) }
                            .onFailure { vm.toast("Could not open the browser") }
                    }, Modifier.fillMaxWidth(), style = PxStyle.PRIMARY, icon = "head", height = 38.dp, fontSize = 11.sp)
                } else {
                    PixelButton("Manage accounts", onAccounts, Modifier.fillMaxWidth(), icon = "head", height = 38.dp, fontSize = 11.sp)
                }
                Text("This is a 2D front preview, not a 3D wardrobe. Changes on the website may require signing in again to refresh.", color = Color(0xFF88888C), fontSize = 10.sp, lineHeight = 14.sp)
            }
        }
    }
}

/** Nearest-neighbour front-facing skin preview. Supports 64x32 and 64x64 standard skins. */
@Composable
private fun SkinFront(skin: ImageBitmap, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val unit = minOf(size.width / 16f, size.height / 32f)
        val ox = (size.width - unit * 16f) / 2f
        val oy = (size.height - unit * 32f) / 2f
        fun part(sx: Int, sy: Int, w: Int, h: Int, x: Int, y: Int) {
            drawImage(skin, srcOffset = IntOffset(sx, sy), srcSize = IntSize(w, h),
                dstOffset = IntOffset((ox + x * unit).toInt(), (oy + y * unit).toInt()),
                dstSize = IntSize((w * unit).toInt().coerceAtLeast(1), (h * unit).toInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.None)
        }
        part(8, 8, 8, 8, 4, 0)
        part(40, 8, 8, 8, 4, 0)
        part(20, 20, 8, 12, 4, 8)
        part(44, 20, 4, 12, 0, 8)
        part(if (skin.height == 64) 36 else 44, if (skin.height == 64) 52 else 20, 4, 12, 12, 8)
        part(4, 20, 4, 12, 4, 20)
        part(if (skin.height == 64) 20 else 4, if (skin.height == 64) 52 else 20, 4, 12, 8, 20)
        if (skin.height == 64) {
            part(20, 36, 8, 12, 4, 8)
            part(44, 36, 4, 12, 0, 8)
            part(52, 52, 4, 12, 12, 8)
            part(4, 36, 4, 12, 4, 20)
            part(4, 52, 4, 12, 8, 20)
        }
    }
}
