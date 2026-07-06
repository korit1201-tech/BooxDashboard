plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.korit.booxdashboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.korit.booxdashboard"
        minSdk = 30
        targetSdk = 30
        versionCode = 3
        versionName = "0.2.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // 系統相片選擇器（PickMultipleVisualMedia），可從 Google 相簿等 App 多選照片
    implementation("androidx.activity:activity-ktx:1.9.3")
    // SAF 選取的照片資料夾用 DocumentFile 存取
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Onyx E Ink 刷新控制 SDK（EpdController / EpdDeviceManager）
    implementation("com.onyx.android.sdk:onyxsdk-device:1.1.11")
}
