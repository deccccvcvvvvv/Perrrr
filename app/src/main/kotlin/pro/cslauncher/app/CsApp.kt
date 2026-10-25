package pro.cslauncher.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import pro.cslauncher.app.platform.AndroidDevice
import pro.cslauncher.app.platform.KeystoreSecretStore
import pro.cslauncher.core.account.AccountRepository
import pro.cslauncher.core.activity.ActivityLog
import pro.cslauncher.core.component.ComponentManager
import pro.cslauncher.core.download.DownloadManager
import pro.cslauncher.core.install.Installer
import pro.cslauncher.core.install.LauncherDirs
import pro.cslauncher.core.launch.LaunchCoordinator
import pro.cslauncher.core.meta.VersionManifestRepository
import pro.cslauncher.core.net.UrlConnectionTransport
import pro.cslauncher.core.platform.PlatformInfo
import pro.cslauncher.core.profile.ProfileStore
import pro.cslauncher.core.renderer.RendererRegistry
import pro.cslauncher.core.runtime.RuntimeManager
import pro.cslauncher.core.settings.SettingsStore
import pro.cslauncher.core.util.CsLog
import pro.cslauncher.core.util.LogLevel
import java.io.File

class CsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Services.init(this)
        val nm = getSystemService(NotificationManager::class.java)   // minSdk 26: channels always exist
        nm.createNotificationChannel(NotificationChannel(CHANNEL_INSTALL, getString(R.string.channel_install), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RECORD, getString(R.string.channel_record), NotificationManager.IMPORTANCE_LOW))
    }
    companion object { const val CHANNEL_INSTALL = "install"; const val CHANNEL_RECORD = "record" }
}

/**
 * Process-wide object graph. Created in every process (launcher and ":game"); everything is lazy so the game
 * process only touches what it needs. Game data lives in app-specific external storage (visible to file managers,
 * removed on uninstall); runtimes/components/natives must be in internal storage because they are dlopen()ed.
 */
object Services {
    lateinit var app: Context; private set
    val abi: String get() = Build.SUPPORTED_ABIS.first()
    val platform by lazy { PlatformInfo.android(abi, Build.VERSION.RELEASE) }
    val dirs by lazy {
        val ext = app.getExternalFilesDir(null) ?: app.filesDir
        val internal = app.filesDir.toPath()
        LauncherDirs(ext.toPath().resolve("minecraft"), nativeRoot = internal.resolve("natives"),
            runtimeRoot = internal.resolve("runtimes"), componentRoot = internal.resolve("components")).also { it.createBase() }
    }
    val http by lazy { UrlConnectionTransport() }
    val settings by lazy { SettingsStore(dirs.config.resolve("settings.json")) }
    val downloads by lazy { DownloadManager(http, maxConcurrency = settings.state.value.downloadConcurrency.coerceIn(1, 16)) }
    val installer by lazy { Installer(dirs, http, downloads, platform) }
    val manifests by lazy { VersionManifestRepository(http, dirs.cache.resolve("version_manifest_v2.json")) }
    val secrets by lazy { KeystoreSecretStore(File(app.filesDir, "secrets.json")) }
    val accounts by lazy { AccountRepository(dirs.config.resolve("accounts.json"), secrets) }
    val profiles by lazy { ProfileStore(dirs.config.resolve("profiles.json"), dirs) }
    val activity by lazy { ActivityLog(dirs.config.resolve("activity.json")) }
    val components by lazy { ComponentManager(dirs) }
    val runtimes by lazy { RuntimeManager(dirs, platform) }
    val renderers by lazy { RendererRegistry() }
    val device by lazy { AndroidDevice(app) }
    val coordinator by lazy { LaunchCoordinator(dirs, platform, installer, runtimes, renderers, components) }
    val launcherLogFile: File get() = File(app.filesDir, "logs/launcher.log")

    fun init(ctx: Context) {
        app = ctx.applicationContext
        launcherLogFile.parentFile?.mkdirs()
        CsLog.minLevel = LogLevel.INFO
        CsLog.addSink { r ->
            android.util.Log.println(when (r.level) { LogLevel.DEBUG -> 3; LogLevel.INFO -> 4; LogLevel.WARN -> 5; LogLevel.ERROR -> 6 }, "CS/" + r.tag, r.message)
            runCatching { launcherLogFile.appendText("${r.time} ${r.level} ${r.tag}: ${r.message}\n") }
        }
        // Keep the launcher log bounded.
        if (launcherLogFile.length() > 2_000_000) runCatching { launcherLogFile.renameTo(File(launcherLogFile.parentFile, "launcher.old.log")) }
    }
}
