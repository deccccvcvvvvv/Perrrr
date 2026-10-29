package pro.cslauncher.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * The Cubiq skinview animation and MIT skinview3d bundle are loaded from packaged assets.
 * No JavaScript bridge, file access, external navigation or network requests are allowed.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SkinPreview3D(png: ByteArray, slim: Boolean, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var pageReady by remember { mutableStateOf(false) }
    val dataUrl = remember(png) { "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP) }
    val view = remember(context) {
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        WebView(context).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = true
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                    request?.url?.toString() != "https://appassets.androidplatform.net/assets/skinview/skinview.html"
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                    val url = request?.url
                    if (url != null && url.scheme == "https" && url.host == "appassets.androidplatform.net" && url.path?.startsWith("/assets/skinview/") == true) {
                        loader.shouldInterceptRequest(url)?.let { return it }
                    }
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == "https://appassets.androidplatform.net/assets/skinview/skinview.html") pageReady = true
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/skinview/skinview.html")
        }
    }
    DisposableEffect(view) {
        onDispose { view.stopLoading(); view.destroy() }
    }
    LaunchedEffect(pageReady, dataUrl, slim, reduceMotion) {
        if (pageReady) {
            val model = if (slim) "slim" else "default"
            view.evaluateJavascript("loadSkin(${JSONObject.quote(dataUrl)}, ${JSONObject.quote(model)});", null)
            view.evaluateJavascript(if (reduceMotion) "skinViewer.animation = null;" else "startAnim('NewIdle');", null)
        }
    }
    Box(modifier) { AndroidView(factory = { view }, modifier = Modifier.fillMaxSize()) }
}
