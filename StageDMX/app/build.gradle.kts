plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.stagedmx"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.stagedmx"
        minSdk = 26
        targetSdk = 36
        versionCode = 25
        versionName = "1.24"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    // 注：kotlinx-coroutines-android 与 lifecycle-runtime-ktx 已移除 —— 全工程 0 引用，
    // 属于死依赖（App 的异步全部走 Handler/Looper）。将来若引入协程/ViewModel 再加回来。
    testImplementation("junit:junit:4.13.2")
}
