plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.emtp.kittykiller"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.emtp.kittykiller"
        minSdk = 26 // Recomendable subir a 26 para IA
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Forzamos solo arquitectura ARM64 (la de tu Samsung A56)
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }
// --- AQUÍ ESTÁ LA CORRECCIÓN ---
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }

    // SOLUCIÓN AL ERROR DE LIBRERÍA NO ENCONTRADA
    packaging {
        jniLibs {
            // Esto es vital: impide que se rompa la librería al comprimirla
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Android Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Corrutinas
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // IA Local (MediaPipe Gemma)
    implementation("com.google.mediapipe:tasks-genai:0.10.14")

    // OCR (Google ML Kit)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")

    implementation("com.google.code.gson:gson:2.10.1")

    // PDF (Lectura de texto nativo)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // WORD (Apache POI) - Sintaxis KTS para exclusiones
    implementation("org.apache.poi:poi:5.2.3")
    implementation("org.apache.poi:poi-ooxml:5.2.3")
    implementation("org.apache.xmlbeans:xmlbeans:5.1.1")

    implementation("org.apache.poi:poi-scratchpad:5.5.1")

    // Dependencias auxiliares para POI
    implementation("javax.xml.stream:stax-api:1.0-2")
    implementation("com.fasterxml.woodstox:woodstox-core:6.5.0")
}