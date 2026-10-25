# APK

The APK is ~169 MB because Java 8/17/21/25, all renderer components and both LWJGL builds (3.3.3 and 3.4.3) are bundled.

## Latest build: Round 4 (pointer-tag launch abort, renderer lock-out)

* GitHub Release `v1.0.1-round4` (pre-release); the repo is private, so log in to download:
  https://github.com/JoooaoooDVD/Pro/releases/tag/v1.0.1-round4
* File: `CSLauncherPro-1.0.1-round4-debug.apk`, 168,825,536 bytes
* SHA-256: `9be2031b5f4634ae262a90636e230ca18bb2d90ab4c2896433b3d5868b7b9b11`
* Source: commit `e860680`
* Signed with the shared debug key (`app/debug.keystore`), so it installs as an update over `v1.0.0-round3`.
* Built outside CI, because GitHub Actions is still unable to start jobs (account billing / spending limit; run 40203192844). It was built with the same steps as the workflow's `build` job, using the natives artifacts of CI run 40196553754. Gates passed: `:core:test` 107/107, `RealBundleTest`, `checkRegexIcu`, `checkAndroidApi`, `lintDebug` and `assembleDebug`.

## Round 3 build

* `v1.0.0-round3`, commit `8662fa8`, 168,820,064 bytes, SHA-256 `fa4be06de77eeba12ecaa64257977f1673c25c962a2b7044b6cb3db43c9012f8`. It aborts at launch with "Pointer tag … was truncated" on Android 11+ devices, so use Round 4.

### How it was built

In CI run 40196553754, the `natives` (3 ABIs) and `natives34` (arm64 LWJGL 3.4.3 build, export verification, SDL3 host test, stub-freshness check) jobs passed. The `build` job could not start because of a billing or spending-limit problem on the GitHub account. The APK was then assembled outside CI from those exact CI artifacts, using the same steps as the workflow's `build` job:

* `build.sh package`
* `build_natives.sh runtimes`
* `:core:test` 106/106
* `RealBundleTest` (installs jre8/17/21/25 and checks lwjgl34-android)
* `checkRegexIcu` (0 problems)
* `checkAndroidApi`
* `lintDebug`
* `assembleDebug`
* APK content checks: `lwjgl34-android.zip`, `jre25.tar.xz` and `libcsglfw.so` with 1052 SDL exports on each ABI

Once Actions billing works again, re-run the workflow. It publishes the same APK as the artifact `CSLauncherPro-debug-apk`.

## Previous builds

* Run 40190716929 (commit 8c17944): 150,548,655 bytes, SHA-256 `1723736d9ce19023b82c73d5fcb12341505cdc9cf5cfdcd53cef459e16475fac`. This build crashes on 1.21.x (ICU regex); do not use it.
