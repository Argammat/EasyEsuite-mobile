import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Per-environment configuration that must not be committed (the repository is public): an untracked
 * `android/easyesuite.properties` (see easyesuite.properties.example), else a Gradle property, else an
 * environment variable, else "" (= production REST API, Firestore screens disabled).
 */
val envProps = Properties().apply {   // `java.util.Properties` would resolve `java` to the Gradle extension here
    val f = rootProject.file("easyesuite.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(gradleName: String, envName: String): String =
    envProps.getProperty(gradleName)?.trim()
        ?: (project.findProperty(gradleName) as String?)?.trim()
        ?: System.getenv(envName)?.trim()
        ?: ""

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
        // Backend + Firebase configuration — see docs/API_MAP.md → Auth, Firebase. Empty API root = production.
        // Base URL and Firebase config must come from the SAME environment; non-production builds stay internal.
        buildConfigField("String", "API_ROOT", "\"${prop("easyesuite.apiRoot", "EASYESUITE_API_ROOT").ifBlank { "https://api-new.easyesuite.com/" }}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${prop("easyesuite.firebaseApiKey", "EASYESUITE_FIREBASE_API_KEY")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${prop("easyesuite.firebaseProjectId", "EASYESUITE_FIREBASE_PROJECT_ID")}\"")
        buildConfigField("String", "FIRESTORE_CONNECTOR_DB", "\"${prop("easyesuite.firestoreConnectorDb", "EASYESUITE_FIRESTORE_CONNECTOR_DB")}\"")
        buildConfigField("String", "FIRESTORE_NOTIFICATIONS_DB", "\"${prop("easyesuite.firestoreNotificationsDb", "EASYESUITE_FIRESTORE_NOTIFICATIONS_DB")}\"")
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
