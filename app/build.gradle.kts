import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

val rawSupabaseUrl: String? = localProperties.getProperty("SUPABASE_URL")
val supabaseUrl = if (!rawSupabaseUrl.isNullOrBlank() && rawSupabaseUrl != "null") {
    rawSupabaseUrl
} else {
    "https://placeholder.supabase.co"
}

val rawSupabaseKey: String? = localProperties.getProperty("SUPABASE_PUBLISHABLE_KEY")
    ?: localProperties.getProperty("SUPABASE_ANON_KEY")
    ?: localProperties.getProperty("SUPABASE_KEY")

val supabaseKey = if (!rawSupabaseKey.isNullOrBlank() && rawSupabaseKey != "null") {
    rawSupabaseKey
} else {
    "placeholder_key"
}

android {
    namespace = "com.example.mealplanner"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mealplan.mealplanner"
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "1.2.3"

        buildConfigField(
            "String",
            "SUPABASE_URL",
            "\"$supabaseUrl\""
        )

        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            "\"$supabaseKey\""
        )
    }

    buildTypes {
        release {
            // Stability hotfix: re-enable release shrinking only after a
            // physical-device startup test with a captured crash trace.
            optimization {
                enable = false
            }
        }
        create("benchmark") {
            initWith(getByName("release"))
            // Evaluate R8's optimized DEX and resource shrinking before
            // restoring it to production. The existing release build stays
            // non-minified until a real-device launch + local/cloud flow test.
            optimization {
                enable = true
            }
            // Non-debuggable for realistic startup/frame tracing; do NOT
            // install over a release-signed app (different signing keys).
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true

        // Required because we create BuildConfig.SUPABASE_URL etc.
        buildConfig = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.androidx.room.compiler)

    /*
     * SUPABASE
     *
     * BOM keeps all Supabase modules on the same version.
     */
    implementation(
        platform(libs.bom)
    )

    implementation(libs.supabaseAuth)
    implementation(libs.supabasePostgrest)
    implementation(libs.supabaseRealtime)
    implementation(libs.supabaseStorage)

    /*
     * KTOR
     *
     * IMPORTANT:
     * Use CIO rather than ktor-client-android.
     *
     * The normal Android Ktor engine does NOT support WebSockets,
     * but Supabase Realtime requires WebSockets.
     */
    implementation(
        libs.io.ktor.ktor.client.cio
    )

    implementation(
        libs.coil.compose
    )

    implementation(libs.composeMaterialIconsExtended)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.robolectric)

    debugImplementation(
        libs.androidx.compose.ui.tooling
    )
}
