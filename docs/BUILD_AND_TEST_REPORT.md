# Build & Test Report — CS Launcher Pro 1.0.0

Everything in this report was produced on the build host described below. Nothing is estimated or copied from elsewhere.
Raw evidence for the host end-to-end run is in `docs/evidence/`.

## Build host

| | |
|---|---|
| OS / CPU / RAM | Debian 13 x86_64, 2 vCPU, ~2 GB RAM + 3 GB swap file (added for the APK build) |
| Virtualisation | **No `/dev/kvm`** → no Android emulator possible |
| Toolchain | Temurin JDK 17.0.x, Gradle 8.7, AGP 8.5.2, Kotlin 2.0.20, Android SDK 34, build-tools 34.0.0, NDK 26.3.11579264, CMake 3.22.1 (installed by `tools/setup_toolchain.sh`) |
| Date | 2026-10-25 |

## Summary

| Check | Command | Result |
|---|---|---|
| Core unit tests | `./gradlew :core:test` | **93 tests, 0 failures, 0 errors, 0 skipped** (13 classes) |
| Host end-to-end (real Mojang install + launch of 1.18.2) | `./gradlew :core:hostE2E` (see below) | **PASSED**: install, targeted repair, launch to title screen, crash = none |
| Native libraries | CMake via `:app:assembleDebug` | `libcsglfw.so` + `libcsbridge.so` built for arm64-v8a, armeabi-v7a, x86_64 with `-Wall -Wextra`: 0 warnings |
| GLFW API coverage | `llvm-readelf --dyn-syms` | 124 exported `glfw*` functions per ABI; 0 functions missing vs. the GLFW calls bound by LWJGL 3.2.2 / 3.3.3 |
| JNI contract | dexdump + readelf | all 14 `external fun` in `NativeBridge` have exported `Java_…` symbols; all 7 native→Kotlin callbacks exist and survive R8 |
| Android API level (minSdk 26) | `./gradlew :app:checkAndroidApi` | 767 class files (`:core` + `:app`), **0 incompatible references** |
| Debug APK | `./gradlew :app:assembleDebug` | **BUILD SUCCESSFUL**: `app-debug.apk`, 9,970,441 bytes, signed with the debug key, verified by `apksigner verify` |
| Release APK (R8 minify + resource shrink) | `./gradlew :app:assembleRelease` | **BUILD SUCCESSFUL**: `app-release-unsigned.apk`, 1,553,034 bytes (unsigned; no release key exists) |
| Android Lint | `./gradlew :app:lintDebug` | **0 errors**; 14 warnings (listed below) |
| Kotlin compiler warnings (`:app`) | | 0 |
| **On-device run** | — | **NOT PERFORMED.** No device or emulator was available. The APK was never installed. |

Archived artefact: `release-artifacts/CSLauncherPro-1.0.0-debug.apk`
SHA-256 `3c0513b7a9b577c57003bf94066e7de6e74ef6f8dc4b54b57e8247d81da3934b`.

`aapt2 dump badging`: package `pro.cslauncher.app`, versionCode 1, versionName 1.0.0, sdkVersion 26, targetSdkVersion 34,
native-code `arm64-v8a armeabi-v7a x86_64`, launchable activity `pro.cslauncher.app.ui.MainActivity`, label "CS Launcher Pro".

## Core unit tests (93)

| Test class | Tests | Covers |
|---|---|---|
| MetadataTest | 9 | modern + legacy version JSON, manifest filtering, malformed-metadata rejection, OS/arch/feature rules, inherited (loader) profile merge, Maven coordinates, asset-index validation, desktop natives excluded on Android |
| DownloadAndInstallTest | 11 | checksum mismatch leaves no file, skip-when-valid, retries, HTTP Range resume, 404 permanence + empty-file rejection, dedup, storage pre-check, batch progress, full install + verify + targeted repair, failure stage in report, corrupt metadata re-download |
| SafeArchiveTest | 5 | path traversal / absolute paths, writes through symlinks, zip-bomb limit, duplicates, tar extraction with strip |
| LaunchTest | 9 | desktop plan from metadata, Android plan (LWJGL substitution + GLFW bridge), legacy args + token placeholder, JVM-arg normaliser/validation, launch locks, preflight corrective actions, state machine + failure stage, startup milestones from real log lines |
| RuntimeProfileAccountTest | 8 | runtime selection policy, verified runtime install from zip, profile lifecycle + migration, memory advisor never uses all RAM, JVM-arg validation, settings corruption recovery, offline accounts + secrets never in metadata, redactor |
| RendererSelectionTest | 5 | no fake support, backend configuration, auto picks Zink for 1.17+ / GL4ES for older, explicit choice honoured or rejected (never swapped), memory + crash-loop protection |
| ControlsTest | 20 | hold/toggle/double-tap/repeat buttons, joystick deadzone + hysteresis, camera tap/long-press break, simultaneous joystick+button+camera, hotbar swipe, menu-mode pointing, touchpad/dynamic cursor, modifier controls + mode switch, shared-key ref counting, safe-area geometry, gamepad, Android key map, editor undo/redo + coalescing, default layouts validate + round-trip, invalid imports rejected |
| ControlLayoutStoreTest | 2 | default always available + overridable, import never overwrites + rejects invalid files |
| CrashAnalyzerTest | 6 | classification of known failures, actionable details, newest crash report + hs_err collection, exit-code fallback, confidence ordering + redaction, repair engine refuses destructive actions |
| RealLogTest | 4 | Log4j XML flattening, real 1.18.2 start reaches READY without false crash, benign startup errors ignored, fatal markers still detected |
| RecordingPlannerTest | 3 | aligned sizes never upscaled, step-down to encoder limits/fps, bitrate/HEVC/audio rules |
| ContentTest | 10 | mod inspection (all formats), mod toggle/compat/trash, worlds list/rename/backup/restore/in-use, safe world import, resource + shader packs, NBT round-trip, servers.dat round-trip with backup, server address parsing, version predicates, options.txt preserves unknown lines |
| StorageTest | 1 | clears only regenerable data and keeps recent sessions |

## Host end-to-end run

Command (after `:core:printTestClasspath`):
```
DISPLAY=:99 LIBGL_ALWAYS_SOFTWARE=1 java -Xmx256m -cp "<core test classpath>" pro.cslauncher.core.e2e.HostE2EKt \
    1.18.2 ~/.cache/cs-e2e docs/evidence launch 768 45
```
The same `:core` code that the Android app uses performed these steps against Mojang's real servers. Only the platform layer differed: Linux, Xvfb, and Mesa llvmpipe.

* Manifest: 920 versions loaded.
* Install of 1.18.2: 3,099 files in 12 s. A second install skipped every file.
* A deliberately corrupted library JAR was detected and re-downloaded by targeted repair.
* Launch: state transitions `VALIDATING → PREPARING_RUNTIME → PREPARING_RENDERER → INITIALIZING_GAME → LOADING_GAME → RUNNING → EXITED`.
  * Milestones `JVM_STARTED, MAIN_ENTERED, GAME_INIT, RENDER_INIT, READY`; time to READY 16 s.
  * Crash analysis: none. The process was stopped by the launcher after the screenshot.
* Evidence:
  * Title-screen screenshot `docs/evidence/host-e2e-1.18.2-title.png`.
  * Logs `host-e2e-1.18.2-{install,launch,game}.log`.

This proves that the metadata, rules, download, verification, argument, state-machine and crash-analysis code works. It does **not** prove that the game runs on Android (see `COMPATIBILITY.md`).

## Defects found and fixed during this build phase

| Found by | Defect | Fix |
|---|---|---|
| New API-level checker | `:core` used `Files.readString/writeString` (absent from Android), `Stream.toList()` (API 34), `InputStream.nullInputStream()` (API 33). These compile against JDK 17 but would throw `NoSuchMethodError` on most devices | Replaced with `FsUtil.readText/writeText`, `Collectors.toList()`, an empty `ByteArrayInputStream`. Checker added as `:app:checkAndroidApi`, wired into `check` |
| CMake link | `libcsbridge` could not link `cs_host_log_line` / `cs_host_jvm_exit` (hidden by `-fvisibility=hidden`) | Exported with `CS_EXPORT`; verified with `-Wl,--no-undefined` |
| Lint `RestrictedApi` (7) | `super.dispatchKeyEvent` on androidx `ComponentActivity` | Key handling moved to public `onKeyDown/onKeyUp/onKeyMultiple`; IME text commits are now also forwarded as GLFW char events |
| Lint `StateFlowValueCalledInComposition` (2) | control editor read settings without observing them | `collectAsState()` |
| Lint `NewApi` | `windowLayoutInDisplayCutoutMode` in base theme (API 27) | moved to `values-v27` |
| OOM kill | Gradle daemon (1.5 GB RSS) killed by the kernel on the 2 GB host | 3 GB swap file, `-Xmx1100m`, test JVM 384 MB, `--max-workers=1` |

## Remaining lint warnings (intentional or informational)

* `GradleDependency` ×5: newer AndroidX/Compose versions exist. The pinned versions are what was built and checked.
* `DiscouragedApi`/`LockedOrientationActivity` ×4: the launcher is portrait (per spec), and the game and control editor are landscape.
* `StaticFieldLeak` ×2: the `Services` singleton holds the **Application** context only.
* `UsableSpace` ×2: the recorder checks free space with `usableSpace` before recording.
* `DataExtractionRules` ×1: `allowBackup="false"` is set deliberately, so tokens and worlds are never backed up to the cloud.

## Round 2 (after the first on-device test)

Device feedback: install worked; Play was blocked by *"Game directory not writable: getFileStore"*; opening the control editor crashed.

| Fix / feature | Verification |
|---|---|
| Free-space check no longer uses `Files.getFileStore` (throws on Android) → `File.usableSpace`; `check_android_api.py` now flags `getFileStore` | `:app:checkAndroidApi`: 831 classes, 0 incompatible references |
| Editor crash: geometry is clamped when a control is larger than the area (first 1×1 layout pass) and the editor canvas waits for a real size | unit tests + compile; device re-test pending |
| Pojav-style editor: tap = properties, drag = move, corner handle = resize, ⚙ menu (add button/joystick/touchpad, modes, undo/redo, grid snap, layouts, import/export, reset) | compiles; device re-test pending |
| In-game control editing (translucent overlay in the game process; layout reapplied live) | compiles; device re-test pending |
| Bundled Java 8/17/21/25 (arm64) + LWJGL 3.3.3, GL4ES, MobileGlues (3 ABIs), installed automatically | CI natives job; `RealBundleTest` installs the real archives; APK contents checked in CI |
| LWJGL Android natives cross-check | all 4,252 `native` methods of the official lwjgl/opengl/stb/tinyfd 3.3.3 jars resolve to exports of the built `.so` files |
| MobileGlues build | `libmobileglues.so` exports `glXGetProcAddress(ARB)`, EGL entry points, 3,060 GL functions; depends only on system libs |
| Minecraft 26.x (LWJGL 3.4.3 + SDL3) | blocked before launch with an explicit reason (`LwjglCompat`), unit-tested |
| Crash report from the previous run shown in *Logs & diagnostics* and included in shared reports | compile |

Core unit tests: 102 + the opt-in real-bundle test, all passing.

## Round 3 (1.21.x crash report, Minecraft 26.3 request)

Device feedback: 1.21.x crashed at launch (ICU `PatternSyntaxException` in the argument placeholder regex); 26.3 was blocked.

| Fix / feature | Verification |
|---|---|
| Placeholder regex escapes `}` (ICU rejects it); `tools/check_regex_icu.py` lints every regex literal; wired into `checkAndroidApi` | CI run 40192786231 green (commit `3209b48`) |
| LWJGL 3.4.3 Android build for Minecraft 26.x (`tools/natives34/build.sh`): LWJGL natives (libffi 3.8.0), FreeType 2.14.3, OpenAL Soft, shaderc v2026.2, SPIRV-Cross 1.4.341.0; bundled as component `lwjgl34-android` next to the 3.3.3 one | `verify_exports.py`: 5,032 symbols that the 3.4.3 jars bind (JNI methods + dynamically resolved functions incl. glslang/SPIRV-Tools C APIs) resolve, 0 problems (local arm64 build; repeated in CI run 40196553754, `natives34` job) |
| SDL3 implementation for 26.3+ (`cssdl3.c`, inside `libcsglfw.so`): window, shared EGL surface, GL context (real version reported), GetProcAddress identical to LWJGL's GL provider, keyboard/text/mouse/relative mode/wheel, displays, clipboard, URLs, message boxes, surfaces; the remaining 830 LWJGL-bound SDL functions fail cleanly via generated stubs | host test `tools/natives34/hosttest`: 70/70 scenario checks (Minecraft's startup sequence, input, rotation/resume surface rebuild, shutdown); LWJGL's real `org.lwjgl.sdl` bindings load the bridge (48 function classes) and round-trip an event; struct layouts identical to LWJGL 3.4.3's; every Minecraft-referenced SDL function is implemented, none is a stub |
| GLFW IME/preedit functions (used by 26.1/26.2) | compiled into all 3 ABIs; exports checked in the APK |
| LWJGL component chosen per game (3.2/3.3 → 3.3.3, 3.4.x → 3.4.3); SDL/shaderc/spvc library paths; Vulkan deliberately unavailable (Minecraft falls back to OpenGL); Zink/GL4ES reported unsupported for SDL3 versions, MobileGlues selected | 4 new unit tests; `:core:test` 106/106 |
| `libshaderc.so`/`libspirv-cross.so` linked `-Bsymbolic` so they never bind to the glslang/SPIRV-Cross copies inside MobileGlues | `DT_FLAGS SYMBOLIC` present |

### APK for Round 3

CI run 40196553754: `natives` and `natives34` passed (export verification 5,032 symbols / 0 problems, SDL3 host test 70/70, stub freshness). The `build` job did not start; GitHub reported failed account payments or a spending limit. The APK was assembled outside CI from that run's artifacts using the workflow's steps. All gates passed: `:core:test` 106/106, `RealBundleTest`, `checkRegexIcu`, `checkAndroidApi`, `lintDebug`, `assembleDebug` and the APK content checks. It is published as GitHub Release `v1.0.0-round3`: 168,820,064 bytes, SHA-256 `fa4be06de77eeba12ecaa64257977f1673c25c962a2b7044b6cb3db43c9012f8`, commit `8662fa8`.

## Round 4 (device report: 1.21.11 and 26.3 stop at launch)

Device feedback:

* 1.21.11 stopped right after `Entering net.minecraft.client.main.Main.main` with `Pointer tag for 0x70ebff82c0 was truncated`.
* After one failed 26.3 launch, every 26.3 launch was blocked with "No compatible renderer", even though MobileGlues was reported as "Ready".

| Fix | Verification |
|---|---|
| Android 11+ heap pointer tagging aborts the process when desktop native code (HotSpot/LWJGL) frees a pointer whose top-byte tag was stripped. The manifest now sets `android:allowNativeHeapPointerTagging="false"`. As a runtime fallback, `libcsbridge` calls `mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, NONE)` on arm64 before loading libjvm. | `aapt2 dump xmltree` shows the attribute in the APK manifest. `libcsbridge.so` (arm64) imports `mallopt@LIBC_O`. |
| The crash analyser classifies `Pointer tag … was truncated` as its own kind ("Android heap pointer tagging abort"). The process abort is titled "Game crashed" instead of "Game was stopped". | New `CrashAnalyzerTest` case. |
| A failure counts against the renderer only when the top diagnosis is graphics-related (or nothing matched). If every compatible renderer is remembered as failed, automatic mode retries the preferred one with an explicit reason instead of blocking. The failure memory moved to `renderer-failures-v2.txt`, so entries written under the old policy are ignored. | New `RendererSelectionTest.sdlGameWhoseOnlyRendererFailedIsRetriedNotBlocked`. |
| Logs screen: picker for the 10 most recent sessions, so the log of an earlier launch stays reachable. The shared report includes the session record (version, renderer, stage, exit code, message, crash line). | Compiles; lint passes. |

Gates (local, same steps as the CI `build` job): `:core:test` 107/107, `RealBundleTest`, `checkRegexIcu`, `checkAndroidApi`, `lintDebug`, `assembleDebug`.

## Round 5 (26.3 libjvm load failure, OptiFine, modpacks, landscape pixel UI)

Device feedback: Minecraft 26.3 stopped while loading `libjvm.so` from the Java 25 runtime, because `libc++_shared.so` was not found.

| Change | Verification |
|---|---|
| The Java 25 runtime links against `libc++_shared.so`, which its archive does not contain. The APK now ships `lib/arm64-v8a/libc++_shared.so`, taken unmodified from NDK r27d (the toolchain the runtime was built with). `AndroidRuntimeEnv` preloads it before `libjvm.so`. | `ProvisioningTest` asserts the preload order. `tools/check_bundle_needed.py` resolves every `DT_NEEDED` of all 169 shared libraries in the APK + bundle. The packaged (debug-stripped) copy exports the same 2,498 dynamic symbols as the source copy. |
| OptiFine: a headless installer (`content/OptiFine.kt`) re-implements OptiFine's own patcher (GDIFF + MD5 checks) and writes the same library/version layout as the official installer. The jar comes from optifine.net, through an in-app WebView of the official page (downloads accepted only from optifine.net, with the page's cookies) or through file import. It is never redistributed. | `OptiFineTest` (8 tests). Against the real `OptiFine_1.21.11_HD_U_J9.jar`, the output is byte-identical to the official `optifine.Patcher` for all 4,603 entries. |
| Modpacks: a `.mrpack` reader/installer (`content/Modpack.kt`) with path-traversal protection, a download-host allow-list, sha1+sha512 verification and overrides. Packs come from the Modrinth browser or a file import, and each one becomes a new isolated profile (vanilla + Fabric/Quilt + files). Forge/NeoForge packs are refused with a clear message. | `ModpackTest` (8 tests, real local HTTP server). |
| Catalog installs: `content/ContentInstall.kt` installs a file with its required dependencies (resolved transitively, each shared one only once). It skips dependencies that are already installed or were added manually, replaces the old file when another version of the same project is installed, and keeps a per-game-dir index used for the "Installed" badges. | `ContentInstallTest` (5 tests, real local HTTP server). |
| Browser: Mods / Modpacks / Resource packs / Shaders. Results are filtered automatically for the selected profile's version and loader, with infinite scroll, project icons (memory + disk cache) and Installed badges. A vanilla profile gets a "Create Fabric profile" shortcut. Content lists show icons embedded in mods and packs, and world `icon.png`. | Compiles; lint passes. |
| UI: landscape (`sensorLandscape`) with a left navigation rail. The default theme is a new Pixel theme with original 12×12 icons (`tools/ui/pixel_icons.py`), bevelled press-down buttons with haptics, pixel toggle/slider/tabs/text field/progress/dialog, and the Monocraft font (OFL-1.1). The background is an animated parallax pixel landscape coloured by time of day, throttled to ~20 fps and running only while resumed. Screens use staggered entrances, slide transitions and pop-in dialogs; reduced motion disables all animation. Settings v1→v2 migration switches existing users to the Pixel theme once. | `PixelIconDataTest` (3), the `RuntimeProfileAccountTest` migration test, and JVM-rendered landscape screenshots (Paparazzi, `./gradlew :app:recordPaparazziDebug -Ppaparazzi`, images in `app/src/test/snapshots/images/`). |

Gates (local): `:core:test` 129/129 (0 skipped; includes `RealBundleTest` and the real-jar OptiFine comparison), `:app:testDebugUnitTest` 3/3, `checkRegexIcu`, `checkAndroidApi`, `lintDebug` (0 errors), `assembleDebug`, and the APK native-dependency check.

## Round 6 (mouse pointer, virtual-mouse switch, in-game menu, 26.3 black screen)

Device feedback: the pointer sometimes showed and sometimes did not; the user wanted an on/off switch for the virtual mouse and controls, a live log and quick actions inside the game; 26.3 showed the loading screen and then turned black.

| Change | Verification |
|---|---|
| Pointer state: the touch engine assumed the game had captured the mouse at start, but the game window starts with a free pointer (title screen) and only reports changes. Both sides now start "free". The native GLFW and SDL3 windows report the current cursor mode as soon as the game window exists, and the overlay/gamepad follow it. Pointer visibility now follows one rule: never while the game captures the mouse; always with a real mouse; otherwise according to the touch mode. | `InGameControlsTest.titleScreenStartsWithAFreePointerAndDirectTouch` and `virtualMouseSwitchAndPointerVisibility`. Host SDL3 test: new check that the free pointer is reported at window creation (72/72 checks). |
| Virtual mouse on/off: Settings › Cursor, the control editor menu ("Mouse & touch"), the in-game menu, and a new bindable action "Virtual mouse" (the inventory layout now has a Mouse button). On = touchpad-style relative pointer in menus; off = direct touch, optionally showing the pointer at the finger. The change applies live. | `switchingToVirtualMouseLiveMakesMenuTouchesRelative`, `menusAlwaysOfferTheGameMenuAndTheMouseSwitch` (serialised as `"virtual_mouse"`). |
| In-game menu (pixel side panel). It opens from a tab at the top edge, from any ☰ button (now also in the inventory and chat layouts), or from the bindable action. Tabs: **Quick** (mouse on/off, hide controls, keyboard, edit controls, screenshot, chat, F3, F5, record/pause, FPS read-out, quit with save, force stop), **Tune** (pointer, camera and on-screen control opacity/size, layout switch; applied live and saved), **Log** (live tail of the game log or the MobileGlues `latest.log`, problems-only filter, follow/pause, copy), **Info** (FPS from the native swap counter, game memory, device memory, battery temperature, renderer, Java, render resolution). Polling only runs while its tab is visible. | Compiles; lint passes. Paparazzi screenshots `gameMenuQuick`, `gameMenuControls`. `newTouchSettingsHaveSafeDefaultsAndOldFilesStillLoad` covers settings saved by the game process and reloaded by the launcher. |
| 26.3 black screen: MobileGlues needs "ignore shader/program errors" for 26.3-snapshot-3+ (MobileGlues release notes; other launchers set the same). The launcher never wrote MobileGlues' `config.json`, so error filtering was off. MobileGlues now gets a generated `config.json` in its directory: automatic mode uses partial error filtering for SDL3-era versions (26.3+) and keeps the previous behaviour for older versions. New per-profile renderer options (profile editor): error filter, reported GL version, shader cache, direct state access (experimental). | `mobileGluesConfigEnablesErrorFilteringFor263`. The written config is also echoed into the game log. **Not device-verified.** |
| Pixel font: Material3 `Text` does not read the theme typography, so plain texts (e.g. the home Performance panel) used the system font. The Pixel theme now provides Monocraft as the default text style. | Home screenshot. |

Gates (local): `:core:test` 135/135, `:app:testDebugUnitTest` 3/3, `lintDebug`, `assembleDebug`, host SDL3 test 72/72, and Paparazzi screenshots (6).

## Round 7 (phone-perfect pixel UI, game menu, download visuals, clean recording)

Device feedback (screenshot of the Profiles screen on a 2400x1080 phone): the status bar was visible, the navigation rail overflowed, header buttons were huge and cards looked sparse. The user wanted every component to be right on a phone, a better in-game menu, better download animations, and recordings without the on-screen controls.

| Change | Verification |
|---|---|
| **Real-screen screenshot harness.** `RealScreensTest` renders the real `LauncherApp` (seeded profiles, accounts, jobs, manifest) at the user's phone size (2400x1080 @ 480 dpi = 800x360 dp) and on a small phone (1440x640 @ 320 dpi = 720x320 dp). The earlier mock screenshots had missed the phone problems. | 17 real-screen PNGs plus 8 kit/game-menu PNGs (`app/src/test/snapshots/images/`), all reviewed. |
| **Launcher UI**: immersive full screen (Settings › Appearance › Full screen; on by default). A 64 dp navigation rail whose items adapt to the height, with a badge for running downloads. Compact headers (36 dp actions), a smaller pixel kit (buttons 40 dp/12 sp, tabs 34 dp). Dialogs are bounded to the screen and scroll. Adaptive card grids on Profiles, Browse, Content and Downloads. A two-pane Settings screen with category icons (no Material widgets left). Logs use pixel tabs and an inset log box. Status badges wrap instead of being cut off. | Screenshots `home`, `profiles`, `newProfileDialog`, `editProfile`, `browse`, `downloads`, `contentMods`, `settings`, `logs`, `accounts`, `optifine`, plus `small*`. |
| **Downloads**: a new animation. Grass blocks drop with a spring bounce into a sunken track and a minecart carries the cargo (diamonds while running, emeralds when done, empty when failed). Unknown progress ping-pongs, finished jobs sparkle, failures tint red. Cards show files, size, failures, rate and ETA. There is a summary header with Clear and a minecart empty state. Animation runs only while a job is active and is disabled with reduced motion. | Screenshots `downloads`, `smallDownloads`. |
| **In-game menu**: the panel width adapts to the screen (62 %, at least 300 dp) and respects cut-outs. The header shows live FPS (colour-coded), game RAM and REC chips, plus a Resume button. A labelled icon tab rail. The Quick tab is grouped (Game, Controls with a layout switcher, Capture & info) with equal-width tiles. The Info tab uses stat cards. A note shows whether the current recording is clean. | Screenshots `gameMenuQuick`, `gameMenuControls`, `gameMenuLog`, `gameMenuInfo`. |
| **Control editor**: all Material widgets were replaced by the pixel kit. The toolbar has menu, undo, layout/mode and save. The editor menu has two columns (mode, add, edit / mouse & touch, layout files). The properties dialog has two columns (behaviour / look) with square colour swatches. The key picker has 4–6 keys per row by width. The layout manager uses pixel cards. Control labels use Monocraft. | Compiles; lint. Not rendered (activity-private composables). |
| **Clean recording** (Settings › Recording › Clean video, on by default). New `record_gl.c`: before each game `eglSwapBuffers` (GLFW and SDL3 paths), the presented back buffer is copied with `glBlitFramebuffer` into an EGL window surface made from the encoder's input `Surface`. The copy uses draw = encoder and read = game, is capped at the recording frame rate, uses swap interval 0, and restores the GL state. The touch controls, game menu and REC indicator are Android views composited above the game, so they are not in the video. The EGL config now prefers `EGL_RECORDABLE_ANDROID`. `RecorderService` uses the target instead of a VirtualDisplay (MediaProjection is still used for game audio). Pause or resume swaps the target, and stop detaches it before end-of-stream. If the renderer cannot do it (GLES 2 context, OSMesa, blit/swap error) or no frame arrives within 2.5 s, it switches to screen capture and tells the user that controls will be visible. | NDK `clang -Wall -Wextra` clean; native build in `assembleDebug`. The host SDL3 test confirms the hook runs once per game-window frame. **Not device-verified.** |

Gates (local): `:core:test` 135/135, `:app:testDebugUnitTest` 3/3, `lintDebug` 0 errors (17 warnings, informational), `assembleDebug`, bundle check (every DT_NEEDED resolves), host SDL3 test 73/73, Paparazzi 25 screenshots (17 real screens + 8 kit/game menu). The optional OptiFine reference-patcher test now skips itself when its fixture jars are absent.

## Not verified (requires hardware)

* Compose UI on a real device (layouts were checked with JVM-rendered screenshots only), WebView behaviour on optifine.net, and live Modrinth installs.
* The touch overlay, control editor gestures, physical keyboard/mouse/gamepad.
* The EGL surface path in `libcsglfw` (GLFW and SDL3), the in-process JVM via `libcsbridge`, and any renderer backend.
* Minecraft 26.x on a device (the SDL3 bridge is host-tested against mock EGL only).
* Recording (clean GL-blit mode and the MediaProjection fallback), audio playback capture, and gallery export.
* Android Keystore encryption, live Microsoft sign-in, and in-app Modrinth/CurseForge requests.

## Follow-up (2026-10-27): Android instance storage check and account setup copy

The attached Downloads screenshot exposed a regression in the instance-install preflight: it called `java.nio.file.Files.getFileStore`, which is present in Android's API surface but throws at runtime, leaving every new vanilla/Fabric instance marked failed with the raw `getFileStore` error. The check now uses the existing Android-safe `FsUtil.usableSpace` helper. Added unit coverage for an existing instance path and a not-yet-created child path. This change must be verified by the GitHub Actions build and, ideally, a real Android profile install; the historical build results above do not verify this follow-up.

Microsoft account sign-in remains the OAuth device-code → Xbox Live → XSTS → Minecraft Services flow and requires the publisher's own public client ID approved/configured for Minecraft services. Ely.by uses its separate documented Yggdrasil login and pinned authlib-injector at game launch. Account screen copy now explains which credentials are ever transmitted and where tokens are stored, includes Ely.by registration, and Microsoft `slow_down` responses now increase the polling interval as required. Live provider sign-in has not been tested on device.
