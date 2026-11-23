plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val davixStoreFile = System.getenv("DAVIX_SIGNING_STORE_FILE")
val davixStorePassword = System.getenv("DAVIX_SIGNING_STORE_PASSWORD")
val davixKeyAlias = System.getenv("DAVIX_SIGNING_KEY_ALIAS")
val davixKeyPassword = System.getenv("DAVIX_SIGNING_KEY_PASSWORD")
val hasDavixSigning = listOf(davixStoreFile, davixStorePassword, davixKeyAlias, davixKeyPassword).all { !it.isNullOrBlank() }

// CI supplies monotonically increasing values from the last successfully published manifest.
// Local builds retain the latest checked-in fallback version.
val extractorVersionCode = System.getenv("DAVIX_EXTRACTOR_VERSION_CODE")?.toIntOrNull() ?: 15
val extractorVersionName = System.getenv("DAVIX_EXTRACTOR_VERSION_NAME") ?: "1.4.7"


android {
    namespace = "it.davidealpegiani.skyunoextractor"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.davidealpegiani.skyunoextractor"
        minSdk = 24
        targetSdk = 35
        versionCode = extractorVersionCode
        versionName = extractorVersionName
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
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-cast:1.11.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
}
