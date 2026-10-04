pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "easyesuite-mobile"

// :core is pure Kotlin/JVM and builds anywhere (CI unit tests need no Android SDK).
include(":core")

// :app needs the Android SDK. It is only wired in when an SDK is available so that
// `./gradlew :core:test` works on a plain JVM box.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    File(rootDir, "local.properties").exists()
if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle("Android SDK not found — skipping :app (set ANDROID_HOME or add local.properties).")
}
