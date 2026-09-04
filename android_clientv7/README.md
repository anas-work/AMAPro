# 📱 AI Monk Attendance — Native Android (Kotlin) Client (v7)

High-performance native Android Edge AI client for continuous real-time face detection, IoU trajectory tracking, and GPU recognition.

---

## ⚡ Performance Highlights
* **$1.5\text{–}3.0\text{ ms}$ Face Detection**: Powered by **Google LiteRT 1.4.0** (TFLite) with hardware acceleration / GPU delegate.
* **16KB Page-Size Aligned**: Fully compliant with Android 15 16KB memory page-size requirements (LiteRT 1.4.0 + CameraX 1.4.0).
* **Zero-Copy Camera Pipeline**: Built on **AndroidX CameraX** with direct hardware buffer memory mapping.
* **Locked 60 FPS Fluid Rendering**: Custom hardware-accelerated canvas overlay with velocity dead-reckoning motion interpolation.
* **Micro-Payload Cloud Dispatches**: Sends throttled $224 \times 224$ face crops to the Modal / GPU server via persistent OkHttp3 HTTP/2 connection pooling with custom SSL trust.

---

## 📂 Project Structure
```text
android_clientv7/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── version-RFB-320.tflite        <-- Ultra-Light LiteRT model
│       │   └── version-RFB-320.onnx
│       ├── java/com/aimonk/attendance/
│       │   ├── MainActivity.kt               <-- CameraX Lifecycle & Main Orchestrator
│       │   ├── engine/
│       │   │   ├── UltraLightDetector.kt     <-- LiteRT / TFLite Detector
│       │   │   ├── IoUTracker.kt             <-- Multi-Face Tracking & Dead-Reckoning
│       │   │   ├── CropDispatcher.kt         <-- 120px Gate & Async GPU Cloud Dispatcher
│       │   │   └── OverlayView.kt            <-- 60 FPS Custom Canvas Drawing Engine
│       │   ├── network/
│       │   │   └── ApiService.kt             <-- OkHttp3 Client (Modal / On-prem)
│       │   ├── ui/                           # UI Adapters & Dialogs
│       │   └── model/
│       │       └── AttendanceModels.kt
│       └── res/
│           ├── layout/
│           └── xml/network_security_config.xml
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 🚀 How to Build & Run

### Method 1: In Android Studio (Recommended)
1. Launch **Android Studio** (Hedgehog or newer).
2. Click **Open** and select the `android_clientv7/` folder.
3. Wait for Gradle Sync to complete (Android SDK 35, minSdk 26).
4. Connect an Android phone / tablet via USB (or start an Android Virtual Device).
5. Click **Run (`Shift + F10`)**.

### Method 2: Build APK via Terminal / Gradle CLI
```bash
cd android_clientv7
./gradlew assembleDebug
```
The output APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## ⚙️ Server Configuration
To point the app to a different GPU server, update the `baseUrl` in [`MainActivity.kt`](file:///h3/anas/ABLBL_AttendanceV2/android_clientv7/app/src/main/java/com/aimonk/attendance/MainActivity.kt) and [`ApiService.kt`](file:///h3/anas/ABLBL_AttendanceV2/android_clientv7/app/src/main/java/com/aimonk/attendance/network/ApiService.kt):
```kotlin
val apiService = ApiService("https://aimonk-labs--ablbl-attendance.modal.run") // Or https://YOUR_SERVER_IP:9001
```
Also update the domain in `app/src/main/res/xml/network_security_config.xml` if needed.
