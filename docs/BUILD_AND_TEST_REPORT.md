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

## Not verified (requires hardware)

* Installing or launching the APK, Compose UI rendering, and theme appearance.
* The touch overlay, control editor gestures, physical keyboard/mouse/gamepad.
* The EGL surface path in `libcsglfw` (GLFW and SDL3), the in-process JVM via `libcsbridge`, and any renderer backend.
* Minecraft 26.x on a device (the SDL3 bridge is host-tested against mock EGL only).
* MediaProjection recording, audio playback capture, and gallery export.
* Android Keystore encryption, live Microsoft sign-in, and in-app Modrinth/CurseForge requests.
