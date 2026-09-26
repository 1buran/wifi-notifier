plugins {
    id("com.android.application")
}

android {
    namespace = "com.buran.wifinotifier"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.buran.wifinotifier"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
}

dependencies {
    // No third-party libraries on purpose: the Android SDK and the Kotlin
    // standard library are all this app needs.
}
