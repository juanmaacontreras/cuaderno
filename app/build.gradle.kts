import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The signing key lives outside the repo (~/.config/cuaderno/signing). Every APK
// must carry the same signature or Android refuses to install it as an update.
// Without the key the build still works, signed with the throwaway debug key.
val signingDir = File(System.getProperty("user.home"), ".config/cuaderno/signing")
val signingProps = Properties().apply {
    val file = File(signingDir, "signing.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.cuaderno.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.cuaderno.app"
        minSdk = 29  // low-latency drawing needs it
        targetSdk = 35
        versionCode = (project.findProperty("vcode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("vname") as String?) ?: "dev"
    }

    signingConfigs {
        if (signingProps.containsKey("storePassword")) {
            create("app") {
                storeFile = File(signingDir, "cuaderno.jks")
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        val key = signingConfigs.findByName("app")
        getByName("debug") {
            if (key != null) signingConfig = key
        }
        release {
            isMinifyEnabled = false
            if (key != null) signingConfig = key
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":kit"))
    implementation("androidx.core:core-ktx:1.16.0")

    // Low-latency drawing: the stroke in progress goes to the front buffer (page/PageView.kt).
    implementation("androidx.graphics:graphics-core:1.0.4")

    // Exporting a notebook with an imported PDF keeps the PDF as it was (its text stays text)
    // and adds the ink on top as vectors: PdfBox, Apache 2.0, the Android port (PdfExport.kt).
    // Without BouncyCastle (14 MB): it only serves PDFs locked with certificates. Password and
    // permission locks, the usual ones, go through Java's own ciphers.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") { exclude(group = "org.bouncycastle") }

    testImplementation("junit:junit:4.13.2")
    // Draws the real screens to PNG without a device (tools/screens.sh).
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// tools/screens.sh says where the PNGs go; without it ScreensTest skips itself.
tasks.withType<Test> {
    systemProperty("cuaderno.screens", System.getenv("CUADERNO_SCREENS") ?: "")
    outputs.upToDateWhen { System.getenv("CUADERNO_SCREENS").isNullOrEmpty() }
}
