plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    namespace = "se.fpq.remote.test"
    compileSdk = 34

    defaultConfig {
        applicationId = "se.fpq.remote.test"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("keystore/example.keystore")
            storePassword = "example"
            keyAlias = "example_alias"
            keyPassword = "example"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
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


}

dependencies {
    // AndroidX
    implementation("androidx.appcompat:appcompat:1.3.1")
    
    // Spotify App Remote SDK
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    
    // Gson (required by Spotify SDK)
    implementation("com.google.code.gson:gson:2.8.9")
    
    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    
    // Spotify Authentication Library
    implementation("com.spotify.android:auth:1.2.3")
}

