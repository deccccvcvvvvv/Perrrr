# APK

The APK is ~169 MB because Java 8/17/21/25, all renderer components and both LWJGL builds (3.3.3 and 3.4.3) are bundled.

## Latest build: Round 6 (pointer fix, virtual-mouse switch, in-game menu, 26.3 MobileGlues config)

* GitHub Release `v1.0.3-round6` (pre-release, id 423532229, asset 675583105); the repo is private, so log in to download:
  https://github.com/JoooaoooDVD/Pro/releases/tag/v1.0.3-round6
* File: `CSLauncherPro-1.0.3-round6-debug.apk`, 170,011,121 bytes
* SHA-256: `3737e8174a2d5bf6b1c478b4bb5787887a139fece6a1c2b1d3317cdc87a6c115` (checked again after downloading it back through the API)
* Source: commit `f583549`
* Signed with the shared debug key (cert SHA-256 `0ce5f955…068c66c`), so it installs as an update over earlier rounds.
* Built outside CI (GitHub Actions still blocked by billing). Gates passed:
  * `:core:test` 135/135
  * `:app:testDebugUnitTest` 3/3
  * `lintDebug` (0 errors)
  * `assembleDebug`
  * SDL3 host test 72/72
  * `tools/check_bundle_needed.py`: every DT_NEEDED resolves for all 169 shared libraries
  * Paparazzi screenshots (6, including the in-game menu)
* Not device-verified yet: 26.3 with the MobileGlues error-filter config, and the in-game menu on a phone.

## Round 5 build (26.3 libc++ fix, OptiFine, modpacks, landscape pixel UI)

* GitHub Release `v1.0.2-round5` (pre-release, id 423515208); the repo is private, so log in to download:
  https://github.com/JoooaoooDVD/Pro/releases/tag/v1.0.2-round5
* File: `CSLauncherPro-1.0.2-round5-debug.apk`, 169,675,705 bytes
* SHA-256: `395d30d67155d2092d4d653a753a412e8df67c52dc8cdf47cab2c526deddc58b` (checked again after downloading it back through the API)
* Source: commit `d8037f7`
* Signed with the shared debug key (cert SHA-256 `0ce5f955…068c66c`), so it installs as an update over Round 3 and Round 4.
* Built outside CI, because GitHub Actions still cannot start jobs (billing). Gates passed:
  * `:core:test` 129/129
  * `:app:testDebugUnitTest` 3/3
  * `checkAndroidApi`
  * `lintDebug` (0 errors)
  * `assembleDebug`
  * `tools/check_bundle_needed.py`: every DT_NEEDED resolves for all 169 shared libraries
  * Paparazzi landscape screenshots
* Not device-verified yet: the 26.3 launch with the libc++ fix, and OptiFine in-game.

## Round 4 build (pointer-tag launch abort, renderer lock-out)

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
