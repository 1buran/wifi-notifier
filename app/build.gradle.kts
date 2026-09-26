import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing lives outside the repository: keystore.properties is
// gitignored and points at the keystore in ~/.config/wifi-notifier. When the
// file is absent (a fresh clone, F-Droid's own build server) the release build
// stays unsigned instead of failing.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.buran.wifinotifier"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.buran.wifinotifier"
        minSdk = 33
        targetSdk = 36
        versionCode = 3
        // Kept in step with the git tag: F-Droid matches v1.0.0 against 1.0.0
        // to notice a new release.
        versionName = "1.0.2"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
