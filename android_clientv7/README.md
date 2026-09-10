# 📱 AI Monk Attendance — Native Android (Kotlin) Client (v7)

High-performance native Android Edge AI client for continuous real-time face detection, IoU trajectory tracking, and GPU recognition.

---

## ⚡ Performance Highlights
* **$1.5\text{–}3.0\text{ ms}$ Face Detection**: Powered by **Google LiteRT 1.4.0** (TFLite) with GPU delegate and NNAPI fallback.
* **Aggressive Frontal Detection (70% Confidence)**: Calibrated at `confThreshold = 0.70f` to aggressively filter background noise and instantly lock onto frontal faces.
* **Natural Bounding Box Alignment**: Natural raw bounding box coordinates aligned precisely to face contours without artificial cuts or offsets.
* **Aspect-Preserving `fillCenter` Uniform Scaling**: Pixel-perfect bounding box alignment matching CameraX `PreviewView` layout.
* **Dead-Zone Jitter & Dimension Stabilization**: Bounding box center locked when stationary ($< 4\text{ px}$) and dimensions stabilized ($< 3.5\text{ px}$) to eliminate jitter and pulsing.
* **Thermal Throttling Protection**: 2-thread LiteRT execution with decoupled GPU delegate prevents chipset heating and sustains consistent 60 FPS without dropping frames.
* **Zero-Allocation Live Loop & Large Heap**: Pre-allocated byte buffers, bitmaps, `RectF`, and `Paint` caches combined with `android:largeHeap="true"` to prevent Garbage Collection pauses.
* **Micro-Payload Cloud Dispatches (70px Gate)**: Automatically captures and sends $224 \times 224$ crops to the Modal GPU server once the face reaches the 70px gate with a 600ms settle delay.

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
│       │   │   ├── UltraLightDetector.kt     <-- LiteRT Detector (70% conf, GPU/NNAPI)
│       │   │   ├── IoUTracker.kt             <-- Multi-Face Tracking & Motion Extrapolation
│       │   │   ├── CropDispatcher.kt         <-- 70px Gate & Async GPU Cloud Dispatcher
│       │   │   └── OverlayView.kt            <-- 60 FPS Custom Canvas Drawing Engine
│       │   ├── network/
│       │   │   └── ApiService.kt             <-- OkHttp3 Client (Modal Cloud Backend)
│       │   ├── ui/                           # UI Adapters & Redesigned Dialogs
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

### Build APK via Terminal / Gradle CLI
```bash
cd android_clientv7
./gradlew assembleDebug
```
The output APK is generated at:
`app/build/outputs/apk/debug/AMAPro_Attendance.apk`

---

## ⚙️ Server Configuration
The app is pre-configured to communicate with the production Modal Serverless GPU backend:
```kotlin
val apiService = ApiService("https://aimonk-labs--amapro-attendance.modal.run")
```
