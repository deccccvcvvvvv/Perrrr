#!/usr/bin/env bash
# CS Launcher Pro — builds every native component the launcher bundles, from upstream sources, for one Android ABI.
#
#   tools/natives/build_natives.sh gen                 # once: generate LWJGL's C sources (arch independent)
#   tools/natives/build_natives.sh build <abi> <out>   # build + package components for <abi> into <out>/bundled/<abi>
#   tools/natives/build_natives.sh runtimes <abi> <out> # add the Android OpenJDK runtimes (verified) to the bundle
#
# Upstream projects (all built unmodified except where noted; licences in docs/DEPENDENCIES_AND_LICENSES.md):
#   LWJGL 3.3.3 (BSD-3)  libffi 3.4.6 (MIT)  FreeType 2.13.2 (FTL)  OpenAL Soft 1.23.1 (LGPL-2.1)
#   GL4ES (MIT, ptitSeb)  MobileGlues (LGPL-2.1, MobileGL-Dev)  OpenJDK Android builds (GPLv2+CE, AngelAuraMC)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${CS_NATIVES_WORK:-$HERE/../../.natives-work}"; mkdir -p "$WORK"; WORK="$(cd "$WORK" && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
API=26
JOBS="${JOBS:-$(nproc)}"

LWJGL_VERSION=3.3.3
LIBFFI_VERSION=3.4.6
FREETYPE_TAG=VER-2-13-2
OPENAL_VERSION=1.23.1
GL4ES_COMMIT=2037865590d410bcd7b8d1f22b841472ed6d39ac
MOBILEGLUES_COMMIT=97558a69157b033b890958b1e49258c5b51a80c6
VULKAN_HEADERS_TAG=v1.3.275          # matches VK_HEADER_VERSION of the headers MobileGlues bundles
COMPONENT_REV=1   # bump when packaging changes so installed packs are refreshed

log() { echo "::group::$*" 2>/dev/null || true; echo "==> $*"; }
endlog() { echo "::endgroup::" 2>/dev/null || true; }
fetch() { # url dest
  if [ ! -s "$2" ]; then curl -fL --retry 4 --retry-delay 3 -o "$2.part" "$1"; mv "$2.part" "$2"; fi
}

setup_abi() {
  ABI="$1"
  [ -n "$NDK" ] && [ -d "$NDK" ] || { echo "ANDROID_NDK_HOME is not set"; exit 2; }
  TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
  case "$ABI" in
    arm64-v8a)   TRIPLE=aarch64-linux-android;     LARCH=arm64; FFIDIR=aarch64 ;;
    armeabi-v7a) TRIPLE=armv7a-linux-androideabi;  LARCH=arm32; FFIDIR=arm ;;
    x86_64)      TRIPLE=x86_64-linux-android;      LARCH=x64;   FFIDIR=x86 ;;
    x86)         TRIPLE=i686-linux-android;        LARCH=x86;   FFIDIR=x86 ;;
    *) echo "unknown ABI $ABI"; exit 2 ;;
  esac
  HOSTTRIPLE="${TRIPLE/armv7a-linux-androideabi/arm-linux-androideabi}"
  CC="$TC/bin/${TRIPLE}${API}-clang"; CXX="$TC/bin/${TRIPLE}${API}-clang++"
  AR="$TC/bin/llvm-ar"; STRIP="$TC/bin/llvm-strip"; RANLIB="$TC/bin/llvm-ranlib"
  B="$WORK/build-$ABI"; STAGE="$WORK/stage-$ABI"; mkdir -p "$B" "$STAGE"
  CMAKE_ANDROID=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI"
                 -DANDROID_PLATFORM="android-$API" -DCMAKE_BUILD_TYPE=Release -DANDROID_STL=c++_static -G Ninja)
}

# ------------------------------------------------------------------------------------------------ LWJGL generator
gen_lwjgl() {
  log "LWJGL $LWJGL_VERSION: generate bindings"
  cd "$WORK"
  [ -d lwjgl3 ] || git clone -q --depth 1 --branch "$LWJGL_VERSION" https://github.com/LWJGL/lwjgl3.git
  cd lwjgl3
  if [ ! -f "$WORK/lwjgl3/.cs-generated" ]; then
    command -v ant >/dev/null || { echo "Apache Ant is required"; exit 2; }
    ANT_OPTS="${ANT_OPTS:--Xmx3g}" LWJGL_BUILD_TYPE=release ant -Dplatform.linux=true compile-templates generate
    touch .cs-generated
  fi
  endlog
}

# ------------------------------------------------------------------------------------------------ libffi
build_libffi() {
  log "libffi $LIBFFI_VERSION ($ABI)"
  local src="$WORK/libffi-$LIBFFI_VERSION"
  fetch "https://github.com/libffi/libffi/releases/download/v$LIBFFI_VERSION/libffi-$LIBFFI_VERSION.tar.gz" "$WORK/libffi.tar.gz"
  [ -d "$src" ] || tar xzf "$WORK/libffi.tar.gz" -C "$WORK"
  FFI="$B/libffi"
  if [ ! -f "$FFI/lib/libffi.a" ]; then
    rm -rf "$B/ffi-obj"; mkdir -p "$B/ffi-obj"; cd "$B/ffi-obj"
    CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" CFLAGS="-fPIC -O2" \
      "$src/configure" --host="$HOSTTRIPLE" --prefix="$FFI" --disable-shared --enable-static --disable-docs --disable-multi-os-directory >/dev/null
    make -j"$JOBS" >/dev/null && make install >/dev/null
  fi
  endlog
}

# ------------------------------------------------------------------------------------------------ LWJGL natives
quiet() { # label cmd...   run with output captured; print the last lines if it fails
  local label="$1"; shift; local logf="$B/$label.log"
  if ! "$@" > "$logf" 2>&1; then echo "---- $label failed; last lines of $logf:"; grep -vE "Deprecation Warning|Compatibility with CMake|^\s*$" "$logf" | tail -60; return 1; fi
}

lw_compile() { # outdir cflags... -- files...   (fails if any translation unit fails)
  local out="$1"; shift; local flags=(); while [ "$1" != "--" ]; do flags+=("$1"); shift; done; shift
  mkdir -p "$out"; rm -f "$out/.failed"
  for f in "$@"; do
    { "$CC" "${flags[@]}" -c "$f" -o "$out/$(basename "${f%.*}").o" || echo "$f" >> "$out/.failed"; } &
    while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do wait -n || true; done
  done
  wait || true
  if [ -s "$out/.failed" ]; then echo "compile failed:"; cat "$out/.failed"; return 1; fi
}

# libffi only declares the ABI enum values of the target architecture, while LWJGL's generated LibFFI.c references
# every ABI it knows. Probe each name with the target compiler and map the missing ones to -1 ("not available").
ffi_abi_defs() { # generated LibFFI.c
  local n
  for n in $(grep -oE 'return \(jint\)FFI_[A-Z0-9_]+;' "$1" | sed -E 's/.*\(jint\)(FFI_[A-Z0-9_]+);/\1/' | sort -u); do
    printf '#include "ffi.h"\nint cs_probe(void) { return (int)%s; }\n' "$n" | "$CC" -I"$FFI/include" -x c -c - -o /dev/null 2>/dev/null || echo "-D$n=-1"
  done
}

build_lwjgl() {
  log "LWJGL $LWJGL_VERSION natives ($ABI)"
  local L="$WORK/lwjgl3/modules/lwjgl" OUT="$STAGE/lwjgl/lib"; mkdir -p "$OUT"
  local JNI_INC; JNI_INC="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")/include"
  local common=(-std=gnu11 -O2 -fPIC -fvisibility=hidden -pthread -DNDEBUG -DLWJGL_LINUX "-DLWJGL_$LARCH" -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0
                -D_GNU_SOURCE -I"$JNI_INC" -I"$JNI_INC/linux" -I"$L/core/src/main/c" -I"$L/core/src/main/c/linux" -Wno-unused-command-line-argument)
  local vs="$WORK/lwjgl3/config/linux/version.script"

  # core: everything LWJGL's Linux build compiles except io_uring (needs kernel/libc features Android apps cannot rely on)
  local core_src=("$L"/core/src/main/c/*.c "$L"/core/src/generated/c/*.c)
  for f in "$L"/core/src/generated/c/linux/*.c; do case "$(basename "$f")" in *liburing*|*LibURing*|*IOURing*) ;; *) core_src+=("$f");; esac; done
  local ffidefs; ffidefs=($(ffi_abi_defs "$L/core/src/generated/c/org_lwjgl_system_libffi_LibFFI.c"))
  echo "libffi ABIs unavailable on $ABI: ${ffidefs[*]:-none}"
  lw_compile "$B/o-core" "${common[@]}" -I"$FFI/include" "${ffidefs[@]}" -- "${core_src[@]}"
  "$CC" -shared -o "$OUT/liblwjgl.so" "$B"/o-core/*.o "$FFI/lib/libffi.a" -Wl,--no-undefined -Wl,--version-script,"$vs" -Wl,-z,noexecstack -ldl -lm -llog

  # opengl (GLX/WGL entry points are looked up at run time; WGL is Windows only)
  local gl_src=(); for f in "$L"/opengl/src/generated/c/*.c; do case "$(basename "$f")" in *WGL*) ;; *) gl_src+=("$f");; esac; done
  lw_compile "$B/o-opengl" "${common[@]}" -I"$L/opengl/src/main/c" -- "${gl_src[@]}"
  "$CC" -shared -o "$OUT/liblwjgl_opengl.so" "$B"/o-opengl/*.o -Wl,--no-undefined -Wl,--version-script,"$vs" -ldl -lm

  # stb
  lw_compile "$B/o-stb" "${common[@]}" -isystem "$L/stb/src/main/c" -- "$L"/stb/src/generated/c/*.c
  "$CC" -shared -o "$OUT/liblwjgl_stb.so" "$B"/o-stb/*.o -Wl,--no-undefined -Wl,--version-script,"$vs" -lm

  # tinyfd (file dialogs are not available on Android; the library must still load because Minecraft links the class)
  lw_compile "$B/o-tinyfd" "${common[@]}" -I"$L/tinyfd/src/main/c" -- "$L"/tinyfd/src/main/c/*.c "$L"/tinyfd/src/generated/c/*.c
  "$CC" -shared -o "$OUT/liblwjgl_tinyfd.so" "$B"/o-tinyfd/*.o -Wl,--no-undefined -Wl,--version-script,"$vs" -lm

  "$STRIP" --strip-unneeded "$OUT"/liblwjgl*.so
  endlog
}

# ------------------------------------------------------------------------------------------------ FreeType (lwjgl-freetype loads libfreetype.so)
build_freetype() {
  log "FreeType $FREETYPE_TAG ($ABI)"
  [ -d "$WORK/freetype" ] || git clone -q --depth 1 --branch "$FREETYPE_TAG" https://github.com/freetype/freetype.git "$WORK/freetype"
  cmake -S "$WORK/freetype" -B "$B/freetype" "${CMAKE_ANDROID[@]}" -DBUILD_SHARED_LIBS=ON \
    -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_PNG=ON -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON >/dev/null
  cmake --build "$B/freetype" -j"$JOBS" >/dev/null
  cp -L "$(ls "$B"/freetype/libfreetype.so* | head -1)" "$STAGE/lwjgl/lib/libfreetype.so"
  "$STRIP" --strip-unneeded "$STAGE/lwjgl/lib/libfreetype.so"
  endlog
}

# ------------------------------------------------------------------------------------------------ OpenAL Soft
build_openal() {
  log "OpenAL Soft $OPENAL_VERSION ($ABI)"
  [ -d "$WORK/openal-soft" ] || git clone -q --depth 1 --branch "$OPENAL_VERSION" https://github.com/kcat/openal-soft.git "$WORK/openal-soft"
  cmake -S "$WORK/openal-soft" -B "$B/openal" "${CMAKE_ANDROID[@]}" -DALSOFT_UTILS=OFF -DALSOFT_EXAMPLES=OFF -DALSOFT_TESTS=OFF \
    -DALSOFT_INSTALL=OFF -DALSOFT_BACKEND_OPENSL=ON -DALSOFT_REQUIRE_OPENSL=ON -DALSOFT_BACKEND_WAVE=ON >/dev/null
  cmake --build "$B/openal" -j"$JOBS" >/dev/null
  cp -L "$(ls "$B"/openal/libopenal.so* | head -1)" "$STAGE/lwjgl/lib/libopenal.so"
  "$STRIP" --strip-unneeded "$STAGE/lwjgl/lib/libopenal.so"
  endlog
}

# ------------------------------------------------------------------------------------------------ GL4ES
build_gl4es() {
  log "GL4ES $GL4ES_COMMIT ($ABI)"
  local src="$WORK/gl4es"
  if [ ! -d "$src" ]; then git init -q "$src" && git -C "$src" fetch -q --depth 1 https://github.com/ptitSeb/gl4es.git "$GL4ES_COMMIT" && git -C "$src" checkout -q FETCH_HEAD; fi
  # Upstream Android.mk builds a static library; build the same sources as a shared library (exports glXGetProcAddress).
  sed -e 's/^LOCAL_CFLAGS += -DSTATICLIB$//' -e 's/include \$(BUILD_STATIC_LIBRARY)/include $(BUILD_SHARED_LIBRARY)/' "$src/Android.mk" > "$src/cs-shared.mk"
  (cd "$src" && "$NDK/ndk-build" -j"$JOBS" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=cs-shared.mk APP_ABI="$ABI" APP_PLATFORM="android-$API" \
     NDK_OUT="$B/gl4es/obj" NDK_LIBS_OUT="$B/gl4es/libs" >/dev/null)
  mkdir -p "$STAGE/gl4es/lib"; cp "$B/gl4es/libs/$ABI/libGL.so" "$STAGE/gl4es/lib/libgl4es.so"
  endlog
}

# ------------------------------------------------------------------------------------------------ MobileGlues
build_mobileglues() {
  log "MobileGlues $MOBILEGLUES_COMMIT ($ABI)"
  local src="$WORK/MobileGlues"
  if [ ! -d "$src" ]; then
    git init -q "$src" && git -C "$src" fetch -q --depth 1 https://github.com/MobileGL-Dev/MobileGlues.git "$MOBILEGLUES_COMMIT" && git -C "$src" checkout -q FETCH_HEAD
    # perfetto is only used for profiling builds; skip the multi-GB checkout
    for m in MobileGlues-cpp/3rdparty/glslang MobileGlues-cpp/3rdparty/SPIRV-Cross MobileGlues-cpp/3rdparty/xxhash MobileGlues-cpp/include/ska; do
      git -C "$src" submodule update --init --depth 1 "$m"
    done
  fi
  # MobileGlues bundles Vulkan headers 1.3.275, which include vk_video/*.h; upstream gets those from NDK 27's sysroot.
  # Supply the same Khronos release as a lower-priority system include so NDK 26 works too.
  local vkh="$WORK/Vulkan-Headers-$VULKAN_HEADERS_TAG"
  [ -d "$vkh" ] || git clone -q --depth 1 --branch "$VULKAN_HEADERS_TAG" https://github.com/KhronosGroup/Vulkan-Headers.git "$vkh"
  quiet mobileglues-configure cmake -S "$src/MobileGlues-cpp" -B "$B/mobileglues" "${CMAKE_ANDROID[@]}" \
    -DCMAKE_C_FLAGS="-isystem $vkh/include" -DCMAKE_CXX_FLAGS="-isystem $vkh/include"
  quiet mobileglues-build cmake --build "$B/mobileglues" --target mobileglues -j"$JOBS"
  mkdir -p "$STAGE/mobileglues/lib"; cp "$B/mobileglues/libmobileglues.so" "$STAGE/mobileglues/lib/"
  "$STRIP" --strip-unneeded "$STAGE/mobileglues/lib/libmobileglues.so"
  endlog
}

# ------------------------------------------------------------------------------------------------ LWJGL Java side (stock Maven Central jars)
fetch_lwjgl_jars() {
  log "LWJGL $LWJGL_VERSION jars"
  mkdir -p "$STAGE/lwjgl/jars"
  for m in lwjgl lwjgl-glfw lwjgl-opengl lwjgl-openal lwjgl-stb lwjgl-tinyfd lwjgl-jemalloc lwjgl-freetype; do
    local base="https://repo1.maven.org/maven2/org/lwjgl/$m/$LWJGL_VERSION/$m-$LWJGL_VERSION.jar" f="$WORK/jars/$m-$LWJGL_VERSION.jar"
    mkdir -p "$WORK/jars"; fetch "$base" "$f"; fetch "$base.sha1" "$f.sha1"
    echo "$(cut -c1-40 "$f.sha1")  $f" | sha1sum -c --quiet -
    cp "$f" "$STAGE/lwjgl/jars/"
  done
  endlog
}

package() { # abi out
  local out="$2/bundled/$ABI"; mkdir -p "$out/components"
  python3 "$HERE/make_component.py" --abi "$ABI" --rev "$COMPONENT_REV" --out "$out" \
    --component "lwjgl3-android|LWJGL $LWJGL_VERSION for Android|$LWJGL_VERSION|lwjgl|BSD-3-Clause (LWJGL), MIT (libffi), FTL (FreeType), LGPL-2.1 (OpenAL Soft)|LWJGL/lwjgl3@$LWJGL_VERSION, libffi $LIBFFI_VERSION, freetype $FREETYPE_TAG, openal-soft $OPENAL_VERSION|lwjgl:$LWJGL_VERSION|$STAGE/lwjgl" \
    --component "gl4es|GL4ES|${GL4ES_COMMIT:0:10}|renderer|MIT|ptitSeb/gl4es@$GL4ES_COMMIT|lib:libgl4es.so|$STAGE/gl4es" \
    --component "mobileglues|MobileGlues|${MOBILEGLUES_COMMIT:0:10}|renderer|LGPL-2.1 (MobileGlues), Apache-2.0 (SPIRV-Cross), BSD-3/MIT (glslang)|MobileGL-Dev/MobileGlues@$MOBILEGLUES_COMMIT|lib:libmobileglues.so|$STAGE/mobileglues"
}

runtimes() { # abi out
  local arch; case "$ABI" in arm64-v8a) arch=arm64;; armeabi-v7a) arch=arm;; x86_64) arch=x86_64;; x86) arch=x86;; esac
  local out="$2/bundled/$ABI"; mkdir -p "$out/runtimes"
  for major in ${CS_RUNTIME_MAJORS:-8 17 21 25}; do
    log "Java $major ($arch)"
    local name="jre$major-android-$arch.tar.xz"
    local meta; meta="$(curl -fsSL -H 'Accept: application/vnd.github+json' ${GITHUB_TOKEN:+-H "Authorization: Bearer $GITHUB_TOKEN"} \
      "https://api.github.com/repos/AngelAuraMC/angelauramc-openjdk-build/releases/tags/download_jre$major")"
    local url sha
    url="$(python3 -c 'import json,sys;a=[x for x in json.loads(sys.argv[1])["assets"] if x["name"]==sys.argv[2]];print(a[0]["browser_download_url"] if a else "")' "$meta" "$name")"
    sha="$(python3 -c 'import json,sys;a=[x for x in json.loads(sys.argv[1])["assets"] if x["name"]==sys.argv[2]];print((a[0].get("digest") or "").replace("sha256:","") if a else "")' "$meta" "$name")"
    [ -n "$url" ] || { echo "no $name published; skipping"; endlog; continue; }
    [ -n "$sha" ] || { echo "no digest for $name; refusing"; exit 3; }
    fetch "$url" "$WORK/$name"
    echo "$sha  $WORK/$name" | sha256sum -c --quiet - || { rm -f "$WORK/$name"; echo "checksum mismatch for $name"; exit 3; }
    local ver; ver="$(tar xJOf "$WORK/$name" ./release 2>/dev/null | sed -n 's/^JAVA_VERSION="\(.*\)"/\1/p')"
    cp "$WORK/$name" "$out/runtimes/jre$major.tar.xz"
    python3 "$HERE/make_component.py" --abi "$ABI" --out "$out" --runtime "jre$major|$major|${ver:-$major}|runtimes/jre$major.tar.xz|AngelAuraMC/angelauramc-openjdk-build download_jre$major"
    endlog
  done
}

case "${1:-}" in
  gen) gen_lwjgl ;;
  build) setup_abi "$2"; gen_lwjgl; build_libffi; build_lwjgl; build_freetype; build_openal; fetch_lwjgl_jars; build_gl4es; build_mobileglues; package "$2" "$3" ;;
  lwjgl) setup_abi "$2"; build_libffi; build_lwjgl ;;   # development helper
  ffiprobe) setup_abi "$2"; build_libffi >&2; ffi_abi_defs "$3" ;;   # development helper: <abi> <LibFFI.c>
  gl4es) setup_abi "$2"; build_gl4es ;;
  mobileglues) setup_abi "$2"; build_mobileglues ;;
  runtimes) ABI="$2"; runtimes "$2" "$3" ;;   # no NDK needed
  *) echo "usage: $0 gen | build <abi> <out> | runtimes <abi> <out>"; exit 2 ;;
esac
