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
        versionCode = 9
        versionName = "1.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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
            // AGP 9.4: optimize code (R8) and resources for the actual
            // production APK/AAB. The benchmark variant inherits this.
            optimization {
                enable = true
            }
            // Include available native debugging metadata in the Play AAB.
            // Pre-stripped third-party libraries cannot regain missing symbols.
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
        create("benchmark") {
            initWith(getByName("release"))
            // The benchmark must match release's R8 settings but use a test
            // signature. Never install over a Play-signed production app.
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
        create("unoptimized") {
            initWith(getByName("release"))
            // Comparison artifact only: identical release source/dependencies
            // without shrinking, used to quantify R8's DEX reduction.
            // This variant must never be uploaded to Play Console.
            optimization {
                enable = false
            }
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

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        allWarningsAsErrors.set(true)
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

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(
        libs.androidx.compose.ui.tooling
    )
}
