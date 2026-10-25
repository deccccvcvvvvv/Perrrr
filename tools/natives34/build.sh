#!/usr/bin/env bash
# CS Launcher Pro — native stack for Minecraft 26.x (LWJGL 3.4.x), built from upstream sources for Android.
#
#   tools/natives34/build.sh build <abi> <stageOut>      # compile everything into <stageOut>/lwjgl34/{lib,jars}
#   tools/natives34/build.sh verify <stageOut>           # check every required export / JNI method against the jars
#   tools/natives34/build.sh package <stageOut> <assets> <abi>  # pack as component "lwjgl34-android" into <assets>/bundled/<abi>
#
# Why a separate stack: Minecraft 26.1/26.2 use LWJGL 3.4.1 (GLFW) and 26.3+ use LWJGL 3.4.3 with SDL3 windowing and a
# shader pipeline that runs GLSL through shaderc + SPIRV-Cross even on OpenGL. LWJGL's Java classes and natives must come
# from the same release, so this pack carries its own jars (3.4.3, a superset of 3.4.1) and natives.
#
# Upstream projects (unmodified; licences in docs/DEPENDENCIES_AND_LICENSES.md):
#   LWJGL 3.4.3 (BSD-3)  libffi 3.8.0 (MIT)  FreeType 2.14.3 (FTL)  OpenAL Soft 1.23.1 (LGPL-2.1)
#   shaderc v2026.2 + glslang + SPIRV-Tools + SPIRV-Headers (Apache-2.0 / BSD-3 / MIT)  SPIRV-Cross vulkan-sdk-1.4.341.0 (Apache-2.0)
# The SDL3 API itself is provided by the launcher's own bridge (app/src/main/cpp/cssdl3.c), not by upstream SDL.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="${CS_NATIVES_WORK:-$ROOT/.natives-work}/n34"; mkdir -p "$WORK"; WORK="$(cd "$WORK" && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
API=26
JOBS="${JOBS:-$(nproc)}"

LWJGL_VERSION=3.4.3
LIBFFI_VERSION=3.8.0
FREETYPE_TAG=VER-2-14-3
OPENAL_VERSION=1.23.1
SHADERC_TAG=v2026.2
SPIRV_CROSS_TAG=vulkan-sdk-1.4.341.0
COMPONENT_REV=1
JAR_MODULES="lwjgl lwjgl-glfw lwjgl-opengl lwjgl-openal lwjgl-stb lwjgl-tinyfd lwjgl-jemalloc lwjgl-freetype lwjgl-sdl lwjgl-shaderc lwjgl-spvc lwjgl-vma lwjgl-vulkan"

log() { echo "::group::$*" 2>/dev/null || true; echo "==> $*"; }
endlog() { echo "::endgroup::" 2>/dev/null || true; }
fetch() { if [ ! -s "$2" ]; then curl -fL --retry 4 --retry-delay 3 -o "$2.part" "$1"; mv "$2.part" "$2"; fi; }
quiet() { # label cmd...   output captured; tail printed on failure
  local label="$1"; shift; local logf="$B/$label.log"
  if ! "$@" > "$logf" 2>&1; then echo "---- $label failed; last lines of $logf:"; grep -vE "Deprecation Warning|Compatibility with CMake|^\s*$" "$logf" | tail -60; return 1; fi
}
git_at() { # url ref dir   (shallow checkout of a tag, branch or commit)
  [ -d "$3/.git" ] && return 0
  git init -q "$3"; git -C "$3" fetch -q --depth 1 "$1" "$2"; git -C "$3" checkout -q FETCH_HEAD
}
abs_out() { mkdir -p "$1" && (cd "$1" && pwd); }

setup_abi() {
  ABI="$1"
  [ -n "$NDK" ] && [ -d "$NDK" ] || { echo "ANDROID_NDK_HOME is not set"; exit 2; }
  TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
  case "$ABI" in
    arm64-v8a)   TRIPLE=aarch64-linux-android;    LARCH=arm64 ;;
    armeabi-v7a) TRIPLE=armv7a-linux-androideabi; LARCH=arm32 ;;
    x86_64)      TRIPLE=x86_64-linux-android;     LARCH=x64 ;;
    *) echo "unsupported ABI $ABI"; exit 2 ;;
  esac
  HOSTTRIPLE="${TRIPLE/armv7a-linux-androideabi/arm-linux-androideabi}"
  CC="$TC/bin/${TRIPLE}${API}-clang"; CXX="$TC/bin/${TRIPLE}${API}-clang++"
  AR="$TC/bin/llvm-ar"; STRIP="$TC/bin/llvm-strip"; RANLIB="$TC/bin/llvm-ranlib"; NM="$TC/bin/llvm-nm"
  B="$WORK/build-$ABI"; mkdir -p "$B"
  CMAKE_ANDROID=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI"
                 -DANDROID_PLATFORM="android-$API" -DCMAKE_BUILD_TYPE=Release -DANDROID_STL=c++_static -G Ninja)
}

# ------------------------------------------------------------------------------------------------ libffi (static, linked into liblwjgl)
build_libffi() {
  log "libffi $LIBFFI_VERSION ($ABI)"
  local src="$WORK/libffi-$LIBFFI_VERSION"
  fetch "https://github.com/libffi/libffi/releases/download/v$LIBFFI_VERSION/libffi-$LIBFFI_VERSION.tar.gz" "$WORK/libffi-$LIBFFI_VERSION.tar.gz"
  [ -d "$src" ] || tar xzf "$WORK/libffi-$LIBFFI_VERSION.tar.gz" -C "$WORK"
  FFI="$B/libffi"
  if [ ! -f "$FFI/lib/libffi.a" ]; then
    rm -rf "$B/ffi-obj"; mkdir -p "$B/ffi-obj"; cd "$B/ffi-obj"
    quiet ffi-configure env CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" CFLAGS="-fPIC -O2" \
      "$src/configure" --host="$HOSTTRIPLE" --prefix="$FFI" --disable-shared --enable-static --disable-docs --disable-multi-os-directory
    quiet ffi-make make -j"$JOBS"; quiet ffi-install make install
  fi
  endlog
}

# ------------------------------------------------------------------------------------------------ LWJGL natives (generated C is part of the 3.4 sources)
lw_compile() { # outdir cflags... -- files...
  local out="$1"; shift; local flags=(); while [ "$1" != "--" ]; do flags+=("$1"); shift; done; shift
  mkdir -p "$out"; rm -f "$out/.failed"
  for f in "$@"; do
    { "$CC" "${flags[@]}" -c "$f" -o "$out/$(basename "${f%.*}").o" 2>> "$out/errors.log" || echo "$f" >> "$out/.failed"; } &
    while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do wait -n || true; done
  done
  wait || true
  if [ -s "$out/.failed" ]; then echo "compile failed:"; cat "$out/.failed"; tail -40 "$out/errors.log"; return 1; fi
}

# libffi declares only the target architecture's ABI enum values; LWJGL's LibFFI.c references every ABI it knows.
ffi_abi_defs() {
  local n
  for n in $(grep -oE 'return \(jint\)FFI_[A-Z0-9_]+;' "$1" | sed -E 's/.*\(jint\)(FFI_[A-Z0-9_]+);/\1/' | sort -u); do
    printf '#include "ffi.h"\nint cs_probe(void) { return (int)%s; }\n' "$n" | "$CC" -I"$FFI/include" -x c -c - -o /dev/null 2>/dev/null || echo "-D$n=-1"
  done
}

build_lwjgl() {
  log "LWJGL $LWJGL_VERSION natives ($ABI)"
  git_at https://github.com/LWJGL/lwjgl3.git "$LWJGL_VERSION" "$WORK/lwjgl3"
  local L="$WORK/lwjgl3/modules/lwjgl" OUT="$STAGE/lib"; mkdir -p "$OUT"
  local JNI_INC; JNI_INC="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")/include"
  local common=(-std=gnu11 -O2 -fPIC -fvisibility=hidden -pthread -DNDEBUG -DLWJGL_LINUX "-DLWJGL_$LARCH" -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0
                -D_GNU_SOURCE -I"$JNI_INC" -I"$JNI_INC/linux" -I"$L/core/src/main/c" -I"$L/core/src/main/c/linux" -Wno-unused-command-line-argument)
  local vs="$WORK/lwjgl3/config/linux/version.script"

  # core: as upstream's Linux build, minus io_uring (kernel/libc features Android apps cannot rely on)
  local core_src=("$L"/core/src/main/c/*.c "$L"/core/src/generated/c/*.c)
  for f in "$L"/core/src/generated/c/linux/*.c; do case "$(basename "$f")" in *liburing*) ;; *) core_src+=("$f");; esac; done
  local ffidefs; ffidefs=($(ffi_abi_defs "$L/core/src/generated/c/org_lwjgl_system_libffi_LibFFI.c"))
  echo "libffi ABIs unavailable on $ABI: ${ffidefs[*]:-none}"
  lw_compile "$B/o-core" "${common[@]}" -I"$FFI/include" "${ffidefs[@]}" -- "${core_src[@]}"
  "$CC" -shared -o "$OUT/liblwjgl.so" "$B"/o-core/*.o "$FFI/lib/libffi.a" -Wl,--no-undefined -Wl,--version-script,"$vs" -Wl,-z,noexecstack -ldl -lm -llog

  local gl_src=(); for f in "$L"/opengl/src/generated/c/*.c; do case "$(basename "$f")" in *WGL*) ;; *) gl_src+=("$f");; esac; done
  lw_compile "$B/o-opengl" "${common[@]}" -I"$L/opengl/src/main/c" -- "${gl_src[@]}"
  "$CC" -shared -o "$OUT/liblwjgl_opengl.so" "$B"/o-opengl/*.o -Wl,--no-undefined -Wl,--version-script,"$vs" -ldl -lm

  lw_compile "$B/o-stb" "${common[@]}" -isystem "$L/stb/src/main/c" -- "$L"/stb/src/generated/c/*.c
  "$CC" -shared -o "$OUT/liblwjgl_stb.so" "$B"/o-stb/*.o -Wl,--no-undefined -Wl,--version-script,"$vs" -lm

  # tinyfd: dialogs do not exist on Android; Minecraft 26.1/26.2 still link the class, so the library must load.
  lw_compile "$B/o-tinyfd" "${common[@]}" -I"$L/tinyfd/src/main/c" -- "$L"/tinyfd/src/main/c/*.c "$L"/tinyfd/src/generated/c/*.c
  "$CC" -shared -o "$OUT/liblwjgl_tinyfd.so" "$B"/o-tinyfd/*.o -Wl,--no-undefined -Wl,--version-script,"$vs" -lm

  "$STRIP" --strip-unneeded "$OUT"/liblwjgl*.so
  endlog
}

# ------------------------------------------------------------------------------------------------ FreeType / OpenAL Soft
build_freetype() {
  log "FreeType $FREETYPE_TAG ($ABI)"
  git_at https://github.com/freetype/freetype.git "$FREETYPE_TAG" "$WORK/freetype"
  quiet ft-configure cmake -S "$WORK/freetype" -B "$B/freetype" "${CMAKE_ANDROID[@]}" -DBUILD_SHARED_LIBS=ON \
    -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_PNG=ON -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON
  quiet ft-build cmake --build "$B/freetype" -j"$JOBS"
  cp -L "$(ls "$B"/freetype/libfreetype.so* | head -1)" "$STAGE/lib/libfreetype.so"
  "$STRIP" --strip-unneeded "$STAGE/lib/libfreetype.so"
  endlog
}

build_openal() {
  log "OpenAL Soft $OPENAL_VERSION ($ABI)"
  git_at https://github.com/kcat/openal-soft.git "$OPENAL_VERSION" "$WORK/openal-soft"
  quiet al-configure cmake -S "$WORK/openal-soft" -B "$B/openal" "${CMAKE_ANDROID[@]}" -DALSOFT_UTILS=OFF -DALSOFT_EXAMPLES=OFF -DALSOFT_TESTS=OFF \
    -DALSOFT_INSTALL=OFF -DALSOFT_BACKEND_OPENSL=ON -DALSOFT_REQUIRE_OPENSL=ON -DALSOFT_BACKEND_WAVE=ON
  quiet al-build cmake --build "$B/openal" -j"$JOBS"
  cp -L "$(ls "$B"/openal/libopenal.so* | head -1)" "$STAGE/lib/libopenal.so"
  "$STRIP" --strip-unneeded "$STAGE/lib/libopenal.so"
  endlog
}

# ------------------------------------------------------------------------------------------------ shaderc (lwjgl-shaderc loads libshaderc.so)
build_shaderc() {
  log "shaderc $SHADERC_TAG ($ABI)"
  local S="$WORK/shaderc"
  git_at https://github.com/google/shaderc.git "$SHADERC_TAG" "$S"
  # Only the three dependencies the library needs, at the revisions pinned in shaderc's DEPS file.
  python3 - "$S/DEPS" > "$B/shaderc-deps.txt" <<'EOF'
import re, sys
t = open(sys.argv[1]).read()
v = dict(re.findall(r"'(\w+_revision)'\s*:\s*'([0-9a-f]+)'", t))
for d, repo, key in (("glslang", "KhronosGroup/glslang", "glslang_revision"),
                     ("spirv-headers", "KhronosGroup/SPIRV-Headers", "spirv_headers_revision"),
                     ("spirv-tools", "KhronosGroup/SPIRV-Tools", "spirv_tools_revision")):
    print(d, "https://github.com/" + repo + ".git", v[key])
EOF
  while read -r d url rev; do git_at "$url" "$rev" "$S/third_party/$d"; done < "$B/shaderc-deps.txt"
  cat "$B/shaderc-deps.txt"
  # SHADERC_SKIP_INSTALL=ON is not used: shaderc v2026.2 then skips SPIRV-Tools exports but keeps glslang's, which fails to configure.
  # LWJGL also binds glslang's C interface and SPIRV-Tools' C API from libshaderc.so (as upstream LWJGL builds it).
  local keep="" sym
  while read -r sym; do case "$sym" in ''|'#'*) ;; *) keep="$keep -Wl,-u,$sym" ;; esac; done < "$HERE/shaderc_extra_exports.txt"
  local rl="$B/shaderc/third_party/glslang/glslang/libglslang-default-resource-limits.a"
  quiet shaderc-configure cmake -S "$S" -B "$B/shaderc" "${CMAKE_ANDROID[@]}" -DSHADERC_SKIP_TESTS=ON -DSHADERC_SKIP_EXAMPLES=ON \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-Bsymbolic $keep $rl" \
    -DSHADERC_SKIP_COPYRIGHT_CHECK=ON -DSPIRV_SKIP_EXECUTABLES=ON -DSPIRV_SKIP_TESTS=ON \
    -DSPIRV_WERROR=OFF -DENABLE_GLSLANG_BINARIES=OFF -DGLSLANG_TESTS=OFF \
    -DSHADERC_SKIP_EXECUTABLES=ON -DBUILD_TESTING=OFF
  quiet shaderc-build-rl cmake --build "$B/shaderc" -j"$JOBS" --target glslang-default-resource-limits
  quiet shaderc-build cmake --build "$B/shaderc" -j"$JOBS" --target shaderc_shared
  cp -L "$B/shaderc/libshaderc/libshaderc_shared.so" "$STAGE/lib/libshaderc.so"
  "$STRIP" --strip-unneeded "$STAGE/lib/libshaderc.so"
  endlog
}

# ------------------------------------------------------------------------------------------------ SPIRV-Cross C API (lwjgl-spvc loads libspirv-cross.so)
build_spvc() {
  log "SPIRV-Cross $SPIRV_CROSS_TAG ($ABI)"
  git_at https://github.com/KhronosGroup/SPIRV-Cross.git "$SPIRV_CROSS_TAG" "$WORK/spirv-cross"
  # -Bsymbolic (also for shaderc): renderers such as MobileGlues embed their own glslang/SPIRV-Cross; internal calls of
  # these libraries must never bind to those copies when both are loaded in the game process.
  quiet spvc-configure cmake -S "$WORK/spirv-cross" -B "$B/spvc" "${CMAKE_ANDROID[@]}" -DSPIRV_CROSS_SHARED=ON -DSPIRV_CROSS_STATIC=OFF \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-Bsymbolic" \
    -DSPIRV_CROSS_CLI=OFF -DSPIRV_CROSS_ENABLE_TESTS=OFF -DSPIRV_CROSS_ENABLE_C_API=ON -DSPIRV_CROSS_SKIP_INSTALL=ON
  quiet spvc-build cmake --build "$B/spvc" -j"$JOBS" --target spirv-cross-c-shared
  cp -L "$(ls "$B"/spvc/libspirv-cross-c-shared.so* | head -1)" "$STAGE/lib/libspirv-cross.so"
  "$STRIP" --strip-unneeded "$STAGE/lib/libspirv-cross.so"
  endlog
}

# ------------------------------------------------------------------------------------------------ Java side (stock Maven Central jars, SHA-1 verified)
fetch_jars() {
  log "LWJGL $LWJGL_VERSION jars"
  mkdir -p "$STAGE/jars" "$WORK/jars"
  for m in $JAR_MODULES; do
    local base="https://repo1.maven.org/maven2/org/lwjgl/$m/$LWJGL_VERSION/$m-$LWJGL_VERSION.jar" f="$WORK/jars/$m-$LWJGL_VERSION.jar"
    fetch "$base" "$f"; fetch "$base.sha1" "$f.sha1"
    echo "$(cut -c1-40 "$f.sha1")  $f" | sha1sum -c --quiet -
    cp "$f" "$STAGE/jars/"
  done
  endlog
}

# ------------------------------------------------------------------------------------------------ verification
# LWJGL resolves most entry points with apiGetFunctionAddress, which throws at class initialisation when a symbol is
# missing, and binds JNI methods lazily. Both are checked here against the jars that ship in the same pack.
verify() { # stageDir
  local S="$1/lwjgl34"; NM="${NM:-$(ls "$NDK"/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm 2>/dev/null || command -v nm)}"
  python3 "$HERE/verify_exports.py" --jars "$S/jars" --lib "$S/lib" --nm "$NM"
}

package() { # stageDir assetsDir abi
  local S="$1/lwjgl34" out="$2/bundled/$3"; mkdir -p "$out/components"
  python3 "$ROOT/tools/natives/make_component.py" --abi "$3" --rev "$COMPONENT_REV" --out "$out" \
    --component "lwjgl34-android|LWJGL $LWJGL_VERSION for Android (Minecraft 26.x)|$LWJGL_VERSION|lwjgl|BSD-3-Clause (LWJGL), MIT (libffi), FTL (FreeType), LGPL-2.1 (OpenAL Soft), Apache-2.0 (shaderc, SPIRV-Tools, SPIRV-Cross), BSD-3/MIT (glslang)|LWJGL/lwjgl3@$LWJGL_VERSION, libffi $LIBFFI_VERSION, freetype $FREETYPE_TAG, openal-soft $OPENAL_VERSION, google/shaderc@$SHADERC_TAG, KhronosGroup/SPIRV-Cross@$SPIRV_CROSS_TAG|lwjgl:$LWJGL_VERSION,lib:libshaderc.so,lib:libspirv-cross.so|$S"
}

case "${1:-}" in
  build)
    OUT="$(abs_out "${3:?stage output dir}")"; setup_abi "${2:?abi}"; STAGE="$OUT/lwjgl34"; mkdir -p "$STAGE/lib"
    build_libffi; build_lwjgl; build_freetype; build_openal; build_spvc; build_shaderc; fetch_jars
    verify "$OUT"
    ls -la "$STAGE/lib" "$STAGE/jars" ;;
  verify) verify "$(abs_out "${2:?stage dir}")" ;;
  package) package "$(abs_out "${2:?stage dir}")" "$(abs_out "${3:?assets dir}")" "${4:?abi}" ;;
  # development helpers
  lwjgl) OUT="$(abs_out "${3:?}")"; setup_abi "$2"; STAGE="$OUT/lwjgl34"; mkdir -p "$STAGE/lib"; build_libffi; build_lwjgl ;;
  spvc) OUT="$(abs_out "${3:?}")"; setup_abi "$2"; STAGE="$OUT/lwjgl34"; mkdir -p "$STAGE/lib"; build_spvc ;;
  shaderc) OUT="$(abs_out "${3:?}")"; setup_abi "$2"; STAGE="$OUT/lwjgl34"; mkdir -p "$STAGE/lib"; build_shaderc ;;
  freetype) OUT="$(abs_out "${3:?}")"; setup_abi "$2"; STAGE="$OUT/lwjgl34"; mkdir -p "$STAGE/lib"; build_freetype ;;
  jars) OUT="$(abs_out "${2:?}")"; STAGE="$OUT/lwjgl34"; fetch_jars ;;
  *) echo "usage: $0 build <abi> <stage> | verify <stage> | package <stage> <assets> <abi>"; exit 2 ;;
esac
