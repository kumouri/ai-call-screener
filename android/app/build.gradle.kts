import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Endpoints + secrets are injected from local.properties (gitignored) into BuildConfig.
// The empty-string defaults keep a secretless checkout — and CI — building. See the README.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(key: String): String = localProps.getProperty(key, "")

android {
    namespace = "com.kumouri.margo"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.kumouri.margo"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        // Blocklist sync (BlocklistSyncWorker) + health feed (HealthSyncWorker) endpoints.
        buildConfigField("String", "BLOCKLIST_URL", "\"${prop("BLOCKLIST_URL")}\"")
        buildConfigField("String", "BLOCKLIST_SECRET", "\"${prop("BLOCKLIST_SECRET")}\"")
        buildConfigField("String", "HEALTH_INGEST_URL", "\"${prop("HEALTH_INGEST_URL")}\"")
        buildConfigField("String", "HEALTH_INGEST_TOKEN", "\"${prop("HEALTH_INGEST_TOKEN")}\"")
        // Presence feed (geofence/activity/sleep events -> desktop /presence-ingest).
        buildConfigField("String", "PRESENCE_INGEST_URL", "\"${prop("PRESENCE_INGEST_URL")}\"")
        buildConfigField("String", "PRESENCE_INGEST_TOKEN", "\"${prop("PRESENCE_INGEST_TOKEN")}\"")
        // T3 wrist acks. Same listener, same token — a separate URL only so the ack route can be
        // pointed elsewhere without moving the presence feed. Blank falls back to the presence URL.
        buildConfigField("String", "ACK_INGEST_URL", "\"${prop("ACK_INGEST_URL")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // --- Compose + Material3 -------------------------------------------------------------------
    // The BOM is what keeps the compose-* artifacts on one consistent version set; only it carries a
    // number, deliberately, so a later bump is one line rather than six that can drift apart.
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // The Samsung-export folder watcher (ExportWatchWorker) lists the granted tree through
    // DocumentFile. Declared explicitly rather than relied on transitively: nothing above pulls it.
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // T3: the Data Layer link to the :wear module.
    implementation("com.google.android.gms:play-services-wearable:19.0.0")

    // A real org.json impl for JVM unit tests — the bundled android.jar stubs org.json.JSONObject
    // to throw ("not mocked"), and it resolves ahead of android.jar on the unit-test runtime
    // classpath, so parseShipResponse's JSONObject parsing is exercised for real.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
