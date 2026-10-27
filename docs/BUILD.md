# Building CS Launcher Pro

> **APK policy:** APKs are assembled only by `.github/workflows/android.yml` on GitHub Actions. Do not run `assembleDebug`, `assembleRelease`, or `build` locally. Local Gradle use below is limited to tests and static checks; download installable APKs from the public GitHub Releases page. See `CI_BUILD.md`.

## 1. Toolchain

`tools/setup_toolchain.sh` installs everything into `/opt/cs-toolchain` (needs network and write access to `/opt`):

* Temurin JDK 17 (AGP 8.x requires 17)
* Android SDK: platform `android-34`, build-tools `34.0.0`, NDK `26.3.11579264`, CMake `3.22.1` (licences accepted non-interactively)
* Gradle 8.7 (the project also contains the Gradle wrapper)

```bash
sudo mkdir -p /opt/cs-toolchain && sudo chown "$USER" /opt/cs-toolchain
tools/setup_toolchain.sh
export JAVA_HOME=/opt/cs-toolchain/jdk17 PATH=/opt/cs-toolchain/jdk17/bin:$PATH ANDROID_HOME=/opt/cs-toolchain/sdk
```

Android Studio (Koala or newer) also works: open the project root, and point `local.properties` → `sdk.dir` at your SDK.
The NDK and CMake versions above are pinned in `app/build.gradle.kts`.

## 2. Commands

| Goal | Command |
|---|---|
| Core unit tests (pure JVM, no SDK needed) | `./gradlew :core:test` |
| Network/integration tests | `./gradlew :core:test -Pintegration` |
| Host end-to-end (real install + launch on Linux with desktop GL) | `./gradlew :core:hostE2E -Pe2eArgs="1.18.2 $HOME/.cache/cs-e2e docs/evidence launch 768 45"` (args: version, work dir, evidence dir, mode, heap MB, title-screen wait s). On low-memory hosts, run the printed classpath directly, as in `BUILD_AND_TEST_REPORT.md` |
| Android API-level guard | `./gradlew :app:checkAndroidApi` (also runs with `check`) |
| Lint | `./gradlew :app:lintDebug` → `app/build/reports/lint-results-debug.html` |
| Install a published APK | Download `app-debug.apk` from the latest GitHub Release, then `adb install -r app-debug.apk` |

## 3. Memory on small test machines

`gradle.properties` is tuned for a 2 GB host: Gradle heap 1100 MB, in-process Kotlin compiler, and no parallel workers. Local device APK packaging is intentionally not documented or supported; use GitHub Actions for all APK builds.

## 4. Native code

`app/src/main/cpp` is built by CMake for each ABI with `-Wall -Wextra -fvisibility=hidden -O2`, without a C++ STL:
* `libcsglfw.so`: an original implementation of the GLFW 3.4 API on top of Android `ANativeWindow`/EGL/OSMesa, with an
  input event queue and callbacks into the app. LWJGL loads it through `-Dorg.lwjgl.glfw.libname`.
* `libcsbridge.so`: the in-process JVM launcher (`dlopen(libjvm.so)`, `JNI_CreateJavaVM`), stdio redirection to the session
  log, environment variables and `chdir` via JNI, and exit/abort hooks.

To check the libraries by hand outside Gradle:
```bash
NDK=$ANDROID_HOME/ndk/26.3.11579264; CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang
F="-shared -fPIC -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden -O2 -std=c11"
$CC $F csglfw.c input_queue.c host_callbacks.c -landroid -lEGL -llog -ldl -o /tmp/libcsglfw.so
$CC $F csbridge.c -L/tmp -lcsglfw -llog -ldl -Wl,--no-undefined -o /tmp/libcsbridge.so
```

## 5. Configuration that is intentionally not in the repository
* **Microsoft sign-in:** new account login uses a bundled public client identifier and the legacy Live flow; no Client ID is entered by the user. This is unofficial and may be blocked by Microsoft. Previously saved Azure v2 sessions retain their configured refresh path.
* **CurseForge:** the API key is entered in-app and stored encrypted with an Android Keystore key.
* **Release signing:** provide your own keystore. No signing secrets are committed.
* **Java runtime, LWJGL and renderer packs:** imported at runtime. See `COMPATIBILITY.md` §5–6.
