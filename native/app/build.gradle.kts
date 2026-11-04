import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 复用 WebView 壳那份发布签名（同一个开发者、同一个备份位置，少一个会丢的东西）。
// 文件不入库：`../app/android/keystore.properties` 与 `pideck-release.keystore` 都在 .gitignore 里。
val keystorePropsFile = rootProject.file("../app/android/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val releaseKeystore = rootProject.file("../app/android/" + (keystoreProps.getProperty("storeFile") ?: "pideck-release.keystore"))
val hasReleaseKeystore = keystorePropsFile.exists() && releaseKeystore.exists()

android {
    namespace = "dev.pideck.nativeapp"
    compileSdk = 35

    defaultConfig {
        // 开发期与 WebView 壳共存（top.caoayu.pideck / .debug 都不冲突）
        // 正式包顶替原 WebView 壳的身份（同一张 pideck-mobile 证书，所以可以直接覆盖升级）。
        // debug 包带 .native 后缀，与已装在手机上的调试版同一个 id，继续覆盖升级。
        applicationId = "top.caoayu.pideck"
        minSdk = 26
        targetSdk = 35
        // 原生版接管 PiDeck 之后从 0.5.0 重新起线（versionCode = major*100+minor*10+patch）；
        // 必须高于壳最后的 412，否则 Android 拒绝覆盖安装。
        versionCode = 503
        versionName = "0.5.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = keystoreProps.getProperty("storePassword", "")
                keyAlias = keystoreProps.getProperty("keyAlias", "pideck")
                keyPassword = keystoreProps.getProperty("keyPassword", "")
            }
        }
    }

    buildTypes {
        debug {
            // 调试包留在 top.caoayu.pideck.native 上（与手机里已装的调试版同一个 id，继续覆盖升级），
            // 正式包才用 top.caoayu.pideck 顶替旧壳，两者互不干扰。
            applicationIdSuffix = ".native"
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.zxing.core)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.content.negotiation)
    testImplementation(libs.ktor.serialization.json)
}
