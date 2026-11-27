plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.foxgirlsupremacy.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.foxgirlsupremacy.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-phase0"
    }

    buildTypes {
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Live2D Cubism : Framework (module) + Core (AAR propriétaire, décodeur .moc3)
    implementation(project(":cubismFramework"))
    implementation(
        fileTree(
            mapOf(
                "dir" to "${rootDir}/CubismSdkForJava-5-r.5/Core/android",
                "include" to listOf("Live2DCubismCore.aar"),
            ),
        ),
    )
}
