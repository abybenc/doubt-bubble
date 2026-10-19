plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.abybencent.doubtbubble"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.abybencent.doubtbubble"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    // Fixed signing key so each new build installs over the old one
    // (no need to uninstall). Only for personal sideloading.
    signingConfigs {
        create("personal") {
            storeFile = file("doubtbubble.keystore")
            storePassword = "doubtbubble"
            keyAlias = "doubtbubble"
            keyPassword = "doubtbubble"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
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
