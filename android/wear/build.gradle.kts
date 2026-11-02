import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// T3 (margo/docs/training-staff-spec.md): the Wear OS companion for the Galaxy Watch 8.
//
// It carries NO endpoints or secrets of its own, deliberately. Everything it observes goes to the
// PHONE over the Wearable Data Layer, and the phone forwards it to the tailnet using the credentials
// it already holds — the watch may well have no wifi and no tailnet, so a direct POST is not a
// dependable design. One ingest path, one credential, no new listener.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kumouri.margo.wear"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        // Same applicationId as the phone app: the Data Layer pairs a watch app to its phone app by
        // application id + signature, so these MUST match or nothing is delivered.
        applicationId = "com.kumouri.margo"
        // Wear OS 3+ (the Galaxy Watch 4 generation and later). The Watch 8 is far past this; the
        // floor is set by Health Services' passive UserActivityInfo, not by the target device.
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // NO appcompat, deliberately: it requires a Theme.AppCompat descendant and this is a Wear app
    // on Theme.DeviceDefault. Pairing the two crashes at setContentView (see MainActivity).
    // Health Services: passive monitoring, and the thing this whole module exists for --
    // UserActivityInfo (asleep / passive / active-exercise) derived from WRIST motion + heart rate,
    // rather than from a phone noticing it has been put down.
    implementation("androidx.health:health-services-client:1.0.0-rc02")
    // The Data Layer link to the phone.
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    // PresenceHoldService's foreground-service notification (NotificationCompat) and its
    // startForegroundService call (ContextCompat) — declared explicitly since nothing above pulls
    // androidx.core in (unlike :app, which gets it transitively via appcompat/work-runtime-ktx).
    implementation("androidx.core:core:1.13.1")
    // The OngoingActivity API — surfaces an active hold on the watch face complication / app chip,
    // not just a bare notification. Built for exactly this: a long-running signal a Wear app wants
    // to stay visibly alive while backgrounded.
    implementation("androidx.wear:wear-ongoing:1.0.0")
    // Health Services' *Async APIs return Guava ListenableFutures, and the type must be on the
    // compile classpath even though we never touch the returned future — without it,
    // `setPassiveListenerServiceAsync` fails with "Cannot access class 'ListenableFuture'".
    //
    // The full guava-android artifact, not `androidx.concurrent:concurrent-futures` (tried first —
    // it does not re-export the type) and not the `com.google.guava:listenablefuture` stub (which
    // guava itself replaces with an empty conflict-avoidance artifact). Heavier than ideal for one
    // interface; correctness on a personal sideloaded app beats shaving a couple of MB, and this is
    // what Google's own Health Services samples do.
    implementation("com.google.guava:guava:33.3.1-android")

    // JVM unit tests for the pure halves (HoldRhythm, the chunk amplitudes) — `:wear:testDebugUnitTest`,
    // run by android.yml beside `:app`'s.
    testImplementation("junit:junit:4.13.2")
}
