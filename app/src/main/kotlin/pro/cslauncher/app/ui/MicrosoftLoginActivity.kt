package pro.cslauncher.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import pro.cslauncher.core.account.MicrosoftLegacyAuthenticator

/** Isolated in-app browser used only for Microsoft's legacy Xbox/Minecraft OAuth redirect. */
class MicrosoftLoginActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URL = "cs.microsoft.url"
        const val EXTRA_STATE = "cs.microsoft.state"
        const val EXTRA_CODE = "cs.microsoft.code"
        const val EXTRA_RETURNED_STATE = "cs.microsoft.returnedState"
        const val EXTRA_ERROR = "cs.microsoft.error"

        private const val CALLBACK_HOST = "auth"
    }

    private var webView: WebView? = null
    private var addressLabel: TextView? = null
    private var progressBar: ProgressBar? = null
    private var expectedState: String? = null
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val authUrl = intent.getStringExtra(EXTRA_URL)
        expectedState = intent.getStringExtra(EXTRA_STATE)
        val authUri = runCatching { authUrl?.let { Uri.parse(it) } }.getOrNull()
        if (authUrl.isNullOrBlank() || expectedState.isNullOrBlank() || authUri == null || authUri.scheme != "https" ||
            authUri.host != "login.live.com" || authUri.path != "/oauth20_authorize.srf") {
            returnWithError("Microsoft sign-in could not be started safely. Please try again.")
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 22, 28))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            setBackgroundColor(Color.rgb(29, 32, 40))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "Microsoft sign-in"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(this).apply {
            text = "Cancel"
            setTextColor(Color.rgb(160, 205, 255))
            textSize = 14f
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener { cancelLogin() }
        })
        header.addView(titleRow)
        addressLabel = TextView(this).apply {
            text = "Checking Microsoft sign-in page…"
            setTextColor(Color.rgb(174, 182, 198))
            textSize = 12f
            setPadding(0, dp(5), 0, 0)
        }
        header.addView(addressLabel)
        header.addView(TextView(this).apply {
            text = "Enter your password only on the Microsoft page. CS Launcher does not ask for or save your Microsoft password."
            setTextColor(Color.rgb(174, 182, 198))
            textSize = 11f
            setPadding(0, dp(5), 0, 0)
        })
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        val browser = WebView(this)
        webView = browser
        configureWebView(browser)
        root.addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (savedInstanceState != null && browser.restoreState(savedInstanceState) != null) {
            updateAddress(browser.url)
        } else {
            CookieManager.getInstance().removeAllCookies {
                runOnUiThread {
                    if (!isFinishing && !completed) browser.loadUrl(authUrl)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(browser: WebView) {
        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            saveFormData = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true)
        browser.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar?.apply {
                    isIndeterminate = newProgress < 1 || newProgress >= 100
                    progress = newProgress
                }
            }
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                request?.url?.let(::handleNavigation) ?: true

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                url?.let { Uri.parse(it) }?.let(::handleNavigation) ?: true

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                updateAddress(url)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) updateAddress("Sign-in page could not load. Check your connection and try again.")
            }
        }
    }

    private fun handleNavigation(uri: Uri): Boolean {
        if (uri.scheme.equals(MicrosoftLegacyAuthenticator.CALLBACK_SCHEME, ignoreCase = true)) {
            if (uri.host != null && uri.host != CALLBACK_HOST) {
                returnWithError("Microsoft returned an unexpected sign-in redirect.")
                return true
            }
            val error = uri.getQueryParameter("error")
            if (error != null) {
                val description = uri.getQueryParameter("error_description")
                if (error == "access_denied") cancelLogin()
                else returnWithError(description?.take(240) ?: "Microsoft sign-in failed: $error")
                return true
            }
            val code = uri.getQueryParameter("code")
            val returnedState = uri.getQueryParameter("state")
            if (code.isNullOrBlank()) {
                returnWithError("Microsoft did not return an authorization code.")
            } else if (returnedState.isNullOrBlank() || returnedState != expectedState) {
                returnWithError("The Microsoft sign-in verification did not match. Please try again.")
            } else {
                finishWithResult(
                    RESULT_OK,
                    Intent().putExtra(EXTRA_CODE, code).putExtra(EXTRA_RETURNED_STATE, returnedState),
                )
            }
            return true
        }

        if (uri.scheme != "https") return true
        val host = uri.host?.lowercase() ?: return true
        if (!isMicrosoftHost(host)) {
            updateAddress("Stopped a redirect to a non-Microsoft website.")
            return true
        }
        return false
    }

    private fun isMicrosoftHost(host: String): Boolean = listOf("microsoft.com", "microsoftonline.com", "microsoftonline.us", "live.com", "xbox.com", "xboxlive.com")
        .any { host == it || host.endsWith(".$it") }

    private fun updateAddress(url: String?) {
        val uri = runCatching { url?.let(Uri::parse) }.getOrNull()
        val host = uri?.host?.lowercase()
        addressLabel?.text = when {
            host != null && isMicrosoftHost(host) -> "Official Microsoft sign-in · $host"
            host == null -> url?.take(100) ?: "Microsoft sign-in"
            else -> "Blocked non-Microsoft page"
        }
    }

    private fun cancelLogin() = finishWithResult(RESULT_CANCELED, null)

    private fun returnWithError(message: String) = finishWithResult(
        RESULT_CANCELED,
        Intent().putExtra(EXTRA_ERROR, message.take(240)),
    )

    private fun finishWithResult(resultCode: Int, data: Intent?) {
        if (completed) return
        completed = true
        setResult(resultCode, data)
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            clearCache(true)
            clearFormData()
        }
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android API; kept to support WebView back navigation")
    override fun onBackPressed() {
        val browser = webView
        if (browser?.canGoBack() == true) browser.goBack() else cancelLogin()
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
