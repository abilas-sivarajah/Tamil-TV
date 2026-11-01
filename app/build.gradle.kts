plugins {
    id("com.android.application")
}

android {
    namespace = "com.tvclient.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tvclient.app"
        // Fire OS 5 (Fire TV Stick 2. Gen) = API 22, Fire OS 7 = API 28
        minSdk = 22
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Basis-URL kommt aus gradle.properties -> kein Code-Edit beim Serverwechsel
        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"${project.findProperty("tvclient.apiBaseUrl") ?: "http://10.0.2.2:8000/"}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Fuer den Eigengebrauch mit dem Debug-Schluessel signieren
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    val media3 = "1.8.0"
    val retrofitV = "2.11.0"
    val okhttpV = "4.12.0"

    // --- Android TV / Leanback: Fokus- und D-Pad-Navigation out of the box ---
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // --- Player ---
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    // --- Netzwerk ---
    implementation("com.squareup.retrofit2:retrofit:$retrofitV")
    implementation("com.squareup.retrofit2:converter-gson:$retrofitV")
    implementation("com.squareup.okhttp3:okhttp:$okhttpV")
    implementation("com.squareup.okhttp3:logging-interceptor:$okhttpV")

    // --- Bilder ---
    implementation("io.coil-kt:coil:2.7.0")

    // --- HTML-Scraper (Standalone ohne PC) ---
    implementation("org.jsoup:jsoup:1.18.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
