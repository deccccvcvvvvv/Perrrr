# Compatibility Report & Path to a Playable Build

**Status in one sentence:** the APK builds, passes static verification and installs Minecraft correctly (proven on a
desktop host with the same core code). Playing on Android additionally requires three native payloads that are **not
bundled**, and it has **not been run on any device**.

## 1. What is verified, and how

| Area | Evidence level | Notes |
|---|---|---|
| Version manifest, metadata, rules, libraries, assets, checksums, downloads, repair | **Executed against Mojang servers** (host E2E, 1.18.2) + unit tests | Same `:core` code as on Android |
| Launch arguments, classpath, token handling, state machine, milestone detection, crash analysis | **Executed** (host E2E launched to title screen) + unit tests | Android plan variant (LWJGL substitution, GLFW bridge) unit-tested only |
| Renderer selection logic | Unit-tested | Real backends never executed on Android |
| Touch engine, control layouts, editor model, cursor logic, recorder planning | Unit-tested (pure Kotlin) | Android views/services around them compiled only |
| Content managers (mods, packs, shaders, worlds, servers, NBT) | Unit-tested | |
| Android app (UI, services, JNI, native libs) | **Compiled, linted, API-checked, packaged** | Never installed or run |

## 2. Android requirements

| | |
|---|---|
| Android version | 8.0+ (API 26). Target API 34 |
| ABIs in APK | arm64-v8a (primary), armeabi-v7a, x86_64 |
| GPU | OpenGL ES 2.0+ for GL4ES. Vulkan 1.1+ for Zink / ANGLE (as detected at runtime: `AndroidDevice` reads the GLES version from `ConfigurationInfo` and Vulkan from `PackageManager` features) |
| Memory | The memory advisor caps the game heap well below total RAM; low-RAM devices get a smaller cap |
| Recording | MediaProjection: API 26+. Game-audio capture and gallery export: API 29+. Resolution/fps are limited to what the device encoders report |

## 3. Minecraft versions

| Versions | LWJGL | Renderer path on Android | Status |
|---|---|---|---|
| 1.17 – current | 3.x | Zink (needs GL 3.2 core) | Supported by the launch logic. Needs runtime + LWJGL + Mesa/Zink packs |
| 1.13 – 1.16.5 | 3.x | GL4ES (GL 2.1), or GL4ES on ANGLE | Supported by the launch logic. Needs runtime + LWJGL + GL4ES pack |
| ≤ 1.12.2 | 2.x | — | **Not supported** on Android (blocked with an explicit message; no LWJGL 2 compatibility layer) |
| Fabric / Quilt | — | as vanilla | Installable via official loader metadata |
| Forge / NeoForge | — | — | **Not supported** (their installers must run as a Java program; the UI shows them disabled) |

The Java major version is taken from each version's metadata (`javaVersion.majorVersion`). The runtime selector refuses
or warns on mismatches; it does not guess.

## 4. Renderer backends

| ID | Provides | Needs | Licence | Default use |
|---|---|---|---|---|
| `zink` | OpenGL 4.x via Mesa on Vulkan (OSMesa context blitted to the window) | Vulkan 1.1+, `libOSMesa.so` pack | MIT | auto for 1.17+ |
| `gl4es` | OpenGL 2.1 → GLES 2/3 | GLES 2.0+, `libgl4es.so` pack | MIT | auto for 1.13–1.16.5 |
| `gl4es-angle` | GL4ES on ANGLE (GLES on Vulkan) | Vulkan, `libgl4es.so` + `libEGL_angle.so` + `libGLESv2_angle.so` | MIT + BSD-3 | manual / fallback, experimental |
| `system-gl` | desktop OpenGL | — | — | desktop host only (reports *Not applicable* on Android) |

A backend is shown as available only when the device reports the capability **and** its libraries are installed and
SHA-256-verified. Otherwise the UI shows the exact reason, e.g. *Device unsupported*, *Missing component*, or *Game unsupported*.
An explicit choice is never silently swapped. In auto mode, a backend that crashed is skipped for the same device/driver/version
fingerprint, so a crash cannot repeat in a loop.

## 5. Blockers to a playable Android build

### B1 — Android Java runtime (not bundled)
The game JVM runs **in-process**: `libcsbridge.so` `dlopen`s `libjvm.so` and calls `JNI_CreateJavaVM`. Android forbids
executing binaries from app storage (W^X, targetSdk ≥ 29) but allows `dlopen` of libraries, which is why this approach was chosen.
A desktop JDK cannot work, because it is built against glibc. What is needed:
* An OpenJDK build for **Android/bionic** (arm64-v8a, plus armeabi-v7a/x86_64 if desired). It must use the `release` file layout
  with `lib/server/libjvm.so` (9+) or `lib/<arch>/server/libjvm.so` (8).
* Java 17 for 1.18–1.20.4, Java 21 for 1.20.5+, and later majors as declared by newer versions.

Why it is not bundled: no such build was produced on this host. Building OpenJDK for bionic is a multi-hour cross-compile
outside this project's scope, and redistributing one requires shipping its GPLv2+CE source offer.
**Path:** build or obtain a bionic OpenJDK. Then import it in the app via *Settings → Runtimes → Import* (`.zip`, `.tar`
or `.tar.gz`, optional SHA-256). The importer extracts into staging, probes the `release` file and architecture, and only
then activates the runtime. Alternatively, ship it as a bundled asset and register it with source `bundled`.

### B2 — LWJGL 3 natives for Android (not bundled)
Mojang's LWJGL natives are desktop (glibc) binaries. On Android the planner removes every `org.lwjgl` library from the
classpath and substitutes the jars and natives of an **LWJGL component pack**. It sets
`-Dorg.lwjgl.librarypath=<pack lib dir>` and `-Dorg.lwjgl.glfw.libname=<APK>/libcsglfw.so`, so GLFW is provided by this
project's own implementation.
**Path:** build LWJGL 3.3.x for Android (`liblwjgl.so`, `liblwjgl_opengl.so`, `liblwjgl_stb.so`, `liblwjgl_tinyfd.so`, and
OpenAL Soft `libopenal.so`) plus matching jars. Package them as a component pack (format below) with `provides: ["lwjgl:3.3.3"]`.

### B3 — Renderer libraries (not bundled)
GL4ES (`libgl4es.so`), Mesa with OSMesa + Zink (`libOSMesa.so`), and optionally ANGLE. Each one is packaged as a component pack
with `kind: "renderer"` and `provides: ["lib:<name>.so"]`.

### B4 — Device validation (not performed)
No emulator is possible without KVM, and no physical device was attached. On-device validation is needed for:
* EGL surface creation, the frame loop, and input latency.
* The touch overlay, the editor, and gamepads.
* Recording with audio.
* Keystore.
* A live Microsoft sign-in.

### B5 — Microsoft sign-in client ID (user/publisher action)
Microsoft authentication uses the OAuth device-code flow and needs an Azure application (client) ID with Xbox Live permissions.
No ID is embedded. Enter it under *Settings → Accounts*. Offline profiles stay available and are clearly labelled as offline.

### B6 — CurseForge API key (optional)
CurseForge search requires a key from the CurseForge console. It is entered by the user and stored encrypted, and nothing is
embedded. Modrinth needs no key.

## 6. Component pack format (`cs-component.json`, formatVersion 1)

```json
{
  "formatVersion": 1,
  "id": "lwjgl-3.3.3-arm64",
  "name": "LWJGL 3.3.3 for Android",
  "version": "3.3.3",
  "kind": "lwjgl",
  "abi": "arm64-v8a",
  "license": "BSD-3-Clause (LWJGL), LGPL-2.0 (OpenAL Soft)",
  "source": "https://github.com/LWJGL/lwjgl3 @ <commit>, built with NDK r26d",
  "provides": ["lwjgl:3.3.3"],
  "classpathJars": ["jars/lwjgl.jar", "jars/lwjgl-glfw.jar", "jars/lwjgl-opengl.jar", "jars/lwjgl-openal.jar", "jars/lwjgl-stb.jar", "jars/lwjgl-tinyfd.jar", "jars/lwjgl-jemalloc.jar"],
  "libraryDir": "lib",
  "files": [ { "path": "lib/liblwjgl.so", "sha256": "<hex>", "size": 123456 } ]
}
```

The pack is a zip containing this manifest and every listed file. Import is done through *Settings → Runtimes & components*.
The pack is rejected if the ABI does not match, if any file is missing, unlisted or fails its hash, or if any path escapes the pack
directory. Licences are shown in the UI.

## 7. Known limitations (by design)
* There is no LWJGL 2 support and no Forge/NeoForge support (see §3).
* Offline profiles cannot join online-mode servers. The UI never presents them as a multiplayer solution.
* There is no "RAM cleaner" or "overclock" feature. Performance presets only change real settings: render scale, renderer
  options, memory within safe limits, and frame-rate limit.
