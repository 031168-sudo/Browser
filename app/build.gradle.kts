plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.andrey.readerlite"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.andrey.readerlite"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.10.0"
    }

    // Постоянный ключ: все сборки подписаны одинаково, поэтому новые версии
    // ставятся поверх старых без удаления приложения.
    signingConfigs {
        create("readerlite") {
            storeFile = file("readerlite.jks")
            storePassword = "bN7RwVAverhqgkR5C40C4tJf"
            keyAlias = "readerlite"
            keyPassword = "bN7RwVAverhqgkR5C40C4tJf"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("readerlite")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("readerlite")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jsoup:jsoup:1.18.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
