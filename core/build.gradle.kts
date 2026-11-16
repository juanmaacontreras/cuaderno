// The model and the file format: plain Kotlin, no Android, so its tests run in
// seconds on any machine. Most of the app's logic belongs here.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // JSON for the notebook manifest, through its tree API (no compiler plugin).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    // Set only to write the fixture of a new format version (see FormatTest).
    systemProperty("cuaderno.fixture", System.getenv("CUADERNO_FIXTURE") ?: "")
}
