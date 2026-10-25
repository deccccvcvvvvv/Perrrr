# Prebuilt native libraries packaged in the APK

| File | Source | SHA-256 | Licence |
|---|---|---|---|
| `arm64-v8a/libc++_shared.so` | Android NDK r27d (`Pkg.Revision = 27.3.13750724`), `toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so`, unmodified | `d523468d62d9b603cb3354294d70d4b2feabf2c3f1e43b0c96c9aabf32813708` | Apache-2.0 WITH LLVM-exception (LLVM project) |

## Why it is here

The bundled Java 25 runtime (AngelAuraMC `jre25-android-arm64`) is built with NDK r27d and links 33 of its
libraries (including `libjvm.so`) against the shared C++ runtime, which the runtime archive does not include.
Without it Minecraft 26.x fails with `libjvm.so: dlopen failed: library "libc++_shared.so" not found`.
The launcher preloads this copy with `RTLD_GLOBAL` before `libjvm.so` (`AndroidRuntimeEnv.build(cxxShared=…)`),
so the dynamic linker resolves the runtime's `DT_NEEDED` entry to it. The exact NDK release matches the one the
runtime was built with, so every C++ symbol the runtime imports is present (checked with `llvm-nm`).

The app's own native code is built with `ANDROID_STL=none` and does not use it.

`tools/check_bundle_needed.py` verifies, for every ABI, that each shared library in the APK, the bundled
runtimes and the bundled components only needs system libraries, libraries from the same archive, or
libraries in the APK. It runs in CI after `assembleDebug`.

To update: extract the same path from the NDK release matching the runtime's build (see `.comment` /
`readelf -p .comment libjvm.so` for the clang version) and update the hash above.
