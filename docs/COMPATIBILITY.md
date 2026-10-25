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

| Versions | Java | LWJGL | Renderer path on Android (auto) | Status |
|---|---|---|---|---|
| 26.3 and newer (SDL3) | 25 | **3.4.3 + SDL3** | MobileGlues only (OpenGL 3.3+ core over GLES 3.x; Zink/GL4ES are reported as unsupported with the reason) | **Launch path implemented, arm64-v8a only, not yet device-verified.** The launcher's own SDL3 implementation (`cssdl3.c` in `libcsglfw.so`) provides the window, OpenGL context, input, clipboard and display APIs; the LWJGL 3.4.3 component adds shaderc + SPIRV-Cross for the 26.3 shader pipeline. OpenGL only: Vulkan is deliberately unavailable, and Minecraft falls back to OpenGL by itself. On armeabi-v7a/x86_64 these versions are blocked with an explicit message (no LWJGL 3.4 build for those ABIs yet) |
| 26.1 – 26.2 (GLFW) | 25 | 3.4.1 | MobileGlues, then Zink | Launch path implemented (arm64-v8a): LWJGL 3.4.3 component + the GLFW bridge (now including GLFW's IME/preedit functions these versions call). Not yet device-verified |
| 1.17 – 1.21.11 | 16/17/21 | 3.2.x – 3.3.3 | MobileGlues (GL 4.x over GLES 3.x), then Zink, then GL4ES | Launch path implemented. Bundled runtime + LWJGL 3.3.3 + MobileGlues |
| 1.13 – 1.16.5 | 8 | 3.x | GL4ES (GL 2.1 over GLES 2/3), then MobileGlues | Launch path implemented. Bundled runtime + LWJGL + GL4ES |
| ≤ 1.12.2 | 8 | 2.x | — | **Not supported** (blocked with an explicit message; no LWJGL 2 compatibility layer) |
| Fabric / Quilt | as vanilla | — | as vanilla | Installed inside the launcher from the official loader metadata (no installer jar) |
| Forge / NeoForge | — | — | — | **Not supported yet** (their installers run binary-patching "processors"; the UI shows them disabled) |

The Java major version is taken from each version's metadata (`javaVersion.majorVersion`). The provisioner installs the
smallest bundled/verified runtime whose major is ≥ the requirement (8, 17, 21 or 25).

## 4. Renderer backends

| ID | Provides | Needs | Licence | Default use |
|---|---|---|---|---|
| `mobileglues` | OpenGL 4.x → GLES 3.x (own EGL wrapper, `EGL_DESKTOP` context) | GLES 3.0+ (3.2 best), bundled `libmobileglues.so` | LGPL-2.1 | auto first choice for 1.17+ |
| `zink` | OpenGL 4.x via Mesa on Vulkan (OSMesa context blitted to the window) | Vulkan 1.1+, `libOSMesa.so` pack (not bundled) | MIT | auto fallback for 1.17+ |
| `gl4es` | OpenGL 2.1 → GLES 2/3 | GLES 2.0+, bundled `libgl4es.so` | MIT | auto first choice for 1.13–1.16.5 |
| `gl4es-angle` | GL4ES on ANGLE (GLES on Vulkan) | Vulkan, `libgl4es.so` + ANGLE libraries (not bundled) | MIT + BSD-3 | manual / fallback, experimental |
| `system-gl` | desktop OpenGL | — | — | desktop host only (reports *Not applicable* on Android) |

A backend is shown as available only when the device reports the capability **and** its libraries are installed and
SHA-256-verified. Otherwise the UI shows the exact reason. An explicit choice is never silently swapped. In auto mode, a backend
that crashed is skipped for the same device/driver/version fingerprint.

## 5. Bundled native components and remaining blockers

Everything needed to start a supported version ships **inside the APK** and is installed automatically on first start /
before the first launch (`AutoProvisioner`). No manual import is needed:

| Component | Source | Built by | ABIs |
|---|---|---|---|
| Java 8 / 17 / 21 / 25 for Android | AngelAuraMC `angelauramc-openjdk-build` releases, pinned by SHA-256 | prebuilt (GPLv2+CE) | bundled for arm64-v8a; other ABIs download the same verified archives on demand |
| LWJGL 3.3.3 (jars incl. glfw/jemalloc; natives for core, opengl, stb, tinyfd, freetype, openal; GLFW is the launcher's own `libcsglfw.so`, the system allocator replaces jemalloc) | LWJGL 3.3.3 sources, libffi 3.4.6, FreeType 2.13.2, OpenAL Soft 1.23.1 | `tools/natives/build_natives.sh` in CI (NDK 26, API 26) | arm64-v8a, armeabi-v7a, x86_64 |
| LWJGL 3.4.3 for Minecraft 26.x (natives for core, opengl, stb, tinyfd, freetype, openal, shaderc, spirv-cross; jars incl. sdl, glfw, shaderc, spvc, vulkan, vma; SDL3 and GLFW are the launcher's own `libcsglfw.so`) | LWJGL 3.4.3 sources, libffi 3.8.0, FreeType 2.14.3, OpenAL Soft 1.23.1, shaderc v2026.2, SPIRV-Cross 1.4.341.0 | `tools/natives34/build.sh` in CI; every symbol the 3.4.3 jars bind is checked (`verify_exports.py`) | arm64-v8a |
| GL4ES | ptitSeb/gl4es (pinned commit) | same | same |
| MobileGlues | MobileGL-Dev/MobileGlues (pinned commit) | same | same |

The bundle lives in `assets/bundled/<abi>/index.json` plus SHA-256-verified archives. The runtime is extracted with the
xz-capable safe tar extractor; components are installed as component packs (format below) and upgraded when the bundled
version changes. The game JVM runs in-process (`dlopen(libjvm.so)`), with the linker path, `JAVA_HOME`, DNS
(`ext.net.resolvPath`), time zone and LWJGL library names set up by `AndroidRuntimeEnv`.

The CI workflow builds these natives from source on every change to `tools/natives/` and refuses to produce an APK if
any bundle file is missing.

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
