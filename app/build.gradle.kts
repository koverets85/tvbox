plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ks: String? = findProperty("keystore") as String?

android {
    namespace = "io.github.koverets85.tvbox"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.github.koverets85.tvbox"
        minSdk = 26
        targetSdk = 34
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "1.0.dev"
    }
    signingConfigs {
        if (ks != null) create("release") {
            storeFile = file(ks)
            storeType = "pkcs12"
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = "tvbox"
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (ks != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
