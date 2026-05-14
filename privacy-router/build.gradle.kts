plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.privacyrouter"
    compileSdk = 34

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }
}

dependencies {
    implementation(project(":core"))

    // MediaPipe LLM Inference (Path B — Gemma 3 4B + FunctionGemma 270M)
    implementation("com.google.mediapipe:tasks-genai:0.10.14")

    // TFLite + NNAPI delegate (Stage 1 classifier, Stage 2 NER, Stage 2B YOLO)
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")

    // ML Kit — face detection (Stage 2B Tier 0)
    implementation("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")
    // ML Kit — text recognition v2 / OCR bridge (Stage 2B Tier 0)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    // ML Kit — document scanner shape detection (Stage 2B Tier 0 pre-signal)
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")

    // ONNX Runtime — Silero VAD (Stage 2B Tier 2 audio)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // HTTP + JSON (Path C cloud API, policy config)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")

    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.8.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
