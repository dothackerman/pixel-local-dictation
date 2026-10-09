plugins {
    id("com.android.application")
}

android {
    namespace = "ch.og.dictation"
    compileSdk { version = release(36) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "ch.og.dictation"
        minSdk = 36
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
}
