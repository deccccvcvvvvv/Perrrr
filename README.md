# CS Launcher Pro

An original Android launcher for **Minecraft: Java Edition**. It has a Kotlin/Jetpack Compose UI, a pure-Kotlin launcher core,
and a small C native layer (its own GLFW implementation plus an in-process JVM bridge).

## Status (honest)

| | |
|---|---|
| Launcher core | ✅ All `:core:test` unit/integration tests pass (see `docs/BUILD_AND_TEST_REPORT.md`). Real install + launch to the title screen verified on a desktop host with the same core code |
| Android APK | ✅ Builds with 0 lint errors and 0 Android API-level violations. Published as GitHub Release assets (too large for the repository) |
| Bundled | Java 8/17/21/25 runtimes, LWJGL 3.3 + 3.4 Android natives, GL4ES, MobileGlues, `libc++_shared` for Java 25 — no manual imports |
| On a device | ✅ 1.21.11 reaches the game (Round 4 device report). Minecraft 26.3 (Java 25/SDL3) failed to load `libjvm.so` before Round 5; the fix (bundled `libc++_shared.so`) is not yet device-confirmed |

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
  * Virtual mouse on/off switch (settings, control editor, in-game menu, bindable action), applied live.
  * In-game menu: quick actions, live control tuning, live game/renderer log, FPS/memory info, optional FPS read-out.
* **Recorder:** MediaCodec + MediaMuxer in a foreground service. In "clean video" mode, frames are copied straight from the game renderer into the encoder, so the on-screen controls are not recorded. It falls back to MediaProjection screen capture when the renderer cannot do this. Game audio uses playback capture.
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
* **Accounts:** Microsoft legacy launcher-compatible sign-in with no Client ID setup, Ely.by accounts with optional 2FA, Keystore-encrypted sessions, and clearly labelled offline profiles. The bundled Microsoft flow is unofficial and may be blocked or changed by Microsoft.
* **OptiFine:** opens the official optifine.net page in-app and captures the jar you download (or import a jar). It is patched locally by a headless re-implementation of OptiFine's own installer; the output is byte-identical to the official installer for 1.21.11 HD U J9. OptiFine is never redistributed.
* **Modpacks:** browse and install Modrinth modpacks (`.mrpack`), or import a `.mrpack` file. Each pack becomes its own profile with Minecraft, its Fabric/Quilt loader and every file installed and hash-checked.
* **Mod browser:** Mods / Modpacks / Resource packs / Shaders. Results are filtered automatically for the selected profile's version and loader. Shows project icons and an "Installed" badge. Required dependencies are installed automatically, skipping ones already present (including manually added copies). Installing another version replaces the old file.
* **UI:**
  * Landscape, built for touch: a left navigation rail with Home / Profiles / Browse / Downloads / Settings.
  * Default **Pixel** theme in the spirit of the Pocket Edition menus. Original 12×12 pixel icons, bevelled 3D buttons that sink when pressed, with haptics. Monocraft pixel font (OFL).
  * Animated parallax pixel landscape coloured by time of day (stars at night). Throttled to ~20 fps and paused when the app is not visible.
  * Staggered list entrances, sliding screens and pop-in dialogs.
  * Reduced motion turns every animation off. Dark Graphite and Glassmorphism remain available as themes.
  * Mod, resource-pack and world icons are read from the files themselves.

## Build

**APK builds are performed through the repository's GitHub Actions workflow only.** Do not assemble APKs locally. Push to `main` or run the `Android CI` workflow manually; successful `main` runs publish the APK and SHA-256 checksum as a GitHub Release. See [`docs/CI_BUILD.md`](docs/CI_BUILD.md) for the exact steps and release locations. Local setup and native build notes are in [`docs/BUILD.md`](docs/BUILD.md), but they are not the APK release process.

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
