# Architecture

CS Launcher Pro is an original design. No code, assets or layouts were taken from PojavLauncher or any other launcher.

```
┌──────────────────────────── :app (Android, process "pro.cslauncher.app") ────────────────────────────┐
│ ui/            Compose UI: MainActivity (bottom nav + overlays), LauncherViewModel, screens/, theme/  │
│                ControlEditorActivity (landscape layout editor)                                       │
│ install/       InstallService (foreground keep-alive) + InstallManager (jobs, progress, cancel)      │
│ platform/      AndroidDevice (GLES/Vulkan/RAM/encoders), KeystoreSecretStore (AES-GCM, Keystore key) │
│ CsApp/Services Application singleton wiring :core objects to Android paths                           │
└───────────────────────────────────────────────┬──────────────────────────────────────────────────────┘
                                                │ Intent (session dir, account id, renderer fingerprint)
┌──────────────────────── :app, process "pro.cslauncher.app:game" ────────────────────────────────────┐
│ game/GameActivity   SurfaceView + TouchOverlayView, physical keyboard/mouse/gamepad, recorder toggle │
│ game/GameSession    session.json, milestones, exit → SessionRecord (read back by the launcher)       │
│ game/NativeBridge   JNI ↔ libcsglfw.so (GLFW over EGL/OSMesa + input queue)                          │
│                         ↔ libcsbridge.so (dlopen libjvm.so, JNI_CreateJavaVM, stdio → game-output.log)│
│ record/             RecorderService (MediaProjection → MediaCodec → MediaMuxer), AudioPipeline         │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
                     ▲ every decision above is made by pure-Kotlin code in :core ▼
┌──────────────────────────── :core (Kotlin/JVM, no Android dependencies) ─────────────────────────────┐
│ meta/        version manifest, version JSON (modern + legacy + inheritsFrom), rules, asset index    │
│ download/    bounded-concurrency downloader: SHA-1/256 verify, retry/backoff, Range resume, dedup    │
│ install/     Installer (client, libraries, natives, assets), Verifier, SafeZip/SafeTar, LauncherDirs │
│ runtime/     Java runtime discovery/probing/import/selection                                        │
│ launch/      LaunchCoordinator (preflight → plan), LaunchPlan + Arguments, LaunchStateMachine,       │
│              StartupMonitor (milestones from log lines), Log4jXml, DesktopGameProcess (host)         │
│ renderer/    RendererBackend registry (zink, gl4es, gl4es-angle, system-gl), capability gating      │
│ component/   SHA-256-verified native component packs (LWJGL for Android, renderer libraries)         │
│ controls/    ControlLayout model + codec (versioned JSON), TouchEngine, LayoutEditor, DynamicCursor,  │
│              Gamepad mapping, key tables, ControlLayoutStore                                         │
│ media/       RecordingPlanner (encoder-capability-gated presets)                                     │
│ content/     mods, resource/shader packs, worlds, servers.dat, NBT, Modrinth/CurseForge, loaders     │
│ diagnostics/ CrashAnalyzer (rule-based classification + redaction), RepairEngine (non-destructive)   │
│ account/     accounts store, Microsoft device-code flow → Xbox → Minecraft token, offline identities │
│ profile/ settings/ perf/ storage/ activity/ net/ platform/ util/                                     │
└──────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Key decisions

1. **Pure-JVM core.** All launcher logic lives in `:core`. It can therefore be unit-tested quickly and *executed for real* on a
   desktop host against Mojang's servers. The Android app is a thin platform layer around it.
2. **In-process game JVM in a separate Android process.** Since Android 10, apps cannot execute binaries from their own data
   directory. `libcsbridge.so` therefore loads `libjvm.so` with `dlopen` and creates the VM with `JNI_CreateJavaVM`, inside
   the `:game` process. When the game crashes or calls `System.exit`, only that process dies. The launcher process keeps
   its state, reads `session.json` and the game log, and runs the crash analyzer.
3. **Original GLFW implementation.** LWJGL 3 talks to GLFW. `libcsglfw.so` implements the GLFW API (124 functions)
   with `ANativeWindow` + EGL (GLES contexts for GL4ES/ANGLE) or OSMesa (Zink), plus a lock-protected input queue.
   Kotlin pushes touch, keyboard, mouse and gamepad events into this queue.
4. **No fake capability.** Renderer backends report `AVAILABLE` only when the device reports the needed GLES/Vulkan level
   *and* their libraries are installed and hash-verified. Otherwise they report a typed reason. The Play button state is
   computed from real data:
   * install markers;
   * the active job (download/verify/update);
   * whether the game process is alive;
   * the session state written by the game process;
   * the last failure.
5. **Launch state machine.** The steps run `IDLE → VALIDATING → PREPARING_RUNTIME → PREPARING_RENDERER → INITIALIZING_GAME → LOADING_GAME → RUNNING → EXITED`.
   * Failures record the stage they happened in.
   * Preflight collects every blocking issue together with a typed `FixAction`, e.g. install version, repair, install runtime,
     install component, choose renderer, sign in, or free storage. The UI offers exactly those fixes.
6. **Secrets.** Access tokens never appear in `plan.json`, `command-line.txt` or logs:
   * The plan carries the placeholder `@@CS_ACCESS_TOKEN@@`, which is substituted only in memory at launch.
   * Refresh tokens and API keys are stored with AES-GCM under an Android Keystore key.
   * Diagnostic reports pass through `Redactor`.
7. **Data safety.**
   * Repairs are limited to regenerable data: re-downloading files, rebuilding natives, and removing temp files.
   * Worlds, mods, configs and accounts are never deleted automatically.
   * Profile deletion keeps the game folder unless the user explicitly asks to delete it in a confirmation dialog.
   * Removed mods go to `backups/trash`.
   * World import refuses path traversal and existing names.
8. **Crash-loop protection.** If the game dies before reaching READY, the launcher records a failure for that
   renderer + device/driver/version fingerprint. In auto mode it then picks the next backend instead of repeating the crash.

## Data layout on device

| Path | Content |
|---|---|
| `getExternalFilesDir()/minecraft/` | `versions/`, `libraries/`, `assets/`, `instances/<profile>/` (isolated game dirs), `game/` (shared game dir), `sessions/<timestamp>-<profile>/` (`plan.json`, redacted `command-line.txt`, `session.json`, `game-output.log`), `recordings/`, `backups/`, `cache/`, `launcher-config/` (profiles, settings, account metadata without secrets, control layouts, activity) |
| `filesDir/runtimes/` | imported Java runtimes (internal storage: required for `dlopen`) |
| `filesDir/components/` | verified component packs (LWJGL, renderer libraries) |
| `filesDir/natives/` | per-version extracted natives |
| `filesDir/secrets.json` | Keystore-encrypted secrets (tokens, API keys) |

## UI structure
Portrait, mobile-first:
* Bottom navigation: **Home** (profile card, Play button with real states, quick actions, performance summary,
  recent activity), **Profiles** (list, create, editor), **Browse** (Modrinth/CurseForge filtered by the profile's version
  and loader), **Downloads** (install jobs and other batches with cancel/retry), and **Settings** (categories).
* Overlays: accounts, logs & diagnostics, profile editor, and content managers (mods, resource packs, shaders, worlds,
  screenshots, recordings).
* Themes: Dark Graphite and Glassmorphism, using the exact palette from the specification.
  * Glass blur is static, used only on API 31+, and only when reduced transparency/motion is off. Otherwise opaque surfaces are used.
  * No continuous animations or polling loops run in the UI. Progress comes from flows.
