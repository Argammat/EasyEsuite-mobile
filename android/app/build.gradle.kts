plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** Gradle property, else environment variable, else "" — for build-time secrets that must not be committed. */
fun prop(gradleName: String, envName: String): String =
    (project.findProperty(gradleName) as String?)?.trim() ?: System.getenv(envName)?.trim() ?: ""

android {
    namespace = "com.easyesuite.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.easyesuite.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "API_ROOT", "\"https://api-new.easyesuite.com/\"")
        buildConfigField("String", "DEFAULT_TENANT", "\"\"")
        // Firebase / Identity Platform sign-in (the backend expects a Firebase ID token as Bearer).
        // Leave the key empty to use the backend-proxied auth/login/ flow instead. Supply via
        //   -Peasyesuite.firebaseApiKey=... / -Peasyesuite.firebaseTenantId=...   or
        //   EASYESUITE_FIREBASE_API_KEY / EASYESUITE_FIREBASE_TENANT_ID environment variables.
        buildConfigField("String", "FIREBASE_API_KEY", "\"${prop("easyesuite.firebaseApiKey", "EASYESUITE_FIREBASE_API_KEY")}\"")
        buildConfigField("String", "FIREBASE_TENANT_ID", "\"${prop("easyesuite.firebaseTenantId", "EASYESUITE_FIREBASE_TENANT_ID")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coil.compose)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.accompanist.permissions)

    implementation(libs.okhttp.logging)

    testImplementation(libs.junit)
}
