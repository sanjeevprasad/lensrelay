import java.util.Properties
plugins {
    id("com.android.application")
}


// Local release signing material lives in android/keystore.properties, which is
// gitignored along with every keystore pattern. Without it, release builds stay
// unsigned so CI and clean checkouts keep working.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.atanx.lensrelay"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.atanx.lensrelay"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 is enabled; the uniffi-generated FFI interfaces must keep their
            // method names (resolved as Rust symbols at runtime), so proguard-
            // rules.pro keeps the generated uniffi package. The minified release
            // dex was diffed against debug to confirm every FFI symbol survives.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    val cameraXVersion = "1.6.2"

    implementation("androidx.activity:activity:1.13.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.core:core:1.19.1")
    implementation("com.google.android.material:material:1.14.0")

    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("com.swmansion.moqkit:moqkit:0.4.0")
    implementation("com.google.zxing:core:3.5.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
