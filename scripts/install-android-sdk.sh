#!/usr/bin/env bash
# LAN-Drop：用户级安装 Android SDK
#   - 不使用 root，不使用 snap
#   - 安装到 ~/Android/Sdk（可用 ANDROID_SDK_ROOT 覆盖）
#   - JDK 使用已有的 ~/.gradle/jdks 下的 Temurin 21
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
JDK_HOME="${JDK_HOME:-$HOME/.gradle/jdks/jdk-21.0.12.1+1}"
CACHE_DIR="$HOME/.cache/lan-drop"
CLT_BUILD="${CLT_BUILD:-16111833}"
PLATFORM="${PLATFORM:-android-36}"
BUILD_TOOLS="${BUILD_TOOLS:-36.0.0}"

log() { printf '[android-sdk] %s\n' "$*"; }
die() { printf '[android-sdk][ERROR] %s\n' "$*" >&2; exit 1; }

[ -x "$JDK_HOME/bin/java" ] || die "未找到 JDK：$JDK_HOME/bin/java"
export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
log "JAVA_HOME=$JAVA_HOME ($("$JAVA_HOME/bin/java" -version 2>&1 | head -1))"

mkdir -p "$SDK_ROOT/cmdline-tools" "$CACHE_DIR"

# 1) 引导 cmdline-tools
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  ZIP="$CACHE_DIR/commandlinetools-linux-${CLT_BUILD}_latest.zip"
  if [ ! -s "$ZIP" ]; then
    log "下载 cmdline-tools（$CLT_BUILD）…"
    curl -fSL --retry 3 --retry-delay 2 -o "$ZIP.part" \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CLT_BUILD}_latest.zip"
    mv "$ZIP.part" "$ZIP"
  fi
  log "解压 cmdline-tools…"
  rm -rf "$SDK_ROOT/cmdline-tools/latest" "$SDK_ROOT/cmdline-tools/cmdline-tools"
  unzip -q "$ZIP" -d "$SDK_ROOT/cmdline-tools"
  mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
fi
[ -x "$SDKMANAGER" ] || die "sdkmanager 不可执行：$SDKMANAGER"

# 2) 接受许可（失败不致命，逐包再确认）
log "接受 SDK 许可…"
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null 2>&1 || true

# 3) 安装所需组件
log "安装 platform-tools / platforms;$PLATFORM / build-tools;$BUILD_TOOLS …"
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --install \
  "platform-tools" "platforms;$PLATFORM" "build-tools;$BUILD_TOOLS" 2>&1 \
  | grep -vE '^\[=*' | tail -20 || true

# 4) 校验
log "已安装组件："
"$SDKMANAGER" --sdk_root="$SDK_ROOT" --list_installed 2>/dev/null | sed 's/^/    /'

[ -x "$SDK_ROOT/platform-tools/adb" ] || die "adb 未安装成功"
log "adb: $("$SDK_ROOT/platform-tools/adb" version | head -1)"
log "完成。SDK_ROOT=$SDK_ROOT"
