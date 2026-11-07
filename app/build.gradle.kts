plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "vn.phamvu.trolyai"
    compileSdk = 35
    defaultConfig {
        applicationId = "vn.phamvu.trolyai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Mô hình AI chạy hoàn toàn trên máy (MediaPipe LLM Inference)
    implementation("com.google.mediapipe:tasks-genai:0.10.24")

    // Lưu trí nhớ, nhắc nhở, lịch sử chat
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Giao diện
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    // Chạy việc "học ban đêm" khi máy sạc và đang rảnh
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Vân tay / khuôn mặt / mã PIN
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // Mã hoá cơ sở dữ liệu
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Nhận diện giọng chủ máy: tải sherpa-onnx-*.aar từ GitHub releases của k2-fsa/sherpa-onnx,
    // đổi tên thành sherpa-onnx.aar và đặt vào app/libs/
    implementation(files("libs/sherpa-onnx.aar"))
}
