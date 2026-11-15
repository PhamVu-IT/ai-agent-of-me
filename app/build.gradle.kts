import java.util.zip.ZipFile

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
        // Chỉ đóng gói thư viện C++ cho máy thật 64-bit (Samsung S23 Ultra...) và máy ảo x86_64: APK nhẹ hơn
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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

    // Giải nén mô hình nghe tiếng Anh (.tar.bz2 tải từ sherpa-onnx) ngay trong app
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Nhận diện giọng chủ máy: tải sherpa-onnx-*.aar từ GitHub releases của k2-fsa/sherpa-onnx,
    // đổi tên thành sherpa-onnx.aar và đặt vào app/libs/ (mọi .aar/.jar trong libs/ đều được nạp,
    // kể cả thư viện C++ bên trong aar). Task kiemTraSherpaOnnx bên dưới kiểm tra file này trước khi build.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
}

// Kiểm tra file sherpa-onnx.aar trước khi build. Thiếu file, chọn nhầm file, hoặc file không có thư viện C++
// cho arm64-v8a thì app vẫn build được nhưng lúc lưu giọng sẽ báo "library libsherpa-onnx-jni.so not found".
// Báo lỗi ngay lúc build cho dễ sửa.
val kiemTraSherpaOnnx by tasks.registering {
    val libsDir = file("libs")
    doLast {
        val aars = libsDir.listFiles().orEmpty().filter { it.name.endsWith(".aar") }
        val huongDan = "Tải file sherpa-onnx-<phiên bản>.aar (bản Android thường, không phải .jar hay bản có chữ " +
            "\"rknn\"/\"qnn\"/\"ascend\") ở https://github.com/k2-fsa/sherpa-onnx/releases, đặt vào " +
            "${libsDir.absolutePath} rồi bấm Build > Clean Project và chạy lại."
        if (aars.isEmpty()) throw GradleException("Chưa có sherpa-onnx.aar trong app/libs/. $huongDan")
        if (aars.size > 1) throw GradleException(
            "Có ${aars.size} file .aar trong app/libs/ (${aars.joinToString { it.name }}). Chỉ giữ lại một file.")
        val aar = aars.single()
        val entries = ZipFile(aar).use { z -> z.entries().asSequence().map { it.name }.toSet() }
        val canCo = "jni/arm64-v8a/libsherpa-onnx-jni.so"
        if (canCo !in entries) throw GradleException(
            "File ${aar.name} không có $canCo nên app sẽ báo lỗi thiếu thư viện khi lưu giọng. $huongDan")
    }
}
tasks.named("preBuild") { dependsOn(kiemTraSherpaOnnx) }
