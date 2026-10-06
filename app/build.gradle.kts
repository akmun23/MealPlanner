import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val localProperties = Properties().apply {
    load(rootProject.file("local.properties").inputStream())
}

android {
    namespace = "com.example.mealplanner"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.mealplanner"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "SUPABASE_URL",
            "\"${localProperties.getProperty("SUPABASE_URL")}\""
        )

        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            "\"${localProperties.getProperty("SUPABASE_PUBLISHABLE_KEY")}\""
        )
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf(
                    "androidx.**",
                    "kotlin.**",
                    "kotlinx.**"
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
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
    implementation(libs.androidx.compose.ui.tooling.preview)
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
        platform("io.github.jan-tennert.supabase:bom:3.8.0")
    )

    implementation(
        "io.github.jan-tennert.supabase:auth-kt"
    )

    implementation(
        "io.github.jan-tennert.supabase:postgrest-kt"
    )

    implementation(
        "io.github.jan-tennert.supabase:realtime-kt"
    )

    implementation(
        "io.github.jan-tennert.supabase:storage-kt"
    )

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
        "io.ktor:ktor-client-cio:3.5.1"
    )

    implementation(
        "io.coil-kt:coil-compose:2.7.0"
    )

    implementation(
        "androidx.compose.material:material-icons-extended"
    )

    testImplementation(libs.junit)

    androidTestImplementation(
        platform(libs.androidx.compose.bom)
    )

    androidTestImplementation(
        libs.androidx.compose.ui.test.junit4
    )

    androidTestImplementation(
        libs.androidx.espresso.core
    )

    androidTestImplementation(
        libs.androidx.junit
    )

    debugImplementation(
        libs.androidx.compose.ui.test.manifest
    )

    debugImplementation(
        libs.androidx.compose.ui.tooling
    )
}