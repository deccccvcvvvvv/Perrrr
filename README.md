# CS Launcher Pro

An original Android launcher for **Minecraft: Java Edition**. It has a Kotlin/Jetpack Compose UI, a pure-Kotlin launcher core,
and a small C native layer (its own GLFW implementation plus an in-process JVM bridge).

## Status (honest)

| | |
|---|---|
| Launcher core | ✅ 93/93 unit tests pass. ✅ A real install + repair + launch of Minecraft 1.18.2 to the title screen succeeded on a desktop host using the same core code |
| Android APK | ✅ Builds (debug, signed with the debug key, and an R8 release build), 0 lint errors, 0 Android API-level violations, native libraries for arm64-v8a / armeabi-v7a / x86_64 |
| On a device | ⚠️ **Never installed or run**: no emulator or device was available on the build host |
| Playing on Android | ⛔ Needs an Android-built Java runtime, an LWJGL 3 Android pack and a renderer pack (GL4ES or Mesa Zink). None are bundled. See [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md) §5 |

The built APK is in `release-artifacts/CSLauncherPro-1.0.0-debug.apk` (SHA-256 in the `.sha256` file next to it).

## Features (implemented in code)
* **Installation:**
  * Official version manifest, client JAR, library rules, assets, and safe native extraction (traversal/symlink/zip-bomb protection).
  * SHA-1/SHA-256 verification, bounded-concurrency downloads with retry and HTTP Range resume, targeted repair, and installation reports.
  * Fabric/Quilt via their official metadata.
* **Launch:**
  * A state machine with the failure stage recorded.
  * A preflight that lists every blocking issue with a one-tap fix.
  * The Java runtime is selected from the version's metadata.
  * Tokens never appear in files or logs.
  * Crash-loop protection.
* **Renderers:** GL4ES, Mesa Zink, GL4ES-on-ANGLE.
  * Real GLES/Vulkan detection.
  * Automatic choice with fallback.
  * Unsupported backends say why instead of pretending.
* **Controls:**
  * An original multi-touch engine: joystick, hold/toggle/repeat buttons, camera, hotbar swipe, and modes/layers.
  * A full-screen editor with undo/redo, grid snap, and import/export of versioned JSON.
  * Keyboard, mouse and gamepad support.
  * Desktop/touchpad/immersive/smart cursor modes.
* **Recorder:** MediaProjection + MediaCodec + MediaMuxer in a foreground service.
  * Presets limited by the device encoders.
  * Pause/resume.
  * Game audio on Android 10+.
  * Saves to the gallery.
* **Content:**
  * Mods (Modrinth, CurseForge with your own key): compatibility checks and toggling; removed mods go to trash.
  * Resource packs with ordering; shader packs.
  * Worlds with backup, export and safe import.
  * Screenshots and recordings.
  * A storage manager that only clears regenerable data.
* **Diagnostics:**
  * Rule-based crash classification on real logs.
  * Redacted shareable reports.
  * Safe repairs only; user data is never deleted.
* **Accounts:** Microsoft device-code sign-in (bring your own Azure client ID), Keystore-encrypted tokens, and clearly labelled offline profiles.
* **UI:**
  * Portrait, with Home / Profiles / Browse / Downloads / Settings.
  * Dark Graphite and Glassmorphism themes.
  * Reduced motion/transparency respected.

## Build

```bash
tools/setup_toolchain.sh                      # JDK 17, SDK 34, NDK r26d, CMake, Gradle → /opt/cs-toolchain
export JAVA_HOME=/opt/cs-toolchain/jdk17 PATH=$JAVA_HOME/bin:$PATH ANDROID_HOME=/opt/cs-toolchain/sdk
./gradlew :core:test :app:assembleDebug :app:checkAndroidApi
```
Details, memory tuning for small machines, and native build notes: [`docs/BUILD.md`](docs/BUILD.md).

## Documentation
* [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): modules, processes, key decisions, data layout
* [`docs/BUILD_AND_TEST_REPORT.md`](docs/BUILD_AND_TEST_REPORT.md): exact results of every build and test run
* [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md): supported Android/Minecraft versions, renderers, blockers and the path to a playable build, component pack format
* [`docs/DEPENDENCIES_AND_LICENSES.md`](docs/DEPENDENCIES_AND_LICENSES.md)
* [`docs/PLAN.md`](docs/PLAN.md): initial inspection and plan
* `docs/evidence/`: host end-to-end logs and title-screen screenshot

## Repository layout
```
core/     pure-Kotlin launcher core + tests (+ host E2E harness in src/test/.../e2e)
app/      Android app: Compose UI, services, game activity, JNI (src/main/cpp)
tools/    setup_toolchain.sh, check_android_api.py
docs/     documentation and evidence
release-artifacts/  built debug APK + checksum
```

Not affiliated with Mojang or Microsoft. Minecraft is a trademark of Mojang Synergies AB.
