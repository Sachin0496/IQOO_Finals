plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.mouna"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mouna"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2-finale"
        ndk { abiFilters += "arm64-v8a" } // the OnePlus 13R and iQOO 15; QNN ships arm64 only
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug") // hackathon build only; never ship this
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    androidResources { noCompress += listOf("task", "onnx", "bin") }
    packaging { jniLibs { useLegacyPackaging = true } } // QNN loads its HTP skel libs from the native lib dir
}

// The face landmarker model is vendored once by the Lab (lab/scripts/vendor.mjs); reuse it.
val copyFaceModel by tasks.registering(Copy::class) {
    val src = rootProject.file("../lab/public/models/face_landmarker.task")
    doFirst { check(src.exists()) { "Missing $src. Run `npm run vendor` in lab/ first." } }
    from(src)
    into(layout.projectDirectory.dir("src/main/assets"))
}

// The pre-rendered voice pack (voices/render.py) is shared with the Lab: one source.
val copyVoices by tasks.registering(Copy::class) {
    from(rootProject.file("../lab/public/voices"))
    into(layout.projectDirectory.dir("src/main/assets/voices"))
}
// sherpa-onnx (Apache-2.0) for on-device Whisper: the AAR with ONNX Runtime statically linked, so it cannot clash
// with the app's own ONNX Runtime (QNN). Fetched once at build time; the app itself has no internet.
val sherpaVersion = "1.13.8"
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar")
val fetchSherpa by tasks.registering {
    outputs.file(sherpaAar)
    doLast {
        val f = sherpaAar.asFile
        if (!f.exists()) {
            f.parentFile.mkdirs()
            uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/${f.name}").toURL().openStream().use { i -> f.outputStream().use { i.copyTo(it) } }
        }
    }
}
// MediaPipe body and hand models (Apache-2.0) for sign mode: keypoints for AI4Bharat's ISL model.
val mediapipeModels = mapOf(
    "pose_landmarker_lite.task" to "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task",
    "hand_landmarker.task" to "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task",
)
val fetchMediapipe by tasks.registering {
    val dir = layout.projectDirectory.dir("src/main/assets")
    outputs.files(mediapipeModels.keys.map { dir.file(it) })
    doLast {
        for ((name, url) in mediapipeModels) {
            val f = dir.file(name).asFile
            if (!f.exists()) {
                f.parentFile.mkdirs()
                uri(url).toURL().openStream().use { i -> f.outputStream().use { i.copyTo(it) } }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(copyFaceModel, copyVoices, fetchSherpa, fetchMediapipe) }

dependencies {
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mediapipe:tasks-vision:0.10.20")
    // Lip encoder on the Hexagon NPU: ONNX Runtime + QNN EP (>= 1.28; 1.27 miscomputes on SM8850, sherpa-onnx #3845)
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0")
    // Speech for weak or unclear voices: Whisper tiny.en through sherpa-onnx (existing model, no training)
    implementation(files(sherpaAar))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation(project(":core")) // decision core (harness/mouna_harness/core.py port)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
