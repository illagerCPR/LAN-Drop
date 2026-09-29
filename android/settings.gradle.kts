// 依赖源策略（实测于本机 WSL2，2026-09-29）：
//   dl.google.com 直连        ≈ 2.4 ~ 2.8 MB/s   → 首选
//   阿里云 Maven Central 镜像 ≈ 2.5 MB/s         → 次选
//   Maven Central 官方        ≈ 87 KB/s          → 仅兜底
//   阿里云 Google 镜像        ≈ 70 KB/s          → 仅作 google() 抖动时的兜底
pluginManagement {
    repositories {
        google()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
}

rootProject.name = "LAN-Drop"
include(":app")
