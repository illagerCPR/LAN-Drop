#!/usr/bin/env bash
# LAN-Drop 开发环境变量（可 source；同时适用于非交互式 shell）
#
#   source scripts/dev-env.sh
#
# 说明：本机 ~/.bashrc 开头有 `case $- in *i*) ;; *) return;; esac`，
# 非交互式 shell（含 CI、Agent 的 bash -c）不会加载它，因此单独抽出本文件。

# ---- JDK 21（用户级 Temurin，位于 ~/.gradle/jdks，无需 root）----
LAN_DROP_JDK="${LAN_DROP_JDK:-$HOME/.gradle/jdks/jdk-21.0.12.1+1}"
if [ -x "$LAN_DROP_JDK/bin/java" ]; then
    export JAVA_HOME="$LAN_DROP_JDK"
    case ":$PATH:" in
        *":$JAVA_HOME/bin:"*) ;;
        *) export PATH="$JAVA_HOME/bin:$PATH" ;;
    esac
fi

# ---- Android SDK（用户级安装，无 root / 无 snap）----
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [ -d "$ANDROID_HOME/platform-tools" ]; then
    case ":$PATH:" in
        *":$ANDROID_HOME/platform-tools:"*) ;;
        *) export PATH="$ANDROID_HOME/platform-tools:$PATH" ;;
    esac
fi
if [ -d "$ANDROID_HOME/cmdline-tools/latest/bin" ]; then
    case ":$PATH:" in
        *":$ANDROID_HOME/cmdline-tools/latest/bin:"*) ;;
        *) export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH" ;;
    esac
fi

# ---- Gradle 发行版（腾讯云镜像解压所得，供生成/刷新 gradle wrapper 使用）----
# 注意：项目构建以 android/gradle/wrapper/gradle-wrapper.properties 为准，
# 这里只是提供一个可用的 gradle 命令，便于执行 `gradle wrapper` 之类的引导操作。
LAN_DROP_GRADLE="${LAN_DROP_GRADLE:-$HOME/.local/share/gradle/gradle-9.7.1}"
if [ -x "$LAN_DROP_GRADLE/bin/gradle" ]; then
    case ":$PATH:" in
        *":$LAN_DROP_GRADLE/bin:"*) ;;
        *) export PATH="$LAN_DROP_GRADLE/bin:$PATH" ;;
    esac
fi

export LANG="${LANG:-zh_CN.UTF-8}"
