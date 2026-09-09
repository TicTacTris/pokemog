plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigning = listOf(
    "POKEMOG_KEYSTORE_FILE", "POKEMOG_KEYSTORE_PASSWORD",
    "POKEMOG_KEY_ALIAS", "POKEMOG_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank) }
val hasReleaseSigning = releaseSigning.values.all { it != null }
check(hasReleaseSigning || releaseSigning.values.all { it == null }) {
    "Release signing requires all four POKEMOG signing environment variables, or none for unsigned CI."
}

tasks.register("verifyReleaseSigning") {
    doLast {
        check(hasReleaseSigning) { "Publishing requires dedicated release signing credentials; unsigned CI builds cannot be published." }
        check(file(releaseSigning.getValue("POKEMOG_KEYSTORE_FILE")!!).isFile) { "Release keystore is missing." }
    }
}

android {
    namespace = "dev.pokemog.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.pokemog.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 12
        versionName = "0.10.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (hasReleaseSigning) create("release") {
            storeFile = file(releaseSigning.getValue("POKEMOG_KEYSTORE_FILE")!!)
            storePassword = releaseSigning.getValue("POKEMOG_KEYSTORE_PASSWORD")
            keyAlias = releaseSigning.getValue("POKEMOG_KEY_ALIAS")
            keyPassword = releaseSigning.getValue("POKEMOG_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") { applicationIdSuffix = ".debug" }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets.getByName("test").resources.srcDir("src/main/assets")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
