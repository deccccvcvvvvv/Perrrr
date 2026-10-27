# Integrated accounts / instances / settings update

## Source changes

- Local and existing Microsoft device-code accounts preserved; Ely.by Yggdrasil authentication and two-factor code entry added. No passwords are persisted. Ely sessions use the same Keystore secret store as Microsoft, are refreshed before launch, and use pinned SHA-256-verified authlib-injector 1.2.8 to route in-game authentication to Ely.by. The account list can render Microsoft-provided skin faces.
- Microsoft uses the external system browser for device-code approval, not an embedded WebView. An approved Azure public client ID remains required. No borrowed client credentials are embedded.
- Instance creation now starts a foreground background installation: base game, loader metadata/libraries, verified installation markers and compatible Java provisioning. Instance jobs share a serial installation queue; file downloads inside each job retain bounded parallelism. Instance installation state persists in profile JSON, so interrupted installations remain incomplete rather than ready; users retry explicitly. Partial download files are reused by the downloader.
- Creation offers full manifest search, snapshots, supported loader versions, icons, Java selection, RAM slider/presets, renderer, shared/isolated game directory and JVM arguments. IDs remain independent UUIDs. No Forge/NeoForge installer processors have been added; those loaders remain explicitly unsupported.
- Home lists compact instances with actual job progress, selection and editing. Profiles include install/retry and repair. Launching incomplete instances is blocked. Active installations block profile editing/deletion to avoid losing changes or resurrecting removed instances.
- Instance editing adds actual fullscreen/resolution settings and directory-change warning. Changing folders preserves the old files; it does not migrate them.
- General, Memory, Rendering and Game Launch settings sections extend the existing categorized settings. RAM, Java, renderer and JVM defaults are copied only at creation. Download attempts/concurrency configure the actual download engine. Rendering displays device/component status against an explicitly identified reference Minecraft version; actual-version checks remain in launch preflight.
- New serialized properties have defaults; existing profiles/accounts/settings do not need recreation. No worlds/mods are removed by these changes.

## Boundaries / not implemented in this update

- No Android SAF tree-to-native game-directory adapter: shared/isolated app-specific directories remain the safe options. Arbitrary document-provider folders cannot be passed to the in-process JVM as filesystem paths.
- No new Forge, NeoForge or LWJGL 2 compatibility implementation.
- No UI language packs, light theme, UI scaling, startup-screen preference, custom profile-image picker, automatic transfer of existing game files or global resolution/fullscreen defaults.
- No durable OS-scheduled download worker: foreground jobs survive navigation, not process death. Persistent instance status and partial files support explicit retry after restart. Queue progress/history itself remains process-local.
- Ely.by skins and device sign-in/gameplay have not been verified on a physical Android device. Microsoft authentication still requires publisher approval/client configuration.

## Build method

Use the existing `:app:assembleDebug` task with JDK 17 / SDK 34 / NDK r26d / CMake 3.22.1. For this update, bundled runtime/component payloads are reused from the repository's Round 7 release APK (asset 675703335); native launcher C sources are built by the normal Gradle CMake task. No separate test campaign is run.
