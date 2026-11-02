// Top-level build file. Plugin versions are declared here and applied in :app.
// See app/build.gradle.kts for the module config and the README for setup.
plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    // Compose's compiler ships WITH Kotlin since 2.0, so this version must track the Kotlin one above
    // exactly — a mismatch fails the build with a version-compatibility error rather than anything
    // that names Compose.
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}
