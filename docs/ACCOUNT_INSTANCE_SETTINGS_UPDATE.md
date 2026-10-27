# Integrated accounts / instances / settings update

## Source changes

- Local and existing Microsoft device-code accounts preserved; Ely.by Yggdrasil authentication and two-factor code entry added. No passwords are persisted. Ely sessions use the same Keystore secret store as Microsoft, are refreshed before launch, and use pinned SHA-256-verified authlib-injector 1.2.8 to route in-game authentication to Ely.by. The account list can render Microsoft-provided skin faces.
- Microsoft uses the external system browser for device-code approval, not an embedded WebView. An approved Azure public client ID remains required. No borrowed client credentials are embedded.
- Instance creation now starts a foreground background installation: base game, loader metadata/libraries, verified installation markers and compatible Java provisioning. Instance jobs share a serial installation queue; file downloads inside each job retain bounded parallelism. Instance installation state persists in profile JSON, so interrupted installations remain incomplete rather than ready; users retry explicitly. Partial download files are reused by the downloader.
- Creation offers full manifest search, snapshots, supported loader versions, icons, Java selection, RAM slider/presets, renderer, shared/isolated game directory and JVM arguments. IDs remain independent UUIDs. No Forge/NeoForge installer processors have been added; those loaders remain explicitly unsupported.
- Home lists compact instances with actual job progress, selection and editing. Profiles include install/retry and repair. Launching incomplete instances is blocked. Active installations block profile editing/deletion to avoid losing changes or resurrecting removed instances.
- Instance editing adds actual fullscreen/resolution settings and directory-change warning. Changing folders preserves the old files; it does not migrate them.
- Startup-screen preference and default fullscreen/resolution are persisted and applied. General, Memory, Rendering and Game Launch settings sections extend the existing categorized settings. RAM, Java, renderer and JVM defaults are copied only at creation. Download attempts/concurrency configure the actual download engine. Rendering displays device/component status against an explicitly identified reference Minecraft version; actual-version checks remain in launch preflight.
- New serialized properties have defaults; existing profiles/accounts/settings do not need recreation. No worlds/mods are removed by these changes.

## Boundaries / not implemented in this update

- No Android SAF tree-to-native game-directory adapter: shared/isolated app-specific directories remain the safe options. Arbitrary document-provider folders cannot be passed to the in-process JVM as filesystem paths.
- No new Forge, NeoForge or LWJGL 2 compatibility implementation.
- No UI language packs, light theme, UI scaling, custom profile-image picker or automatic transfer of existing game files.
- No durable OS-scheduled download worker: foreground jobs survive navigation, not process death. Persistent instance status and partial files support explicit retry after restart. Queue progress/history itself remains process-local.
- Ely.by skins and device sign-in/gameplay have not been verified on a physical Android device. Microsoft authentication still requires publisher approval/client configuration.

## Follow-up changes in this pass

- Microsoft device-code approval now opens in a Chrome Custom Tab (a real, isolated browser tab) with an external-browser fallback. Microsoft's identity platform forbids embedded WebViews for OAuth, so no WebView is used; the reason is stated in the Accounts screen.
- Ely.by's refresh flow tolerates responses without `selectedProfile` by keeping the stored identity, marks accounts needing re-login with an actionable message, and points authlib-injector at Ely's explicit `/api/authlib-injector` metadata endpoint. The pinned 1.2.8 agent SHA-256 was re-verified against the upstream artifact. Offline unit tests cover login, 2FA, refresh fallback, error messages and secret handling.
- Instance creation validates duplicate display names (case-insensitive) both in the dialog and in the view model, and shows Forge/NeoForge as visibly disabled with the actual reason rather than hiding them or pretending they work. Loader-specific icons were added.
- Background install notifications now carry the instance name, Minecraft version, loader, real file progress (indeterminate when unknown), queued/running status and the actual failure message; tapping opens Downloads. Failed instance jobs can be retried directly from Downloads, resuming from kept partial files.
- `docs/CI_BUILD.md` documents the GitHub Actions-only build. No APK is ever assembled locally.

## Build method (GitHub Actions only)

**APK builds are performed exclusively by `.github/workflows/android.yml` on GitHub Actions.** Never run `./gradlew assembleDebug`, `assembleRelease` or `build` on a local machine. The workflow checks out the repository, installs JDK 17 / SDK 34 / NDK r26d / CMake 3.22.1, restores the SHA-256-verified bundled runtimes and component packs from the pinned `v1.0.4-round7` release asset, runs `:core:test`, `:app:testDebugUnitTest`, `checkAndroidApi` and `lintDebug`, then builds the APK with `:app:assembleDebug` and uploads `CSLauncherPro-debug-apk` as a workflow artifact. The launcher's Kotlin and C sources are always compiled fresh; the optional `rebuild_natives` dispatch input regenerates the third-party payloads from source when needed.
