import java.io.BufferedReader

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/** 从环境变量读取固定签名信息（keystore 路径/密码/alias），用于稳定签名。
 *  缺省时回退到 AGP 自动生成的 debug keystore（仅保证可编译，签名不稳定）。
 */
val stableStoreFile = System.getenv("MYTV_KEYSTORE_PATH").orEmpty().takeIf { it.isNotEmpty() }
val stableStorePwd = System.getenv("MYTV_KEYSTORE_PASSWORD").orEmpty()
val stableKeyAlias = System.getenv("MYTV_KEY_ALIAS").orEmpty()
val stableKeyPwd = System.getenv("MYTV_KEY_PASSWORD").orEmpty()

android {
    namespace = "com.lizongying.mytv0"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lizongying.mytv0"
        minSdk = 21
        targetSdk = 35
        versionCode = getVersionCode()
        versionName = getVersionName()
    }

    buildFeatures {
        viewBinding = true
    }

    signingConfigs {
        if (stableStoreFile != null && stableStorePwd.isNotEmpty() && stableKeyAlias.isNotEmpty() && stableKeyPwd.isNotEmpty()) {
            create("stableMytv") {
                storeFile = file(stableStoreFile)
                storePassword = stableStorePwd
                keyAlias = stableKeyAlias
                keyPassword = stableKeyPwd
            }
        }
    }

    buildTypes {
        debug {
            // 固定签名：避免每次新建 Docker 容器后 AGP 重新生成 debug keystore 导致签名变化
            signingConfig = signingConfigs.findByName("stableMytv")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        // Flag to enable support for the new language APIs
        // For AGP 4.1+
        isCoreLibraryDesugaringEnabled = true

        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

fun getTag(): String {
    return try {
        val process = Runtime.getRuntime().exec("git describe --tags --always")
        process.waitFor()
        process.inputStream.bufferedReader().use(BufferedReader::readText).trim().removePrefix("v")
    } catch (_: Exception) {
        ""
    }
}

fun getVersionCode(): Int {
    return try {
        val arr = (getTag().replace(".", " ").replace("-", " ") + " 0").split(" ")
        arr[0].toInt() * 16777216 + arr[1].toInt() * 65536 + arr[2].toInt() * 256 + arr[3].toInt()
    } catch (_: Exception) {
        1
    }
}

fun getVersionName(): String {
    return getTag().ifEmpty {
        "0.0.0-1"
    }
}

dependencies {
    // For AGP 7.4+
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.media3.ui)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.datasource.rtmp)

    implementation(libs.nanohttpd)
    implementation(libs.gua64)
    implementation(libs.zxing)
    implementation(libs.glide)

    implementation(libs.gson)
    implementation(libs.okhttp)

    implementation(libs.core.ktx)
    implementation(libs.coroutines)

    implementation(libs.constraintlayout)
    implementation(libs.appcompat)
    implementation(libs.recyclerview)
    implementation(libs.lifecycle.viewmodel)

    implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
}