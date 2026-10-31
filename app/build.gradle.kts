plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
}

val releaseCredentials = listOf("CAMPUS_KEYSTORE", "CAMPUS_STORE_PASSWORD", "CAMPUS_KEY_ALIAS", "CAMPUS_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull }
require(releaseCredentials.values.all { it.isNullOrBlank() } || releaseCredentials.values.none { it.isNullOrBlank() }) {
    "Release signing requires all four CAMPUS_KEYSTORE, CAMPUS_STORE_PASSWORD, CAMPUS_KEY_ALIAS and CAMPUS_KEY_PASSWORD environment variables."
}

android {
    namespace = "com.seb.campustimetable"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.seb.campustimetable"
        minSdk = 26
        targetSdk = 37
        versionCode = 12
        versionName = "1.8.0"
        testInstrumentationRunner = if (providers.gradleProperty("widgetVerification").orNull == "true")
            "com.seb.campustimetable.WidgetInstrumentation"
        else if (providers.gradleProperty("interfaceVerification").orNull == "true")
            "com.seb.campustimetable.InterfaceInstrumentation"
        else if (providers.gradleProperty("motionVerification").orNull == "true")
            "com.seb.campustimetable.MotionInstrumentation" else "com.seb.campustimetable.BackupInstrumentation"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Both interface languages must remain available for offline switching.
    bundle { language { enableSplit = false } }

    buildFeatures {
        viewBinding = true
    }

    buildTypes.getByName("debug") {
        if (providers.gradleProperty("isolatedVerification").orNull == "true") {
            applicationIdSuffix = ".verification"
        }
    }

    if (releaseCredentials.values.none { it.isNullOrBlank() }) {
        signingConfigs {
            create("configuredRelease") {
                storeFile = file(releaseCredentials.getValue("CAMPUS_KEYSTORE")!!)
                storePassword = releaseCredentials.getValue("CAMPUS_STORE_PASSWORD")
                keyAlias = releaseCredentials.getValue("CAMPUS_KEY_ALIAS")
                keyPassword = releaseCredentials.getValue("CAMPUS_KEY_PASSWORD")
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("configuredRelease")
    }
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
