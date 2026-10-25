# CS Launcher Pro — Implementation Plan (from repository inspection)

## Inspection results (2026-10-25)
* Repository: **empty** — project created from scratch. No existing code to preserve.
* Host: Debian 13 x86_64, 2 vCPU, ~2 GB RAM, JDK 11 only, no Android SDK, **no /dev/kvm** (no emulator possible).
* Network: Mojang (piston-meta), Google Maven/SDK repo, Gradle, Adoptium reachable.

## Consequences
* Toolchain is bootstrapped by `tools/setup_toolchain.sh` (Temurin 17, SDK 34, build-tools 34, NDK r26d, CMake 3.22, Gradle 8.7).
* Gradle is tuned for 2 GB (in-process Kotlin compiler, no parallel workers).
* No Android device/emulator: on-device behaviour (rendering, touch, recording) can be **compiled but not executed** here.
* The launcher core is a pure JVM module so that the real install + launch pipeline can be executed on this host
  against Mojang's servers (Xvfb + Mesa llvmpipe), proving metadata, rules, downloads, verification and argument generation.

## Modules
* `:core` (Kotlin/JVM, no Android deps): metadata, rules, downloads, installer, verifier, runtime selection, launch planning,
  state machine, startup monitor, renderer registry/selection, control-layout model + touch engine, dynamic cursor logic,
  recorder planning, content managers (mods/packs/shaders/worlds/servers), NBT, diagnostics & repair, accounts (MSA flow),
  performance advisor, storage analysis, settings/profile persistence with versioned schemas.
* `:app` (Android, Kotlin + Jetpack Compose UI, C via NDK): UI/themes/navigation, Android services
  (install keep-alive, recorder), Keystore token storage, game activity in a separate `:game` process,
  native bridge (`libcsbridge.so`: in-process JVM via JNI_CreateJavaVM, stdio capture, input queue) and
  `libcsglfw.so` (original GLFW-API implementation over EGL / OSMesa for LWJGL 3).

## Phases executed
Foundation → installation → runtime & launch → renderer abstraction → touch engine → recorder & cursor →
content → diagnostics & performance → build & test report (`docs/BUILD_AND_TEST_REPORT.md`).
