import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "org.gpdresearch.hyperbrowser"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "org.gpdresearch.hyperbrowser"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "3.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Credentials come from keystore.properties (git-ignored) or the environment on CI.
            val properties = Properties().apply {
                val file = rootProject.file("keystore.properties")
                if (file.exists()) file.inputStream().use { load(it) }
            }
            fun setting(key: String, variable: String): String? =
                properties.getProperty(key) ?: System.getenv(variable)

            setting("storeFile", "HB_KEYSTORE_FILE")?.let { path ->
                storeFile = rootProject.file(path)
                storePassword = setting("storePassword", "HB_KEYSTORE_PASSWORD")
                keyAlias = setting("keyAlias", "HB_KEY_ALIAS")
                keyPassword = setting("keyPassword", "HB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = android.signingConfigs.findByName("debug")?.apply {
                storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }

        release {
            // R8 is off: the Drive REST models are bound reflectively by GSON.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
                ?: run {
                    logger.warn("No release keystore configured - release artifacts will be unsigned.")
                    null
                }
        }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/INDEX.LIST",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("com.google.api-client:google-api-client-android:2.7.0") {
        exclude(group = "org.apache.httpcomponents")
        exclude(module = "guava-jdk5")
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20240914-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
        exclude(module = "guava-jdk5")
    }
    implementation("com.google.http-client:google-http-client-gson:1.45.0") {
        exclude(group = "org.apache.httpcomponents")
    }

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}