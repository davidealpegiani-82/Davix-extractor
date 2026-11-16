plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val davixStoreFile = System.getenv("DAVIX_SIGNING_STORE_FILE")
val davixStorePassword = System.getenv("DAVIX_SIGNING_STORE_PASSWORD")
val davixKeyAlias = System.getenv("DAVIX_SIGNING_KEY_ALIAS")
val davixKeyPassword = System.getenv("DAVIX_SIGNING_KEY_PASSWORD")
val hasDavixSigning = listOf(davixStoreFile, davixStorePassword, davixKeyAlias, davixKeyPassword).all { !it.isNullOrBlank() }


android {
    namespace = "it.davidealpegiani.skylite"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.davidealpegiani.skylite"
        minSdk = 24
        targetSdk = 35
        versionCode = 12
        versionName = "1.2.3"
    }

    signingConfigs {
        if (hasDavixSigning) {
            create("davixRelease") {
                storeFile = file(davixStoreFile!!)
                storePassword = davixStorePassword!!
                keyAlias = davixKeyAlias!!
                keyPassword = davixKeyPassword!!
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (hasDavixSigning) {
                signingConfig = signingConfigs.getByName("davixRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
}
