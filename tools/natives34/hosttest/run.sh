#!/usr/bin/env bash
# Host test of the SDL3 bridge: builds app/src/main/cpp/cssdl3*.c for the host with mock Android/EGL plumbing,
# runs the C scenario test (sdltest.c) and loads the bridge through LWJGL's real SDL bindings (SdlLoad.java).
# Usage: run.sh <dir with lwjgl-3.4.3.jar and lwjgl-sdl-3.4.3.jar>
# EGL headers come from the NDK; -DUSE_OZONE selects their platform-neutral native types (no X11 headers needed).
# Needs: cc, JAVA_HOME (JDK 11+), ANDROID_NDK_HOME (only for the EGL/KHR headers), network for lwjgl natives-linux.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CPP="$HERE/../../../app/src/main/cpp"
JARS="$(cd "${1:?jars dir}" && pwd)"
W="${CS_HOSTTEST_WORK:-$(mktemp -d)}"; mkdir -p "$W/inc"
SYS="${ANDROID_NDK_HOME:?}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"
cp -r "$SYS/EGL" "$SYS/KHR" "$W/inc/"
INC=(-I"$HERE/shim" -I"$W/inc" -I"$CPP" -I"$CPP/third_party/SDL3/include" -I"$HERE" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux")
CFLAGS=(-O1 -g -fPIC -fvisibility=hidden -Wall -Wno-unused-parameter -Wno-misleading-indentation -DUSE_OZONE)
SRC=("$CPP/cssdl3.c" "$CPP/cssdl3_libc.c" "$CPP/cssdl3_stubs.c" "$CPP/input_queue.c" "$HERE/mock.c")

cc "${CFLAGS[@]}" -shared -o "$W/libfakegl.so" "$HERE/fakegl.c"
cc "${CFLAGS[@]}" "${INC[@]}" -o "$W/sdltest" "$HERE/sdltest.c" "${SRC[@]}" -ldl -lpthread
cc "${CFLAGS[@]}" "${INC[@]}" -shared -o "$W/libcssdl3host.so" "${SRC[@]}" -ldl -lpthread -Wl,--no-undefined
echo "== C scenario test"
CS_GL_LIB="$W/libfakegl.so" "$W/sdltest"

echo "== LWJGL bindings test"
NAT="$W/lwjgl-3.4.3-natives-linux.jar"
[ -f "$NAT" ] || curl -sfL --retry 3 -o "$NAT" https://repo1.maven.org/maven2/org/lwjgl/lwjgl/3.4.3/lwjgl-3.4.3-natives-linux.jar
CP="$JARS/lwjgl-3.4.3.jar:$JARS/lwjgl-sdl-3.4.3.jar:$NAT"
"$JAVA_HOME/bin/javac" -nowarn -cp "$CP" -d "$W" "$HERE/SdlLoad.java"
CS_GL_LIB="$W/libfakegl.so" "$JAVA_HOME/bin/java" -cp "$CP:$W" -Dorg.lwjgl.sdl.libname="$W/libcssdl3host.so" \
  -Dorg.lwjgl.system.SharedLibraryExtractPath="$W/extract" SdlLoad "$JARS/lwjgl-sdl-3.4.3.jar"
