plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
}
val releaseStorePath = System.getenv("HEARTHSONG_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("HEARTHSONG_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("HEARTHSONG_KEY_ALIAS")
val releaseKeyPassword = System.getenv("HEARTHSONG_KEY_PASSWORD")

android {
    namespace = "com.hearthsong.app"
    compileSdk = 35
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    signingConfigs {
        if (!releaseStorePath.isNullOrBlank() && !releaseStorePassword.isNullOrBlank() && !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseStorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (!releaseStorePath.isNullOrBlank() && !releaseStorePassword.isNullOrBlank() && !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    defaultConfig {
        applicationId = "com.hearthsong.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.5.7"
    }
}
