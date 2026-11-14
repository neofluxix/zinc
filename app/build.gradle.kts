plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.interpreter.live"
    compileSdk = 34

    signingConfigs {
        create("zinc") {
            storeFile = file("zinc.keystore")
            storePassword = "zincapp"
            keyAlias = "zinc"
            keyPassword = "zincapp"
            storeType = "pkcs12"
        }
    }

    defaultConfig {
        applicationId = "com.interpreter.live"   // change this if you publish under your own name
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("zinc") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("zinc")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
}
