plugins {
    alias(libs.plugins.android.application)
    // AGP 9 起 Kotlin 支持内置于 AGP（不再应用 org.jetbrains.kotlin.android，
    // 它也与新 DSL 不兼容）。下面两个是 Kotlin 编译器插件，仍需显式应用。
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.illagercpr.landrop"
    // Compose BOM 2026.09 起，androidx.core 等依赖强制要求 compileSdk >= 37
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.illagercpr.landrop"
        // 按确认结论：目标机型 Android 13+，因此 minSdk 直接取 33，
        // 省掉通知权限、存储权限、前台服务类型等大量旧版本兼容分支。
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        resourceConfigurations += listOf("zh", "en")
    }

    buildTypes {
        debug {
            // 与正式包并存，便于同时安装
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // 说明：内置 Kotlin 下 kotlin.compilerOptions.jvmTarget 默认取
    // android.compileOptions.targetCompatibility，无需重复设置。

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }
}

ksp {
    // Room 导出 schema，便于后续写迁移与人工比对
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // 扫码配对：CameraX 取 YUV 流 + zxing 解码。camera2 实现与 lifecycle 绑定必须一起引
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    // 协议一致性测试跑在 JVM 上，不需要设备
    testImplementation(libs.junit)
}
