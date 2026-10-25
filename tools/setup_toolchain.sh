#!/bin/bash
# Reproducible toolchain bootstrap for CS Launcher Pro (Linux x86_64 host)
set -euo pipefail
T=/opt/cs-toolchain
sudo mkdir -p $T && sudo chown $(id -u):$(id -g) $T
cd $T
if [ ! -d jdk17 ]; then
  curl -fsSL -o jdk.tgz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir jdk17 && tar -xzf jdk.tgz -C jdk17 --strip-components=1 && rm jdk.tgz
fi
echo "JDK17 ok"
if [ ! -d sdk/cmdline-tools/latest ]; then
  curl -fsSL -o clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  mkdir -p sdk/cmdline-tools && unzip -q clt.zip -d sdk/cmdline-tools && mv sdk/cmdline-tools/cmdline-tools sdk/cmdline-tools/latest && rm clt.zip
fi
export JAVA_HOME=$T/jdk17 ANDROID_HOME=$T/sdk
(yes | sdk/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null) || true
sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools" "cmake;3.22.1" "ndk;26.3.11579264" | grep -v '^\[' | tail -5
echo "SDK ok"
if [ ! -d gradle-8.7 ]; then
  curl -fsSL -o g.zip https://services.gradle.org/distributions/gradle-8.7-bin.zip && unzip -q g.zip && rm g.zip
fi
echo "TOOLCHAIN_DONE"
