plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aimonk.attendance"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ablbl.testv1"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

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

    androidResources {
        noCompress += "tflite"
    }

    packaging {
        jniLibs {
            // Android 15 16 KB page alignment compatibility
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.api.ApkVariantOutput
            output.outputFileName = "AMAPro_Attendance.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.window:window:1.3.0")

    // 1. CameraX 1.4.0 — fixes libimage_processing_util_jni.so 16KB alignment
    val cameraxVersion = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // 2. LiteRT — Google's TFLite successor (same org.tensorflow.lite API, 16KB page-size aligned)
    //    Replaces: org.tensorflow:tensorflow-lite + tensorflow-lite-gpu
    //    No source changes needed — package names are identical.
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("com.google.ai.edge.litert:litert-api:1.4.0")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.0")
    implementation("com.google.ai.edge.litert:litert-support:1.4.0")

    // 3. Fast Network Client (OkHttp3 & Gson)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // 4. Coroutines Lifecycle & Coil Image Loader
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("io.coil-kt:coil:2.7.0")
}
