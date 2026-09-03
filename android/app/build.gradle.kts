plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

val androidKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH").orEmpty()
val androidKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD").orEmpty()
val androidKeyAlias = System.getenv("ANDROID_KEY_ALIAS").orEmpty()
val androidKeyPassword = System.getenv("ANDROID_KEY_PASSWORD").orEmpty()
val hasReleaseSigning = androidKeystorePath.isNotBlank() &&
    androidKeystorePassword.isNotBlank() && androidKeyAlias.isNotBlank() && androidKeyPassword.isNotBlank()

android {
    namespace = "com.codexatlas.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.codexatlas.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 31
        versionName = "0.1.35"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(androidKeystorePath)
                storePassword = androidKeystorePassword
                keyAlias = androidKeyAlias
                keyPassword = androidKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin { jvmToolchain(17) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    testImplementation("junit:junit:4.13.2")
}
