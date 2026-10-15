plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Filament: Google's physically based rendering engine (Vulkan backend), and its glTF loader.
val filamentVersion = "1.53.4"

android {
    namespace = "com.titovpower.racegame"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.titovpower.racegame"
        minSdk = 24
        targetSdk = 35
        // CI build number, so each new APK installs as an update over the previous one.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.$versionCode"
        ndk {
            // Filament ships native code; phones are ARM.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // A fixed debug key checked into the repo: every CI build is signed with the same key,
        // so Android accepts it as an update. (CI runners otherwise generate a new key each run.)
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK installs directly for testing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.google.android.filament:filament-android:$filamentVersion")
    implementation("com.google.android.filament:gltfio-android:$filamentVersion")
    testImplementation("junit:junit:4.13.2")
}
