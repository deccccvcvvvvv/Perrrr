package pro.cslauncher.app.ui

import android.app.Application
import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import pro.cslauncher.app.Services
import pro.cslauncher.app.install.InstallJob
import pro.cslauncher.app.install.InstallManager
import pro.cslauncher.app.install.JobKind
import pro.cslauncher.app.install.JobStatus
import pro.cslauncher.app.ui.screens.ContentSection
import pro.cslauncher.app.ui.theme.CsTheme
import pro.cslauncher.core.profile.*
import pro.cslauncher.core.settings.ThemeChoice
import java.io.File
import java.nio.file.Files

/**
 * Renders the REAL launcher (LauncherApp + every screen + the real view model) at true phone sizes, with sample
 * profiles/accounts/downloads seeded into a temporary launcher directory. This is what catches layouts that do not
 * fit a phone. Run: ./gradlew :app:recordPaparazziDebug -Ppaparazzi
 */
class RealScreensTest {
    @get:Rule
    val paparazzi = Paparazzi(
        // 2400×1080 at 480 dpi = 800×360 dp: the smallest common landscape phone (fullscreen, no system bars).
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 2400, screenHeight = 1080, xdpi = 480, ydpi = 480, density = Density.XXHIGH,
            orientation = ScreenOrientation.LANDSCAPE, softButtons = false),
        theme = "android:Theme.Material.NoActionBar",
        maxPercentDifference = 100.0,
    )

    private class FakeApp(base: Context, private val root: File) : Application() {
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getExternalFilesDir(type: String?) = File(root, "ext").apply { mkdirs() }
    }

    private object Owners : LifecycleOwner, OnBackPressedDispatcherOwner, ActivityResultRegistryOwner {
        private val reg = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = reg
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {}
        }
    }

    companion object {
        private var app: FakeApp? = null
        private var vmInstance: LauncherViewModel? = null

        @OptIn(ExperimentalCoroutinesApi::class)
        fun vm(ctx: Context): LauncherViewModel {
            vmInstance?.let { return it }
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val root = Files.createTempDirectory("cs-real-ui").toFile()
            val a = FakeApp(ctx, root); app = a
            Services.init(a)
            Services.settings.update { it.copy(theme = ThemeChoice.PIXEL, reduceMotion = true, animatedBackground = false) }
            val now = System.currentTimeMillis()
            val spunky = Profile(name = "Spunky Boost", versionId = "1.21.11", loader = LoaderSpec(LoaderType.FABRIC, "0.18.2"),
                launchVersionId = "fabric-loader-0.18.2-1.21.11", iconKey = "chest", lastPlayedAtMs = now - 4 * 60_000,
                lastRun = RunStatus(RunOutcome.SUCCESS, now - 4 * 60_000))
            val profiles = listOf(spunky,
                Profile(name = "Vanilla 26.3", versionId = "26.3", iconKey = "grass", rendererId = "mobileglues"),
                Profile(name = "OptiFine 1.21.11", versionId = "1.21.11", launchVersionId = "1.21.11-OptiFine_HD_U_J9", iconKey = "bolt",
                    lastRun = RunStatus(RunOutcome.CRASHED, now - 3_600_000, "LOADING_GAME", 1, "Renderer error")))
            val file = Services.dirs.config.resolve("profiles.json")
            Files.createDirectories(file.parent)
            Files.write(file, pro.cslauncher.core.util.CsJson.encodeToString(ProfilesFile.serializer(), ProfilesFile(selectedId = spunky.id, profiles = profiles)).toByteArray())
            Services.dirs.versionDir(spunky.effectiveVersionId).also { Files.createDirectories(it) }.resolve(".cs-installed").toFile().writeText("ok")
            Services.accounts.addOffline("Steve_42")
            @Suppress("UNCHECKED_CAST")
            val jobsField = InstallManager::class.java.getDeclaredField("_jobs").apply { isAccessible = true }.get(InstallManager) as MutableStateFlow<Map<String, InstallJob>>
            jobsField.value = mapOf(
                "26.3" to InstallJob("26.3", JobKind.INSTALL, JobStatus.RUNNING, "Downloading assets", finishedFiles = 2481, totalFiles = 4012, bytes = 212_400_000, startedAtMs = now - 95_000),
                "task:modpack" to InstallJob("task:modpack", JobKind.TASK, JobStatus.DONE, "Installed", finishedFiles = 128, totalFiles = 128, bytes = 88_000_000, startedAtMs = now - 600_000, title = "Modpack: Fabulously Optimized"),
                "1.20.1" to InstallJob("1.20.1", JobKind.VERIFY_REPAIR, JobStatus.FAILED, "Libraries", "3 files could not be downloaded (network)", 410, 413, 9_000_000, failedFiles = 3, startedAtMs = now - 900_000),
            )
            val v = LauncherViewModel(a); vmInstance = v
            val until = System.currentTimeMillis() + 5000
            while (v.ui.value.profiles.profiles.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
            return v
        }
    }

    private fun shot(tab: Tab = Tab.HOME, overlay: Overlay? = null, extra: @Composable () -> Unit = {}) {
        val vm = vm(paparazzi.context)
        paparazzi.snapshot {
            CompositionLocalProvider(
                androidx.lifecycle.compose.LocalLifecycleOwner provides Owners,
                LocalOnBackPressedDispatcherOwner provides Owners,
                LocalActivityResultRegistryOwner provides Owners,
                androidx.compose.ui.platform.LocalInspectionMode provides true,
            ) {
                CsTheme(ThemeChoice.PIXEL, reduceTransparency = false, reduceMotion = true) {
                    LauncherApp(vm, animatedBackground = false, initialTab = tab, initialOverlay = overlay)
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) { extra() }
                }
            }
        }
    }

    @Test fun home() = shot(Tab.HOME)
    @Test fun profiles() = shot(Tab.PROFILES)
    @Test fun browse() = shot(Tab.BROWSE)
    @Test fun downloads() = shot(Tab.DOWNLOADS)
    @Test fun settings() = shot(Tab.SETTINGS)
    @Test fun accounts() = shot(overlay = Overlay.Accounts)
    @Test fun logs() = shot(overlay = Overlay.Logs)
    @Test fun editProfile() = shot(overlay = Overlay.EditProfile(vm(paparazzi.context).ui.value.profiles.profiles.first().id))
    @Test fun contentMods() = shot(overlay = Overlay.Content(ContentSection.MODS))
    @Test fun optifine() = shot(overlay = Overlay.OptiFine(null))
    @Test fun newProfileDialog() = shot(Tab.PROFILES) { pro.cslauncher.app.ui.screens.CreateProfileDialog(vm(paparazzi.context), {}, {}) }

    /** A smaller phone in landscape: 1440x640 at 320 dpi = 720x320 dp. */
    private fun small() = paparazzi.unsafeUpdateConfig(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1440, screenHeight = 640, xdpi = 320, ydpi = 320,
        density = Density.XHIGH, orientation = ScreenOrientation.LANDSCAPE, softButtons = false))
    @Test fun smallHome() { small(); shot(Tab.HOME) }
    @Test fun smallProfiles() { small(); shot(Tab.PROFILES) }
    @Test fun smallSettings() { small(); shot(Tab.SETTINGS) }
    @Test fun smallNewProfileDialog() { small(); shot(Tab.PROFILES) { pro.cslauncher.app.ui.screens.CreateProfileDialog(vm(paparazzi.context), {}, {}) } }
    @Test fun smallBrowse() { small(); shot(Tab.BROWSE) }
    @Test fun smallDownloads() { small(); shot(Tab.DOWNLOADS) }
}
