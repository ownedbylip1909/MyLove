import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }
        ?.inputStream()?.use { load(it) }
}

android {
    namespace = "pl.ownedbylip.mylove"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "pl.ownedbylip.mylove"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "SUPABASE_URL",
            "\"${localProperties.getProperty("supabase.url", "")}\""
        )
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            "\"${localProperties.getProperty("supabase.publishableKey", "")}\""
        )
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
