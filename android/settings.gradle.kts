// 依赖源策略（实测于本机 WSL2，2026-09-29）：
//   dl.google.com 直连        ≈ 2.4 ~ 2.8 MB/s   → 首选
//   阿里云 Maven Central 镜像 ≈ 2.5 MB/s         → 次选
//   Maven Central 官方        ≈ 87 KB/s          → 仅兜底
//   阿里云 Google 镜像        ≈ 70 KB/s          → 仅作 google() 抖动时的兜底
//
// CI（GitHub 托管 runner 在海外）官方源优先：阿里云镜像对海外 IP 偶发超时/限速，
// 曾致 KSP 插件在 CI 上解析失败（本机有 Gradle 缓存掩盖了这一问题）。
// 判据用 GitHub Actions 注入的 CI 环境变量，不引入额外配置。
// 注意：pluginManagement{} 与顶层脚本分属不同编译上下文，顶层 val 在其中不可见，
// 因此判据在各块内各自声明。
pluginManagement {
    val officialReposFirst = System.getenv("CI") != null
    repositories {
        if (officialReposFirst) {
            google()
            mavenCentral()
            gradlePluginPortal()
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        } else {
            google()
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val officialReposFirst = System.getenv("CI") != null
    repositories {
        if (officialReposFirst) {
            google()
            mavenCentral()
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        } else {
            google()
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
            mavenCentral()
        }
    }
}

rootProject.name = "LAN-Drop"
include(":app")
