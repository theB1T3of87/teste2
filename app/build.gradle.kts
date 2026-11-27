plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.foxgirlsupremacy.companion"
    compileSdk = 35
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.foxgirlsupremacy.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2-native-cubism"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake { cppFlags += listOf("-std=c++14", "-fexceptions", "-frtti") }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake { path = file("CMakeLists.txt"); version = "3.22.1" }
    }

    androidResources { noCompress += listOf("task") }

    sourceSets.getByName("main").assets.srcDirs(
        "src/main/assets",
        "../FoxGirlSupremacy/CubismSdkForNative-5-r.5/Samples/OpenGL/Shaders/StandardES",
        "../FoxGirlSupremacy/CubismSdkForNative-5-r.5/Framework/src/Rendering/OpenGL/Shaders/StandardES",
    )

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

extra["ASSET_DIR"] = "$projectDir/src/main/assets"
apply(from = "download_tasks.gradle.kts")

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("com.google.mediapipe:tasks-vision:1.2.2")
}
