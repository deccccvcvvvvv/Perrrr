# Building the APK — GitHub Actions only

**Policy:** this project never builds an APK on a developer machine. `./gradlew assembleDebug`,
`assembleRelease`, `build` (and equivalents), Android Studio's *Build APK*, and any local attempt to
package the app are forbidden. Source edits, Gradle/YAML edits and pure-JVM `:core:test` runs are
fine; compiling/packaging the APK happens only inside the GitHub Actions workflow.

## Workflow

`.github/workflows/android.yml`, job **`build`**:

1. `actions/checkout@v4`.
2. `actions/setup-java@v4` — Temurin JDK 17 (matches AGP 8.5.2 / Kotlin 2.0.20).
3. `sdkmanager` — `platforms;android-34`, `build-tools;34.0.0`, `ndk;26.3.11579264`, `cmake;3.22.1`;
   writes `local.properties`.
4. `gradle/actions/setup-gradle@v4`.
5. **Restore bundled payloads.** `assets/bundled/` is gitignored, so the workflow downloads the
   pinned `CSLauncherPro-1.0.4-round7-debug.apk` release asset with `gh release download`, verifies
   its SHA-256 (`8da3b7c5a7ce873a4ddd43c3af16ca263d2f3006706877cae09b275f0d305335`), extracts
   `assets/bundled/*` into `app/src/main/assets/`. This supplies the Java 8/17/21/25 runtimes and
   the LWJGL 3.3.3 / 3.4.3, GL4ES and MobileGlues component packs. Without it, a clean checkout
   produces an APK with no usable game runtime.
6. `:core:test`, `:app:testDebugUnitTest`, `:app:checkAndroidApi`, `:app:lintDebug`.
7. `:app:assembleDebug` — **the only APK build step**. Gradle compiles all Kotlin and builds
   `libcsglfw.so` / `libcsbridge.so` from `app/src/main/cpp` via CMake, so launcher code is never
   stale even though the third-party payloads are reused.
8. `tools/check_bundle_needed.py` verifies every ELF `DT_NEEDED`, and the APK contents are asserted.
9. On a successful build from `main` (push or manual dispatch), the workflow creates a published
   GitHub Release tagged `cslauncher-build-<run-number>-<short-sha>` and attaches `app-debug.apk`
   plus its SHA-256 checksum. The APK is **not** uploaded as a workflow artifact. Release visibility
   follows repository visibility; this target repository is public. Pull requests still run
   build/checks but never publish a release.
10. On failure, `build-reports` (compiler/test/lint output) is uploaded and a summary is written to
    the run's job summary. No APK or release is published when a step fails.

The `natives` / `natives34` jobs that cross-compile LWJGL, GL4ES, MobileGlues, OpenAL Soft,
FreeType, shaderc and SPIRV-Cross from source are preserved but gated behind
`workflow_dispatch → rebuild_natives = true`. They are not on the APK build path, so a two-hour
source build is not required for every push and a failure there cannot block an APK.

## Triggering

- Automatic build on push to `main` and on pull requests. A successful `main` push publishes a GitHub Release; a pull request only builds/checks.
- Manual: **Actions → Android build → Run workflow**, optional `rebuild_natives`. Dispatching from `main` publishes a release after a successful build; dispatching from another branch does not.
- API: `POST /repos/{owner}/{repo}/actions/workflows/{workflow_id}/dispatches` with `{"ref":"main"}`. A successful main dispatch publishes the release.

## Updating the pinned payloads

When third-party natives/runtimes change, run the source jobs with `rebuild_natives`, publish a new
release asset, then update `PAYLOAD_TAG`, `PAYLOAD_APK` and `PAYLOAD_SHA256` in the workflow's
`env:` block together. The checksum gate makes a wrong or tampered asset fail loudly.

## Verified target build

On 2026-10-27, public repository `deccccvcvvvvv/Perrrr` completed GitHub Actions run #1
successfully: unit tests, API-level check, lint, APK assembly, native dependency checks, and release
publication all passed. The resulting APK is published as `cslauncher-build-1-fb4c6ec`.

The earlier private source repository had an account-level Actions billing/spending-limit block;
that was not a source-code failure. The target repository's public Actions run succeeded.
