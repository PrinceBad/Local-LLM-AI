# Local LLM/AI 🤖📱

A premium, modern, and high-performance offline Android application for running Large Language Models (LLMs) entirely on-device. Powered by **Google MediaPipe Tasks GenAI** and built using modern **Jetpack Compose (Material 3)**.

No internet required. No data leaves your device. Total privacy, absolute speed.

---

## ✨ Features

- **On-Device Local Inference**: Runs models natively on your mobile hardware. Fully offline, ensuring 100% privacy and zero latency from network roundtrips.
- **Hardware Acceleration**: Automatically leverages mobile GPU backends (Vulkan) for fast token generation, falling back gracefully to optimized CPU execution.
- **Model Manager**: Integrated download manager to pull pre-configured LLM models (like Gemma 2B) directly from URLs or load custom local `.task` files.
- **Premium Material 3 UI**: Clean, stunning interface featuring dynamic theme color harmonization, fluid animations, structured card views, and clean chat bubbles.
- **Streaming Responses**: Real-time word-by-word token streaming for interactive and responsive conversational experiences.

---

## 🚀 Hardware & Software Requirements

- **Processor**: High-end ARM64 processor (e.g., Snapdragon 8 Gen 1+, Google Tensor G2+, Dimensity 9000+).
- **RAM**: Minimum 6 GB of physical system memory (8 GB+ highly recommended to prevent out-of-memory states).
- **OS Version**: Android 8.0 (API level 26) or higher.
- **Graphics**: Vulkan-compatible GPU driver for accelerated hardware performance.

---

## 🛠️ Getting Started & Installation

### 1. Download the App
Simply go to the [Releases](https://github.com/PrinceBad/Local-LLM-AI/releases) section of this repository and download the latest `app-debug.apk` file. Install it on your compatible Android device.

### 2. Add an LLM Model
Because LLM model files are large (1.5 GB+), they are not packaged inside the APK.
1. Open the **Local LLM Manager** in the app.
2. Select one of the pre-configured models (e.g., Gemma 2B).
3. Tap **Download** to stream it directly to your device's local storage.
4. Once completed, select it as the active model and start chatting!

---

## 📦 Project Structure & Architecture

```
Local-LLM-AI/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/auralocalai/
│   │   │   ├── data/            # Local LLM Inference Engine & Download Managers
│   │   │   ├── ui/              # Compose ViewModels & Core Theme Configurations
│   │   │   │   └── screens/     # Chat and Model Management Screens
│   │   │   └── MainActivity.kt  # Root Launcher Activity
│   │   └── AndroidManifest.xml  # Permissions, Themes, and App Configurations
│   └── build.gradle.kts         # Core App Dependencies (MediaPipe, Compose, OkHttp)
├── gradle/                      # Gradle Wrapper & Version Catalogs
└── build.gradle.kts             # Root Build Configuration
```

---

## 💡 Technical Implementation Details

- **MediaPipe Tasks GenAI**: Leverages `LlmInference` with optimized native JNI binaries to run quantized models on mobile chips.
- **Jetpack Compose & Material 3**: Crafted with high-fidelity components, including `AnimatedVisibility`, HSL color palettes, custom rounded boundaries, and scroll-to-bottom list synchronizations.
- **Async Streaming Flow**: Utilizes Kotlin Coroutines `callbackFlow` and `flowOn(Dispatchers.IO)` to stream output tokens safely off the main UI thread.

---

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.
