# AMAPro — Mobile & Tablet AI Face Recognition Attendance System (Modal Serverless Edition)

[![Android](https://img.shields.io/badge/Android-Kotlin-3DDC84?logo=android)](https://developer.android.com/)
[![Modal](https://img.shields.io/badge/Modal-Serverless_GPU-000000?logo=modal)](https://modal.com/)
[![NVIDIA CUDA](https://img.shields.io/badge/NVIDIA-CUDA_12.2-76B900?logo=nvidia)](https://developer.nvidia.com/cuda-toolkit)

**AMAPro** is an isolated, high-performance **Mobile & Tablet App + Modal GPU Serverless** face recognition and attendance monitoring platform. It consists exclusively of the Android native client application and the serverless cloud backend deployed on **Modal (NVIDIA T4 GPU)** with cloud volume persistence.

---

## 🌟 Key Architecture & Highlights

### 1. Mobile & Tablet Native App (`android_clientv7`)
- **Ultra-Light LiteRT Face Detection**: Runs local client-side face detection directly on device via Google LiteRT 1.4.0 (GPU Delegate + NNAPI) in **1.5–3 ms** per frame with 70% confidence filtering.
- **Proximity-Aware Motion Tracking**: Smooth 60 FPS motion extrapolation, dead-zone bounding box jitter filtering, aspect-preserving `fillCenter` alignment, and aggressive 70px camera gate trigger.
- **Responsive Mobile & Tablet UI**: Modern dark-mode interface designed for Android smartphones, wall-mounted tablets, and kiosk devices with wide proportional enrollment dialogs.

### 2. Modal Cloud GPU Backend (`modal_deploy.py`)
- **Serverless NVIDIA T4 GPU**: Scalable, pay-per-second GPU execution for 512-d AdaFace embedding extraction and FAISS vector matching.
- **Persistent Cloud Volumes (`amapro-attendance-data`)**: Persistent volume mounting for databases, enrolled portraits, FAISS gallery embeddings, and attendance logs across serverless container lifecycle.
- **Zero Local Server Overhead**: Completely serverless backend requiring no local host GPU server or local Docker containers.

---

## 🔄 Fresh Boot Lifecycle

AMAPro starts with completely clean database and photo storage states:
- `Employees_Photo/`: Initialized empty. Ready for employee enrollment via app or Modal CLI.
- `data/`: Initialized empty. Modal persistent volume automatically mounts and manages database tables and FAISS embeddings upon the first enrollment.

---

## 🚀 Quick Start Guide

### 1. Deploy Modal Serverless Backend

```bash
# Install Modal CLI if not already installed
pip install modal

# Authenticate with your Modal account
modal token set

# Deploy the AMAPro serverless application
modal deploy modal_deploy.py
```

### 2. Build Android Application

```bash
cd android_clientv7

# Build debug or release APK using Gradle
./gradlew assembleDebug

# Output APK path:
# app/build/outputs/apk/debug/AMAPro_Attendance.apk
```

---

## 📁 Repository Structure

```
AMAPro/
├── modal_deploy.py                 # Modal T4 GPU Serverless deployment script
├── requirements.txt                # Modal container Python dependencies
├── config/
│   └── config.yaml                 # Recognition thresholds & pipeline configuration
│
├── android_clientv7/               # Android Native App (Mobile & Tablet)
│   ├── app/
│   │   ├── src/main/java/com/aimonk/attendance/
│   │   │   ├── MainActivity.kt     # App entry point & CameraX preview loop
│   │   │   ├── engine/             # Client UltraLight ONNX detector & IoU tracker
│   │   │   ├── network/            # ApiService client for Modal HTTPS endpoints
│   │   │   └── ui/                 # Enrollment, directory & comparison dialogs
│   │   └── src/main/res/           # Layouts, themes, colors & strings
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
├── models/                         # ONNX models (SCRFD landmark detector, AdaFace)
├── src/                            # Backend recognition pipeline & database models
└── Employees_Photo/                # Photo directory (initialized empty for fresh boot)
```

---

## 📡 REST API Reference (Modal Serverless)

| Endpoint | Method | Payload / Params | Description |
|---|:---:|---|---|
| `/` | `GET` | — | Health check indicator |
| `/api/status` | `GET` | — | Live system metrics, enrolled count, and active mode |
| `/api/mode` | `POST` | `{"mode": "ENTRY"\|"EXIT"}` | Switch system operation mode |
| `/api/process_crop` | `POST` | `{"crop_base64": "..."}` | Runs AdaFace GPU embedding, FAISS search, and logs attendance |
| `/api/record_unknown` | `POST` | `{"crop_base64": "..."}` | Records unverified incident |
| `/api/attendance/recent` | `GET` | `?limit=50` | Recent attendance activity feed |
| `/api/attendance/flush` | `POST` | — | Resets attendance records and presence states |
| `/api/employees` | `GET` | — | Directory of enrolled employees |
| `/api/enroll` | `POST` | Multipart Form | Enrolls new employee, updates FAISS gallery and cloud volume |
| `/api/employees/{id}` | `DELETE`| — | **360-degree purge of employee across FAISS, DB, memory & disk** |

---

## 🛡️ License

© 2026 AMAPro — All rights reserved.
