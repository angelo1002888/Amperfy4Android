import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// Release signing: create keystore.properties in the project root with the fields
// storeFile (path relative to the project root), storePassword, keyAlias and keyPassword.
// When the file is absent, release builds fall back to the debug signing config so the
// project still builds out of the box (such APKs are for local testing only).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystorePropertiesFile.exists()
val keystoreProperties = Properties()
if (hasReleaseKeystore) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

// Load local properties for debug defaults
val localProperties = Properties()
rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { localProperties.load(it) }

android {
    namespace = "com.amperfy"
    compileSdk = 35

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"].toString())
                storePassword = keystoreProperties["storePassword"].toString()
                keyAlias = keystoreProperties["keyAlias"].toString()
                keyPassword = keystoreProperties["keyPassword"].toString()
            }
        }
    }

    defaultConfig {
        applicationId = "io.github.angelo1002888.amperfy"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // 登录页预填字段的安全默认值（release 使用；LoginScreen 引用这些字段，
        // 若只在 debug 定义会导致 release 编译失败）。
        // debug buildType 会用 local.properties 的开发值覆盖——开发凭证绝不进 release 包
        buildConfigField("String", "DEBUG_SERVER_URL", "\"https://\"")
        buildConfigField("String", "DEBUG_USERNAME", "\"\"")
        buildConfigField("String", "DEBUG_PASSWORD", "\"\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "DEBUG_SERVER_URL", "\"${localProperties.getProperty("debug.serverUrl", "https://")}\"")
            buildConfigField("String", "DEBUG_USERNAME", "\"${localProperties.getProperty("debug.username", "")}\"")
            buildConfigField("String", "DEBUG_PASSWORD", "\"${localProperties.getProperty("debug.password", "")}\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // mockk-android 传递引入 junit-jupiter 5.x，6 个 jar 各带一份许可文件，
            // androidTest 合并 java resources 时冲突
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
    }
}

// Room Gradle Plugin：导出 Schema JSON 到 app/schemas/
// Room schema JSON is the migration baseline and must be committed.
room {
    schemaDirectory("$projectDir/schemas")
}

// KGP 2.2 起 kotlinOptions 弃用，迁移到 compilerOptions DSL
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // 采纳 Kotlin 未来语义：构造参数上的注解同时应用到 param 与 field，
        // 消除 KT-73255 告警（对 Hilt qualifier 注解无行为影响）
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // Hilt Dependency Injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    androidTestImplementation(libs.androidx.room.testing)

    // Retrofit & OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    
    // Coil for Image Loading
    implementation(libs.coil.compose)
    
    // Media3 (ExoPlayer) for Audio Playback
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    
    // Security (for encrypted credentials storage)
    implementation(libs.androidx.security.crypto)

    // Splash Screen
    implementation(libs.androidx.core.splashscreen)

    // Pinyin4j for Chinese to Pinyin conversion
    implementation(libs.pinyin4j)

    // Testing
    testImplementation(libs.junit)
    // 单测（仅测试依赖）：Scrobble 状态机用虚拟时间驱动 + 构造依赖 mock
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // Repository 边界合同测试（mock 构造依赖 + suspend 测试），版本与 JVM 侧一致
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
